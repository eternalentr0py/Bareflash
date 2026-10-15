package com.bareflash.app.dump

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.system.Os
import androidx.documentfile.provider.DocumentFile
import com.bareflash.app.AppGraph
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Direct dump destination. When the user has picked a folder with
 * Storage Access Framework, Python writes straight into that folder
 * through a detached native fd ([openFd]) — bytes never land in the
 * app's private `dumps/` first. When no folder is picked, fds point at
 * the original app-private directory so existing commands keep working.
 *
 * Called from Kotlin (Dumps tab, space guard) and from Python
 * (`saf_fs.py` via Chaquopy).
 */
object DumpBridge {

    @Volatile private var ctx: Context? = null
    private val lock = Any()

    data class Entry(
        val name: String,
        val size: Long,
        val modified: Long,
        val uri: Uri,
        val localFile: File?,
    )

    /**
     * Reject path traversal and other unsafe relative paths before any
     * File / DocumentFile walk. Returns the cleaned relative path, or null
     * if the input is blank, absolute, or contains ".." / empty segments
     * that would escape the dump root.
     */
    private fun sanitizeRelPath(relPath: String): String? {
        if (relPath.isBlank() || relPath == "." ) return ""
        // Absolute or Windows-style paths are never valid dump-relative names.
        if (relPath.startsWith("/") || relPath.startsWith("\\") || relPath.contains(':')) return null
        val parts = relPath.split('/', '\\')
        val clean = ArrayList<String>(parts.size)
        for (p in parts) {
            when {
                p.isEmpty() || p == "." -> continue
                p == ".." -> return null
                // Refuse names that DocumentFile / FAT cannot safely store
                // and that often appear in traversal payloads.
                p.contains('\u0000') || p == "." || p == ".." -> return null
                else -> clean.add(p)
            }
        }
        return if (clean.isEmpty()) "" else clean.joinToString("/")
    }

    @JvmStatic
    fun init(context: Context) {
        ctx = context.applicationContext
        PartialDumps.init(context.applicationContext)
        WriteMarker.init(context.applicationContext)
        // Older versions copied every file to be flashed into app storage first
        // (and could leave multi-GB leftovers behind). Flash inputs are read in
        // place now, so remove any that are still there.
        kotlin.concurrent.thread(name = "dump-sweep", isDaemon = true) {
            runCatching {
                localDumps().listFiles { f -> f.name.endsWith("_flash_input.img") }?.forEach { it.delete() }
            }
        }
    }

    @JvmStatic
    fun isEnabled(): Boolean = synchronized(lock) { treeRoot() != null }

    @JvmStatic
    fun displayName(): String {
        val name = AppGraph.settings.state.value.general.dumpFolderName
        return if (isEnabled() && name.isNotBlank()) name else "App folder"
    }

    fun currentUri(): Uri? = treeUri()

