package com.bareflash.app.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Everything the Session tab edits; applied to every QDL command. */
data class QdlSettings(
    val programmer: String = "",          // absolute path to prog.mbn / prog.elf
    val includeDir: String = "",          // --include: folder searched for XML / images
    val storage: String = "auto",         // auto | emmc | ufs | nand | nvme | spinor
    val serial: String = "",              // --serial
    val slot: String = "",                // --slot
    val outChunk: String = "",            // --out-chunk-size
    val vipDir: String = "",              // --vip-table-path
    val debug: Boolean = false,
    val dryRun: Boolean = false,
    val allowMissing: Boolean = false,
    val skipReset: Boolean = false,
    val finalizeProvisioning: Boolean = false,
) {
    companion object { val storageTypes = listOf("auto", "emmc", "ufs", "nand", "nvme", "spinor") }
}

/** SharedPreferences-backed [QdlSettings] with a StateFlow for Compose. */
class QdlSessionStore(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("qdl_session", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<QdlSettings> = _state

    private fun load() = QdlSettings(
        programmer = prefs.getString("programmer", "") ?: "",
        includeDir = prefs.getString("includeDir", "") ?: "",
        storage = prefs.getString("storage", "auto") ?: "auto",
        serial = prefs.getString("serial", "") ?: "",
        slot = prefs.getString("slot", "") ?: "",
        outChunk = prefs.getString("outChunk", "") ?: "",
        vipDir = prefs.getString("vipDir", "") ?: "",
        debug = prefs.getBoolean("debug", false),
        dryRun = prefs.getBoolean("dryRun", false),
        allowMissing = prefs.getBoolean("allowMissing", false),
        skipReset = prefs.getBoolean("skipReset", false),
        // Never restored: UFS provisioning can be irreversible, so it is off after
        // every restart and has to be armed again (with a typed confirmation).
        finalizeProvisioning = false,
    )

    fun update(block: (QdlSettings) -> QdlSettings) {
        val s = block(_state.value)
        _state.value = s
        prefs.edit()
            .putString("programmer", s.programmer).putString("includeDir", s.includeDir)
            .putString("storage", s.storage).putString("serial", s.serial)
            .putString("slot", s.slot).putString("outChunk", s.outChunk)
            .putString("vipDir", s.vipDir)
            .putBoolean("debug", s.debug).putBoolean("dryRun", s.dryRun)
            .putBoolean("allowMissing", s.allowMissing).putBoolean("skipReset", s.skipReset)
            .remove("finalizeProvisioning")
            .apply()
    }

    /** Copies a picked programmer into app storage (qdl needs a real path) and selects it. */
    fun importProgrammer(uri: Uri): String? = runCatching {
        val dir = File(ctx.filesDir, "qdl").apply { mkdirs() }
        var name = "programmer.bin"
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        val out = File(dir, name.replace('/', '_'))
        ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        update { it.copy(programmer = out.absolutePath) }
        out.absolutePath
    }.getOrNull()

    /** Primary-storage tree URIs map to a real path; anything else cannot be used by native code. */
    fun pathFromTree(uri: Uri): String? {
        val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val (vol, rel) = docId.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return if (vol.equals("primary", true)) "/storage/emulated/0/$rel".trimEnd('/') else "/storage/$vol/$rel".trimEnd('/')
    }
}
