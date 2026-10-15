package com.bareflash.app.engine

import com.bareflash.app.catalog.QdlCatalog
import java.io.File

/**
 * Turns the app's QDL verbs (see [QdlCatalog]) into a real qdl argv, adding
 * the programmer and the Session options. Pure logic, no Android calls.
 */
object QdlRules {

    private val destructive = setOf("flash", "xml", "write", "erase")
    private val knownFirstWords: Set<String> by lazy { QdlCatalog.all.map { it.cmd }.toSet() + "clear" }

    fun isKnown(cmd: String) = cmd.trim().substringBefore(' ').let { it.isNotEmpty() && it in knownFirstWords }
    fun isDestructive(cmd: String) = cmd.trim().substringBefore(' ') in destructive

    sealed interface Plan {
        data class Run(val argv: List<String>, val needsDevice: Boolean) : Plan
        data class Reject(val message: String) : Plan
    }

    /** Short human-readable notes of what the Session tab will add to a command. */
    fun notes(s: QdlSettings): List<String> = buildList {
        if (s.programmer.isNotBlank()) add("Programmer: ${File(s.programmer).name}")
        if (s.storage != "auto") add("Storage: ${s.storage}")
        if (s.serial.isNotBlank()) add("Serial: ${s.serial}")
        if (s.slot.isNotBlank()) add("Slot: ${s.slot}")
        if (s.dryRun) add("Dry run (nothing is sent)")
        if (s.skipReset) add("Skip reset after flashing")
        if (s.allowMissing) add("Allow missing files")
        if (s.finalizeProvisioning) add("Finalize provisioning")
    }

    private fun options(s: QdlSettings): List<String> = buildList {
        if (s.debug) add("--debug")
        if (s.dryRun) add("--dry-run")
        if (s.allowMissing) add("--allow-missing")
        if (s.skipReset) add("--skip-reset")
        if (s.finalizeProvisioning) add("--finalize-provisioning")
        if (s.storage != "auto") add("--storage=${s.storage}")
        if (s.serial.isNotBlank()) add("--serial=${s.serial.trim()}")
        if (s.slot.isNotBlank()) add("--slot=${s.slot.trim()}")
        if (s.outChunk.isNotBlank()) add("--out-chunk-size=${s.outChunk.trim()}")
        if (s.vipDir.isNotBlank()) add("--vip-table-path=${s.vipDir.trim()}")
        if (s.includeDir.isNotBlank()) add("--include=${s.includeDir.trim()}")
    }