    /**
     * Persist a tree the user picked with OpenDocumentTree. Returns a
     * user-facing folder name, or null if write access could not be taken.
     */
    fun takeTree(uri: Uri): String? {
        val app = ctx ?: return null
        return runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val name = DocumentFile.fromTreeUri(app, uri)?.name
                ?: uri.lastPathSegment?.substringAfterLast(':')
                ?: "Chosen folder"
            AppGraph.settings.update {
                it.copy(general = it.general.copy(dumpFolderUri = uri.toString(), dumpFolderName = name))
            }
            name
        }.getOrNull()
    }

    fun clearTree() {
        val app = ctx ?: return
        treeUri()?.let { uri ->
            runCatching {
                app.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        AppGraph.settings.update {
            it.copy(general = it.general.copy(dumpFolderUri = "", dumpFolderName = ""))
        }
    }

    /** Native fd for Python `os.fdopen`. -1 on failure. Mode is "r" or "w". */
    @JvmStatic
    fun openFd(relPath: String, mode: String): Int = synchronized(lock) {
        val path = sanitizeRelPath(relPath) ?: return -1
        val writing = mode.startsWith("w") || mode.startsWith("a") || mode.startsWith("x")
        val root = treeRoot()
        if (writing) {
            if (root != null) {
                val doc = ensureFile(root, path) ?: return -1
                val resumeAt = resumeOffsets.remove(path)
                if (resumeAt != null && resumeAt > 0L) {
                    // Continue a partial dump: never fall back to a truncating open.
                    return detachAt(doc.uri, resumeAt)
                }
                return detach(doc.uri, writing = true, append = mode.startsWith("a"))
            }
            return openLocal(path, writing = true, append = mode.startsWith("a"))
        }
        // Picked inputs are keyed by the original (already sanitized) name.
        picked[path]?.let { p ->
            val fd = detach(p.uri, writing = false, append = false)
            if (fd >= 0) return fd
        }
        // Also try the raw key in case attach stored a display name with no slash.
        if (path != relPath) {
            picked[relPath]?.let { p ->
                val fd = detach(p.uri, writing = false, append = false)
                if (fd >= 0) return fd
            }
        }
        if (root != null) {
            cachedFind(root, path)?.takeIf { it.isFile }?.let {
                val fd = detach(it.uri, writing = false, append = false)
                if (fd >= 0) return fd
            }
        }
        return openLocal(path, writing = false, append = false)
    }

    @JvmStatic
    fun mkdir(relPath: String): Boolean = synchronized(lock) {
        val path = sanitizeRelPath(relPath) ?: return false
        if (path.isEmpty()) return true
        val root = treeRoot()
        if (root != null) return ensureDir(root, path) != null
        val dir = safeLocalFile(path) ?: return false
        return dir.exists() || dir.mkdirs()
    }

    @JvmStatic
    fun exists(relPath: String): Boolean = synchronized(lock) {
        val path = sanitizeRelPath(relPath) ?: return false
        if (path.isEmpty()) {
            return treeRoot() != null || localDumps().let { it.exists() || it.mkdirs() }
        }
        if (picked.containsKey(path) || picked.containsKey(relPath)) return true
        treeRoot()?.let { root -> if (cachedFind(root, path) != null) return true }
        return safeLocalFile(path)?.exists() == true
    }

    @JvmStatic
    fun isDir(relPath: String): Boolean = synchronized(lock) {
        val path = sanitizeRelPath(relPath) ?: return false
        if (path.isEmpty()) return true
        if (picked.containsKey(path) || picked.containsKey(relPath)) return false
        treeRoot()?.let { root -> cachedFind(root, path)?.let { return it.isDirectory } }
        return safeLocalFile(path)?.isDirectory == true
    }

    @JvmStatic
    fun availableBytes(): Long {
        val uri = synchronized(lock) { treeUri() }
        if (uri != null) {
            volumePath(uri)?.let { path ->
                val n = runCatching { StatFs(path.absolutePath).availableBytes }.getOrDefault(-1L)
                if (n >= 0L) return n
            }
            val root = synchronized(lock) { treeRoot() }
            root?.let { r ->
                val probe = r.listFiles().firstOrNull { it.isFile } ?: r
                val n = runCatching {
                    ctx!!.contentResolver.openFileDescriptor(probe.uri, "r")?.use { pfd ->
                        val st = Os.fstatvfs(pfd.fileDescriptor)
                        st.f_bavail * st.f_frsize
                    } ?: -1L
                }.getOrDefault(-1L)
                if (n >= 0L) return n
            }
            return -1L
        }
        val dir = localDumps()
        runCatching { if (!dir.exists()) dir.mkdirs() }
        return try { StatFs(dir.absolutePath).availableBytes } catch (_: Exception) { -1L }
    }

    fun listEntries(): List<Entry> {
        // Only the app's own folder inside the chosen dump folder; nothing when no
        // folder is chosen (there is no app-storage fallback to browse).
        val root = synchronized(lock) { treeRoot() } ?: return emptyList()
        return collect(root, "")
    }

    fun delete(entry: Entry): Boolean {
        entry.localFile?.let { return it.delete() }
        val app = ctx ?: return false
        return DocumentFile.fromSingleUri(app, entry.uri)?.delete() == true
    }

    fun shareUri(entry: Entry): Uri = entry.uri

    @JvmStatic
    fun listJson(): String {
        val arr = JSONArray()
        for (e in listEntries()) {
            arr.put(JSONObject().apply {
                put("name", e.name)
                put("size", e.size)
                put("modified", e.modified)
            })
        }
        return arr.toString()
    }

    /**
     * Working directory for the Python and Qualcomm engines (they need a real
     * path under their cwd). It is not a place to keep dumps: nothing here is
     * listed, and MediaTek dumps refuse to run without a chosen folder.
     */
    fun localDumps(): File {
        val app = ctx ?: throw IllegalStateException("DumpBridge.init was not called")
        return File(app.getExternalFilesDir(null), "dumps").apply { mkdirs() }
    }


    // ---- input files: read in place, never copied ---------------------------

    /** A file the user picked from anywhere; [real] is its filesystem path when one exists. */
    private class Picked(val uri: Uri, val real: File?, val size: Long)

    /** What [attachInput] hands back: the value to put in the command, and where the file is. */
    data class Attached(val value: String, val size: Long, val inChosenFolder: Boolean)

    private val picked = java.util.concurrent.ConcurrentHashMap<String, Picked>()

    /** True once at least one file outside the chosen folder has been attached. */
    @JvmStatic
    fun hasPicked(): Boolean = picked.isNotEmpty()

    /**
     * Registers a file the user picked for a command that reads it (flash,
     * write, restore ...). Nothing is copied: a file inside the chosen dump
     * folder is addressed by its path there; a file elsewhere is remembered by
     * name and opened through its own URI when the command reads it.
     */
    fun attachInput(uri: Uri): Attached? {
        val app = ctx ?: return null
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        var name = "attached_file"
        var size = -1L
        app.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (n >= 0) name = c.getString(n) ?: name
                val z = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (z >= 0 && !c.isNull(z)) size = c.getLong(z)
            }
        }
        name = File(name).name.ifBlank { "attached_file" }
        relativeToTree(uri)?.let { rel ->
            synchronized(lock) { picked.remove(rel) }
            return Attached(rel, size, true)
        }
        synchronized(lock) { picked[name] = Picked(uri, realPathOf(uri), size) }
        return Attached(name, size, false)
    }

    /** Files attached from outside the dump folder that the command names, as (name, uri) for a preset to remember. */
    fun pickedFor(command: String): List<Pair<String, String>> =
        picked.entries.filter { (name, _) -> command.contains("dumps/$name") || command.split(' ', ',', '"', '\'').contains(name) }
            .map { it.key to it.value.uri.toString() }

    /**
     * Registers a file a preset remembered. Returns false when its permission is gone or it can
     * no longer be opened, so the caller can say which file is missing.
     */
    fun restoreInput(name: String, uri: Uri): Boolean {
        val app = ctx ?: return false
        if (app.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) return false
        val opens = runCatching { app.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
        if (!opens) return false
        var size = -1L
        runCatching {
            app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val z = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (z >= 0 && !c.isNull(z)) size = c.getLong(z)
                }
            }
        }
        synchronized(lock) { picked[name] = Picked(uri, realPathOf(uri), size) }
        return true
    }

    /** Forget a file attached with [attachInput] (it was removed from the command). */
    fun forgetInput(name: String) {
        synchronized(lock) { picked.remove(name) }
    }

    /** Size in bytes of a dumps-relative file, or -1 when it does not exist. */
    @JvmStatic
    fun size(relPath: String): Long = synchronized(lock) {
        val path = sanitizeRelPath(relPath) ?: return -1L
        picked[path]?.let { if (it.size >= 0) return it.size }
        if (path != relPath) picked[relPath]?.let { if (it.size >= 0) return it.size }
        treeRoot()?.let { root -> cachedFind(root, path)?.takeIf { it.isFile }?.let { return it.length() } }
        val f = safeLocalFile(path) ?: return -1L
        return if (f.isFile) f.length() else -1L
    }

    /**
     * The real filesystem file behind a dumps-relative input (for the QDL
     * engine, which opens paths itself), or null when it has none or cannot
     * be read. An empty [relPath] means the chosen folder itself.
     */
    fun realInput(relPath: String): File? = synchronized(lock) {
        val path = sanitizeRelPath(relPath) ?: return null
        picked[path]?.let { p -> return p.real?.takeIf { it.canRead() } }
        if (path != relPath) picked[relPath]?.let { p -> return p.real?.takeIf { it.canRead() } }
        val tree = treeUri() ?: return null
        val base = volumePath(tree)?.let { File(it, APP_DIR) } ?: return null
        val f = if (path.isEmpty()) base else File(base, path)
        // Keep the resolved path under the app subfolder of the chosen volume.
        return runCatching {
            val baseCanon = base.canonicalFile
            val fileCanon = f.canonicalFile
            if (fileCanon == baseCanon || fileCanon.path.startsWith(baseCanon.path + File.separator)) {
                f.takeIf { it.canRead() }
            } else null
        }.getOrNull()
    }

    private fun relativeToTree(uri: Uri): String? {
        val tree = treeUri() ?: return null
        if (uri.authority != tree.authority) return null
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val prefix = if (treeId.endsWith(":")) treeId else "$treeId/"
        if (!docId.startsWith(prefix)) return null
        // Only files inside the app's own subfolder are addressed by path; a file
        // elsewhere in the chosen folder is opened through its own URI.
        val inApp = docId.removePrefix(prefix)
        return if (inApp.startsWith("$APP_DIR/")) inApp.removePrefix("$APP_DIR/").takeIf { it.isNotEmpty() } else null
    }

    private fun realPathOf(uri: Uri): File? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val vol = id.substringBefore(':')
        val rest = id.substringAfter(':', "")
        val base = if (vol.equals("primary", ignoreCase = true)) Environment.getExternalStorageDirectory()
        else File("/storage/$vol")
        return File(base, rest).takeIf { it.exists() }
    }

    // ---- internals -------------------------------------------------------

    private fun treeUri(): Uri? {
        val raw = AppGraph.settings.state.value.general.dumpFolderUri
        if (raw.isBlank()) return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val app = ctx ?: return null
        val ok = app.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        return if (ok) uri else null
    }

    private const val APP_DIR = "Bareflash"
    /** How long the SAF tree root and child lookups stay valid. */
    private const val SCOPE_TTL_MS = 30_000L

    private var scopedFor: Uri? = null
    private var scopedDir: DocumentFile? = null
    private var scopedAt = 0L
    /** Short-lived cache of sanitized relative path → DocumentFile under treeRoot. */
    private val docCache = java.util.HashMap<String, DocumentFile>(32)
    private var docCacheAt = 0L

    /**
     * The app's own folder inside the chosen dump folder (created on first use),
     * or null when no folder is chosen or it cannot be written. Every read,
     * write, existence check and listing goes through here, so other files in the
     * chosen folder are never touched or shown. The lookup is cached because
     * SAF queries are slow and Python asks often.
     */
    private fun treeRoot(): DocumentFile? = synchronized(lock) {
        val app = ctx ?: return null
        val uri = treeUri() ?: return null
        val now = android.os.SystemClock.elapsedRealtime()
        if (scopedFor == uri && scopedDir != null && now - scopedAt < SCOPE_TTL_MS) return scopedDir
        val base = DocumentFile.fromTreeUri(app, uri)?.takeIf { it.canWrite() } ?: return null
        val dir = base.listFiles().firstOrNull { it.name == APP_DIR && it.isDirectory }
            ?: base.createDirectory(APP_DIR)
            ?: return null
        scopedFor = uri
        scopedDir = dir
        scopedAt = now
        docCache.clear()
        docCacheAt = now
        return dir
    }

    private fun cachedFind(parent: DocumentFile, path: String): DocumentFile? {
        if (path.isEmpty()) return parent
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - docCacheAt < SCOPE_TTL_MS) {
            docCache[path]?.let { return it }
        } else {
            docCache.clear()
            docCacheAt = now
        }
        val found = find(parent, path) ?: return null
        docCache[path] = found
        return found
    }

    private fun find(parent: DocumentFile, relPath: String): DocumentFile? {
        // relPath is already sanitized (no "..", no empties).
        var cur = parent
        val parts = if (relPath.isEmpty()) emptyList() else relPath.split('/')
        if (parts.isEmpty()) return parent
        for ((i, part) in parts.withIndex()) {
            val child = cur.listFiles().firstOrNull { it.name == part } ?: return null
            if (i == parts.lastIndex) return child
            if (!child.isDirectory) return null
            cur = child
        }
        return null
    }

    private fun ensureDir(parent: DocumentFile, relPath: String): DocumentFile? {
        var cur = parent
        if (relPath.isEmpty()) return parent
        var created = false
        for (part in relPath.split('/')) {
            val existing = cur.listFiles().firstOrNull { it.name == part && it.isDirectory }
            if (existing == null) {
                cur = cur.createDirectory(part) ?: return null
                created = true
            } else {
                cur = existing
            }
        }
        if (created) docCache.clear()
        return cur
    }

    private fun ensureFile(parent: DocumentFile, relPath: String): DocumentFile? {
        if (relPath.isEmpty()) return null
        val parts = relPath.split('/')
        val dir = if (parts.size == 1) parent else ensureDir(parent, parts.dropLast(1).joinToString("/")) ?: return null
        val name = parts.last()
        dir.listFiles().firstOrNull { it.name == name && it.isFile }?.let { return it }
        val created = dir.createFile("application/octet-stream", name) ?: return null
        docCache.clear()
        docCache[relPath] = created
        return created
    }

    // ---- resume, text sidecars, filesystem probe ---------------------------

    private val resumeOffsets = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** The next write-open of [relPath] keeps its first [offset] bytes and continues from there. */
    fun armResume(relPath: String, offset: Long) {
        val path = sanitizeRelPath(relPath) ?: return
        resumeOffsets[path] = offset
    }

    fun clearResume() { resumeOffsets.clear() }

    /** Pending resume offset for [relPath] (0 when none). Peeks; does not consume. */
    @JvmStatic
    fun resumeOffset(relPath: String): Long {
        val path = sanitizeRelPath(relPath) ?: return 0L
        return resumeOffsets[path] ?: 0L
    }

    /** Whether Python should hash dump files as it writes them (Settings > Experimental). */
    @JvmStatic
    fun hashEnabled(): Boolean = com.bareflash.app.UsbTuning.hashDumps.value

    /**
     * Opens the file for writing without truncating, cuts it back to [offset] and
     * positions there. Refuses (-1) if the provider truncated the file on open.
     */
    private fun detachAt(uri: Uri, offset: Long): Int {
        val app = ctx ?: return -1
        val pfd = runCatching { app.contentResolver.openFileDescriptor(uri, "rw") }.getOrNull() ?: return -1
        return try {
            val size = Os.fstat(pfd.fileDescriptor).st_size
            if (size < offset) {
                runCatching { pfd.close() }
                return -1
            }
            Os.ftruncate(pfd.fileDescriptor, offset)
            Os.lseek(pfd.fileDescriptor, offset, android.system.OsConstants.SEEK_SET)
            pfd.detachFd()
        } catch (_: Exception) {
            runCatching { pfd.close() }
            -1
        }
    }

    /** Reads a dumps-relative file as a stream, or null when it cannot be opened. */
    fun openRead(relPath: String): java.io.InputStream? {
        val fd = openFd(relPath, "r")
        if (fd < 0) return null
        return ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(fd))
    }

    /** Writes (replaces) a small text file inside the app folder. */
    fun writeText(relPath: String, text: String): Boolean {
        val fd = openFd(relPath, "w")
        if (fd < 0) return false
        return runCatching {
            ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(fd)).use {
                it.write(text.toByteArray(Charsets.UTF_8))
            }
            true
        }.getOrDefault(false)
    }

    fun readText(relPath: String, maxBytes: Int = 1 shl 20): String? = runCatching {
        openRead(relPath)?.use { s ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (buf.size() < maxBytes) {
                val n = s.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
            }
            buf.toString(Charsets.UTF_8.name())
        }
    }.getOrNull()

    /** Deletes a dumps-relative file or folder. */
    fun deleteRel(relPath: String): Boolean = synchronized(lock) {
        val path = sanitizeRelPath(relPath) ?: return false
        if (path.isEmpty()) return false
        val root = treeRoot()
        if (root != null) {
            val f = cachedFind(root, path) ?: return false
            val ok = runCatching { f.delete() }.getOrDefault(false)
            docCache.clear()
            return ok
        }
        return safeLocalFile(path)?.deleteRecursively() == true
    }

    /**
     * Filesystem type of the chosen folder's volume ("vfat", "exfat", ...), read from
     * /proc/mounts. Null for internal storage or when it cannot be determined.
     */
    fun volumeFsType(): String? {
        val tree = synchronized(lock) { treeUri() } ?: return null
        val vol = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            ?.substringBefore(':') ?: return null
        if (vol.isBlank() || vol.equals("primary", ignoreCase = true)) return null
        val lines = runCatching { File("/proc/mounts").readLines() }.getOrNull() ?: return null
        val types = lines.mapNotNull { l ->
            val p = l.split(' ')
            if (p.size < 3) return@mapNotNull null
            val mp = p[1]
            if (mp.endsWith("/$vol") && (mp.startsWith("/mnt/media_rw/") || mp.startsWith("/storage/") ||
                    mp.startsWith("/mnt/pass_through/"))) p[2] else null
        }
        return types.firstOrNull { it == "vfat" || it == "msdos" } ?: types.firstOrNull { it == "exfat" } ?: types.firstOrNull()
    }

    /**
     * Detach a native fd so Python can write through it. Truncate modes
     * (`w` / `rwt`) are tried first; some USB/FAT providers only accept `rw`.
     */
    private fun detach(uri: Uri, writing: Boolean, append: Boolean): Int {
        val app = ctx ?: return -1
        val modes = when {
            !writing -> listOf("r")
            append -> listOf("wa", "rw")
            else -> listOf("w", "wt", "rwt", "rw")
        }
        for (m in modes) {
            val pfd = runCatching { app.contentResolver.openFileDescriptor(uri, m) }.getOrNull() ?: continue
            val fd = try {
                pfd.detachFd()
            } catch (_: Exception) {
                runCatching { pfd.close() }
                continue
            }
            if (writing && !append && m == "rw") {
                runCatching {
                    ParcelFileDescriptor.fromFd(fd).use { dup -> Os.ftruncate(dup.fileDescriptor, 0) }
                }
            }
            return fd
        }
        return -1
    }

    /**
     * Build a File under localDumps() that is guaranteed not to escape the
     * dump root (canonical-path check). Returns null on traversal or I/O error.
     */
    private fun safeLocalFile(relPath: String): File? {
        val root = localDumps()
        val candidate = if (relPath.isEmpty()) root else File(root, relPath)
        return runCatching {
            val rootCanon = root.canonicalFile
            val fileCanon = candidate.canonicalFile
            if (!fileCanon.path.startsWith(rootCanon.path + File.separator) &&
                fileCanon != rootCanon
            ) {
                null
            } else {
                candidate
            }
        }.getOrNull()
    }

    private fun openLocal(relPath: String, writing: Boolean, append: Boolean): Int {
        val file = safeLocalFile(relPath) ?: return -1
        if (writing) {
            file.parentFile?.mkdirs()
        } else if (!file.exists()) {
            return -1
        }
        val bits = if (writing) {
            var m = ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            m = m or if (append) ParcelFileDescriptor.MODE_APPEND else ParcelFileDescriptor.MODE_TRUNCATE
            m
        } else {
            ParcelFileDescriptor.MODE_READ_ONLY
        }
        val pfd = runCatching { ParcelFileDescriptor.open(file, bits) }.getOrNull() ?: return -1
        return try { pfd.detachFd() } catch (_: Exception) {
            runCatching { pfd.close() }
            -1
        }
    }

    private fun collect(dir: DocumentFile, prefix: String): List<Entry> {
        val out = mutableListOf<Entry>()
        for (c in dir.listFiles()) {
            val name = c.name ?: continue
            if (name.startsWith(".")) continue
            val rel = if (prefix.isEmpty()) name else "$prefix/$name"
            if (c.isDirectory) out += collect(c, rel)
            else out += Entry(rel, c.length(), c.lastModified(), c.uri, localFile = null)
        }
        return out.sortedByDescending { it.modified }
    }

    private fun collectLocal(dir: File, prefix: String): List<Entry> {
        val out = mutableListOf<Entry>()
        val children = dir.listFiles() ?: return out
        for (c in children) {
            val rel = if (prefix.isEmpty()) c.name else "$prefix/${c.name}"
            if (c.isDirectory) out += collectLocal(c, rel)
            else {
                val uri = Uri.fromFile(c)
                out += Entry(rel, c.length(), c.lastModified(), uri, localFile = c)
            }
        }
        return out
    }

    private fun volumePath(tree: Uri): File? {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        val vol = id.substringBefore(':', missingDelimiterValue = id)
        val rest = if (id.contains(':')) id.substringAfter(':') else ""
        val base = if (vol.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage/$vol")
        }
        val path = if (rest.isEmpty()) base else File(base, rest)
        return path.takeIf { it.exists() }
    }
}
