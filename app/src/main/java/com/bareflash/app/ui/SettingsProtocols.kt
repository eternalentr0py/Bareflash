package com.bareflash.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.AppGraph
import com.bareflash.app.console.CommandRules
import kotlinx.coroutines.launch
import java.io.File

// ---- protocols ------------------------------------------------------------

private fun copyPickedFile(ctx: Context, uri: Uri, targetName: String): Pair<String, String>? = runCatching {
    var display = targetName
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) display = c.getString(idx) ?: targetName
    }
    val dir = File(ctx.filesDir, "loaders").apply { mkdirs() }
    val dest = File(dir, targetName)
    ctx.contentResolver.openInputStream(uri)?.use { input ->
        dest.outputStream().use { input.copyTo(it) }
    } ?: return null
    dest.absolutePath to display
}.getOrNull()

@Composable
internal fun ProtocolsPage() {
    val ctx = LocalContext.current
    val repo = AppGraph.settings
    val saved by repo.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(saved.protocols) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val vidOk = draft.vid.isBlank() || CommandRules.isHexValue(draft.vid)
    val pidOk = draft.pid.isBlank() || CommandRules.isHexValue(draft.pid)
    val sectorOk = CommandRules.isHexValue(draft.sectorSize)
    val valid = vidOk && pidOk && sectorOk
    val dirty = draft != saved.protocols

    val preloaderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val r = copyPickedFile(ctx, uri, "preloader.bin")
            if (r != null) draft = draft.copy(preloaderPath = r.first, preloaderName = r.second)
            else scope.launch { snackbar.showSnackbar("The selected file could not be read.") }
        }
    }
    val daPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val r = copyPickedFile(ctx, uri, "da.bin")
            if (r != null) draft = draft.copy(daPath = r.first, daName = r.second)
            else scope.launch { snackbar.showSnackbar("The selected file could not be read.") }
        }
    }
    val authPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val r = copyPickedFile(ctx, uri, "auth_sv5.auth")
            if (r != null) draft = draft.copy(authPath = r.first, authName = r.second)
            else scope.launch { snackbar.showSnackbar("The selected file could not be read.") }
        }
    }
    val certPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val r = copyPickedFile(ctx, uri, "cert.bin")
            if (r != null) draft = draft.copy(certPath = r.first, certName = r.second)
            else scope.launch { snackbar.showSnackbar("The selected file could not be read.") }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SwitchRow("Debug logging", "Prints verbose diagnostic output to the terminal.", draft.debugMode) { draft = draft.copy(debugMode = it) }
            SwitchRow("Skip watchdog initialization", "Skips watchdog timer setup during the BootROM and Preloader handshake.", draft.skipWatchdog) { draft = draft.copy(skipWatchdog = it) }
            SwitchRow("Skip BootROM crash step", "For devices already in Preloader mode: skips the BootROM crash before the payload runs.", draft.alreadyInPreloader) { draft = draft.copy(alreadyInPreloader = it) }
            SwitchRow("Enforce Preloader crash", "Always crash Preloader into BootROM (--crash), even when a softer path exists.", draft.enforceCrash) { draft = draft.copy(enforceCrash = it) }
            SwitchRow("Read SoC ID", "Reads the SoC identifier as part of the connection sequence.", draft.readSocId) { draft = draft.copy(readSocId = it) }
            SwitchRow("Use stock DA", "Prefer the stock Download Agent instead of a custom or kamakiri path (--stock).", draft.stockDa) { draft = draft.copy(stockDa = it) }
            SwitchRow("IoT mode", "Special handshake for MT6261 / MT2301 IoT chips (--iot).", draft.iotMode) { draft = draft.copy(iotMode = it) }
            SwitchRow("Use selected Preloader and DA files", "Uses the files chosen below instead of the automatically detected ones.", draft.useCustomFiles) { draft = draft.copy(useCustomFiles = it) }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            PayloadTypeField(draft.payloadType) { draft = draft.copy(payloadType = it) }

            FieldRow("Vendor ID override (hex)", "Overrides the USB vendor ID used for device detection.", draft.vid, !vidOk) { draft = draft.copy(vid = it) }
            FieldRow("Product ID override (hex)", "Leave empty to detect the product ID automatically.", draft.pid, !pidOk) { draft = draft.copy(pid = it) }
            FieldRow("Sector size (hex)", "Storage sector size used for dump and flash addressing. Default is 0x200.", draft.sectorSize, !sectorOk) { draft = draft.copy(sectorSize = it) }
            FieldRow("UART log level", "Optional --uartloglevel (0=Trace, 2=Normal). Leave empty for default.", draft.uartLogLevel, false) { draft = draft.copy(uartLogLevel = it) }

            FileRow("Preloader file", draft.preloaderName, onChoose = { preloaderPicker.launch(arrayOf("*/*")) },
                onClear = { draft = draft.copy(preloaderPath = "", preloaderName = "") })
            FileRow("Download Agent file", draft.daName, onChoose = { daPicker.launch(arrayOf("*/*")) },
                onClear = { draft = draft.copy(daPath = "", daName = "") })
            FileRow("Auth file (auth_sv5.auth)", draft.authName, onChoose = { authPicker.launch(arrayOf("*/*")) },
                onClear = { draft = draft.copy(authPath = "", authName = "") })
            FileRow("Cert file", draft.certName, onChoose = { certPicker.launch(arrayOf("*/*")) },
                onClear = { draft = draft.copy(certPath = "", certName = "") })

            Button(
                onClick = {
                    repo.update {
                        it.copy(protocols = draft.copy(vid = draft.vid.trim(), pid = draft.pid.trim(), sectorSize = draft.sectorSize.trim()))
                    }
                    scope.launch { snackbar.showSnackbar("MediaTek settings saved.") }
                },
                enabled = dirty && valid,
                modifier = Modifier.padding(16.dp)
            ) { Text("Save protocol settings") }
            Spacer(Modifier.height(24.dp))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }
}

@Composable
private fun PayloadTypeField(value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Payload type", style = MaterialTheme.typography.bodyLarge)
        Text("Selects the exploit method used to reach BootROM or Preloader.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = { open = true }) { Text(value.ifBlank { "Automatic" }) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Automatic") }, onClick = { onChange(""); open = false })
                CommandRules.payloadTypes.forEach { t ->
                    DropdownMenuItem(text = { Text(t) }, onClick = { onChange(t); open = false })
                }
            }
        }
    }
}

@Composable
private fun FieldRow(label: String, description: String, value: String, error: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = { Text(if (error) "Enter a hexadecimal value, for example 0x0E8D." else description) },
        isError = error,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun FileRow(label: String, name: String, onChoose: () -> Unit, onClear: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(name.ifBlank { "Not set" }) },
        trailingContent = {
            Row {
                if (name.isNotBlank()) TextButton(onClick = onClear) { Text("Clear") }
                TextButton(onClick = onChoose) { Text("Choose") }
            }
        }
    )
}