    /** Splits on whitespace, honouring double quotes (the Commands tab quotes paths with spaces). */
    fun tokenize(cmd: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        for (c in cmd.trim()) {
            when {
                c == '"' -> quoted = !quoted
                c.isWhitespace() && !quoted -> if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /**
     * @param appBase the folder `dumps/<name>` paths are relative to
     *                (the app's external files dir, same as the MTK engine uses).
     */
    fun plan(
        cmd: String,
        s: QdlSettings,
        appBase: File,
        /** Real file behind a `dumps/<name>` input in the user's chosen folder (no copy), or null. */
        resolveDumps: (String) -> File? = { null }
    ): Plan {
        val t = tokenize(cmd)
        val verb = t.firstOrNull() ?: return Plan.Reject("Empty command.")
        val a = t.drop(1)
        // With a chosen folder readable as a real path, point qdl's --include at it
        // so images next to an XML are found in place.
        val opts = options(s) + (
            if (s.includeDir.isBlank()) resolveDumps("")?.let { listOf("--include=${it.absolutePath}") } ?: emptyList()
            else emptyList()
        )
        val unreadable = mutableListOf<String>()

        fun out(p: String): String {
            val f = if (p.startsWith("/")) File(p) else File(appBase, p)
            f.parentFile?.mkdirs()
            return f.absolutePath
        }
        fun input(p: String): String {
            val path = when {
                p.startsWith("/") -> p
                p.startsWith("dumps/") ->
                    (resolveDumps(p.removePrefix("dumps/")) ?: File(appBase, p)).absolutePath
                else -> (resolveDumps(p) ?: File(s.includeDir.ifBlank { appBase.absolutePath }, p)).absolutePath
            }
            if (!File(path).canRead()) unreadable += p
            return path
        }
        /** A folder is accepted where qdl wants a package file: use the manifest inside it. */
        fun pkg(p: String, vararg names: String): String {
            val f = File(input(p))
            if (!f.isDirectory) return f.absolutePath
            return names.map { File(f, it) }.firstOrNull { it.isFile }?.absolutePath ?: f.absolutePath
        }
        fun need(n: Int) = if (a.size < n) "\"$verb\" needs $n argument(s), got ${a.size}." else null

        val cat = QdlCatalog.all.firstOrNull { it.cmd == verb }
            ?: return Plan.Reject("\"$verb\" is not a QDL command.")
        cat.unsupported?.let { return Plan.Reject("$verb: $it") }

        val prog: String? = s.programmer.takeIf { it.isNotBlank() && File(it).exists() }
        val noProg = Plan.Reject("No programmer selected. Pick a prog.mbn / prog.elf in the Session tab first.")

        val planned = when (verb) {
            "version" -> Plan.Run(listOf("--version"), needsDevice = false)

            "chipinfo" -> Plan.Run(
                listOf("chipinfo") + (if (s.debug) listOf("--debug") else emptyList()) +
                    (if (s.serial.isNotBlank()) listOf("-S", s.serial.trim()) else emptyList()),
                needsDevice = true
            )

            // qdl parses options first and treats the first bare word as the verb,
            // so options must come BEFORE "flash".
            "flash" -> need(1)?.let { Plan.Reject(it) } ?: Plan.Run(
                opts + "flash" +
                    (pkg(a[0], "flashmap.json", "contents.xml") + a.getOrNull(1)?.let { "::$it" }.orEmpty()),
                needsDevice = !s.dryRun
            )

            "xml", "readxml" -> need(1)?.let { Plan.Reject(it) } ?: prog?.let { p ->
                val files = a[0].split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::input)
                Plan.Run(opts + p + files, needsDevice = !s.dryRun)
            } ?: noProg

            "read" -> need(2)?.let { Plan.Reject(it) } ?: prog?.let { p ->
                Plan.Run(opts + p + listOf("read", a[0], out(a[1])), needsDevice = !s.dryRun)
            } ?: noProg

            "write" -> need(2)?.let { Plan.Reject(it) } ?: prog?.let { p ->
                Plan.Run(opts + p + listOf("write", a[0], input(a[1])), needsDevice = !s.dryRun)
            } ?: noProg

            "erase", "sha256" -> need(1)?.let { Plan.Reject(it) } ?: prog?.let { p ->
                Plan.Run(opts + p + listOf(verb, a[0]), needsDevice = !s.dryRun)
            } ?: noProg

            "reset" -> prog?.let { p -> Plan.Run(opts + p + "reset", needsDevice = !s.dryRun) } ?: noProg

            "digests" -> need(2)?.let { Plan.Reject(it) } ?: prog?.let { p ->
                val files = a[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::input)
                Plan.Run(opts + "--create-digests=${out(a[0])}" + p + files, needsDevice = false)
            } ?: noProg

            "ramdump" -> {
                val folder = out(a.getOrElse(0) { "dumps/ramdump" })
                File(folder).mkdirs()
                val filter = a.getOrNull(1)
                Plan.Run(
                    listOf("ramdump") + (if (s.debug) listOf("--debug") else emptyList()) +
                        (if (s.serial.isNotBlank()) listOf("-S", s.serial.trim()) else emptyList()) +
                        listOf("-o", folder) + listOfNotNull(filter),
                    needsDevice = true
                )
            }

            "createzip" -> need(2)?.let { Plan.Reject(it) } ?: Plan.Run(
                listOf("create-zip", out(a[0]), pkg(a[1], "contents.xml") + a.getOrNull(2)?.let { "::$it" }.orEmpty()),
                needsDevice = false
            )

            else -> Plan.Reject("\"$verb\" is not implemented.")
        }
        if (planned is Plan.Run && unreadable.isNotEmpty()) {
            return Plan.Reject(
                "Cannot read ${unreadable.joinToString(", ")} in place. Files are read straight from your chosen folder " +
                    "(nothing is copied), so pick the folder in Settings > General and allow All files access " +
                    "for this app (Android Settings > Apps > Special access)."
            )
        }
        return planned
    }
}
