package com.bareflash.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.console.ConsoleViewModel
import com.bareflash.app.engine.QdlNative
import com.bareflash.app.engine.QdlSettings
import com.bareflash.app.ui.theme.LocalNcAccent
import com.bareflash.app.ui.theme.Nc
import com.bareflash.app.ui.theme.Oxanium
import java.io.File

/**
 * QDL mode's own setup tab: engine status, programmer, firmware folder,
 * storage type, target selection and flash options. Every value here is
 * applied to each command by [com.bareflash.app.engine.QdlRules].
 */
@Composable
fun QdlSessionScreen(vm: ConsoleViewModel) {
    val ctx = LocalContext.current
    val store = vm.qdlSession
    val s by store.state.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()

    val pickProgrammer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && store.importProgrammer(uri) == null) vm.log("Could not import the programmer file.\n")
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val path = store.pathFromTree(uri)
            if (path == null) vm.log("That folder cannot be used as a firmware folder.\n")
            else store.update { it.copy(includeDir = path) }
        }
    }
    var confirmFinalize by remember { mutableStateOf(false) }
    val allFiles = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    if (confirmFinalize) {
        FinalizeConfirm(
            onConfirm = { confirmFinalize = false; store.update { it.copy(finalizeProvisioning = true) } },
            onDismiss = { confirmFinalize = false }
        )
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NcHeader(kicker = com.bareflash.app.engine.EngineMode.QDL.short, title = "Session")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            item {
                val programmerOk = s.programmer.isNotBlank() && File(s.programmer).exists()
                val folderOk = s.includeDir.isNotBlank()
                Card("Ready") {
                    Line("Programmer", if (programmerOk) File(s.programmer).name else "not set", good = programmerOk)
                    Line("Firmware folder", if (folderOk) s.includeDir else "not set", good = folderOk)
                    Line("Storage", s.storage, good = true)
                    Line("EDL device", device?.let { "0x%04x:0x%04x".format(it.vid, it.pid) } ?: "not detected", good = device != null)
                }
            }

            item {
                Card("Engine") {
                    Line("qdl engine", if (QdlNative.ready) "ready" else if (QdlNative.loaded) "not built in (stub)" else "library missing",
                        good = QdlNative.ready)
                }
            }

            item {
                Card("Programmer (prog.mbn / prog.elf)") {
                    Text(
                        if (s.programmer.isBlank()) "None selected" else File(s.programmer).name,
                        fontSize = 13.sp, color = if (s.programmer.isBlank()) Nc.Warn else Nc.Fg
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Choose file") { pickProgrammer.launch(arrayOf("*/*")) }
                        if (s.programmer.isNotBlank()) Pill("Clear") { store.update { it.copy(programmer = "") } }
                    }
                    Hint("Needed by xml, read, write, erase, sha256, reset and digests. flash uses the programmer inside the package.")
                }
            }

            item {
                Card("Firmware folder") {
                    Field(s.includeDir, "/storage/emulated/0/Download/firmware") { v -> store.update { it.copy(includeDir = v) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Pick folder") { pickFolder.launch(null) }
                        if (!allFiles) Pill("Allow file access") {
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                    Hint(
                        if (allFiles) "XML files and images in this folder are found automatically."
                        else "qdl reads images straight from disk, so the app needs all-files access to use a folder outside its own storage."
                    )
                }
            }

            item {
                Card("Storage type") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(QdlSettings.storageTypes.size) { i ->
                            val t = QdlSettings.storageTypes[i]
                            Chip(t, s.storage == t) { store.update { it.copy(storage = t) } }
                        }
                    }
                    Hint("auto lets qdl follow the programmer / XML. Set it when a device exposes more than one.")
                }
            }

            item {
                Card("Target") {
                    Field(s.serial, "Serial (optional, e.g. 0AA94EFD)") { v -> store.update { it.copy(serial = v) } }
                    Field(s.slot, "Slot (optional, multi-storage devices)") { v -> store.update { it.copy(slot = v) } }
                }
            }

            item {
                Card("Options") {
                    Toggle("Dry run", "Parse and plan only; nothing is sent to the device", s.dryRun) { v -> store.update { it.copy(dryRun = v) } }
                    Toggle("Debug output", "Print detailed protocol logs", s.debug) { v -> store.update { it.copy(debug = v) } }
                    Toggle("Allow missing files", "Skip images that are not in the folder", s.allowMissing) { v -> store.update { it.copy(allowMissing = v) } }
                    Toggle("Skip reset", "Stay in EDL after flashing completes", s.skipReset) { v -> store.update { it.copy(skipReset = v) } }
                    Toggle("Finalize provisioning", "UFS provisioning; can be irreversible. Off after every restart.", s.finalizeProvisioning) { v ->
                        if (v) confirmFinalize = true else store.update { it.copy(finalizeProvisioning = false) }
                    }
                }
            }

            item {
                Card("Advanced") {
                    Field(s.outChunk, "Out chunk size (bytes, optional)") { v -> store.update { it.copy(outChunk = v) } }
                    Field(s.vipDir, "VIP digest tables folder (optional)") { v -> store.update { it.copy(vipDir = v) } }
                }
            }
        }
    }
}

@Composable
private fun FinalizeConfirm(onConfirm: () -> Unit, onDismiss: () -> Unit) = TypedConfirmDialog(
    title = "Arm finalize provisioning?",
    body = "Finalizing UFS provisioning can permanently change the storage layout and cannot be undone. " +
        "It applies to every command until you turn it off, and it turns itself off when the app restarts.",
    phrase = "finalize",
    confirmLabel = "Arm",
    onConfirm = onConfirm,
    onDismiss = onDismiss
)

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().ncBlack(RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = TextStyle(fontFamily = Oxanium, fontWeight = FontWeight.SemiBold, fontSize = 14.sp), color = Nc.Fg)
        content()
    }
}

@Composable
private fun Line(k: String, v: String, good: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, fontSize = 13.sp, color = Nc.Muted)
        Text(v, fontSize = 13.sp, color = if (good) Nc.Ok else Nc.Warn)
    }
}

@Composable
private fun Hint(t: String) = Text(t, fontSize = 11.sp, color = Nc.Subtle, lineHeight = 15.sp)

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    val accent = LocalNcAccent.current
    Text(
        label,
        style = TextStyle(fontFamily = Oxanium, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
        color = accent.color,
        modifier = Modifier.clip(CircleShape).background(accent.dim).ncClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    )
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val accent = LocalNcAccent.current
    Text(
        label,
        style = TextStyle(fontFamily = Oxanium, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
        color = if (on) accent.onColor else Nc.Muted,
        modifier = Modifier.clip(CircleShape)
            .then(if (on) Modifier.background(accent.brush) else Modifier.background(Nc.Raised))
            .ncClickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp)
    )
}

@Composable
private fun Field(value: String, hint: String, onChange: (String) -> Unit) {
    val accent = LocalNcAccent.current
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = TextStyle(fontSize = 13.sp, color = Nc.Fg),
        cursorBrush = SolidColor(accent.color),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Nc.Raised).padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) Text(hint, fontSize = 13.sp, color = Nc.Subtle, maxLines = 1)
            inner()
        }
    )
}

@Composable
private fun Toggle(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = Nc.Fg)
            Text(sub, fontSize = 11.sp, color = Nc.Subtle)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}
