package com.bareflash.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bareflash.app.ui.theme.LocalNcAccent
import com.bareflash.app.ui.theme.Nc
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.AppGraph
import com.bareflash.app.console.ConsoleViewModel
import com.bareflash.app.data.Extras
import com.bareflash.app.dump.DumpBridge
import com.bareflash.app.live.LiveHub
import com.bareflash.app.live.LiveNotificationFactory
import com.bareflash.app.live.PromotionCheck

// ---- general ----------------------------------------------------------

@Composable
internal fun GeneralPage(vm: ConsoleViewModel) {
    val ctx = LocalContext.current
    val repo = AppGraph.settings
    val s by repo.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val ok = runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(repo.exportJson().toByteArray()) } != null
            }.getOrDefault(false)
            scope.launch { snackbar.showSnackbar(if (ok) "Settings exported." else "Settings could not be exported.") }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            }.getOrNull()
            val error = if (text == null) "The file could not be read." else repo.importJson(text)
            scope.launch { snackbar.showSnackbar(error ?: "Settings imported.") }
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(
        object : ActivityResultContracts.OpenDocumentTree() {
            override fun createIntent(context: android.content.Context, input: Uri?): Intent {
                return super.createIntent(context, input).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
            }
        }
    ) { uri ->
        if (uri != null) {
            val name = DumpBridge.takeTree(uri)
            vm.notifyDumpsChanged()
            scope.launch {
                snackbar.showSnackbar(
                    if (name != null) "Dumps will be written directly to $name."
                    else "That folder could not be used. Pick another."
                )
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            val liveOn by Extras.liveNotifications.collectAsStateWithLifecycle()
            val autoOn by Extras.autoResume.collectAsStateWithLifecycle()
            val roOn by Extras.readOnlyLock.collectAsStateWithLifecycle()
            val chipOn by Extras.chipProfiles.collectAsStateWithLifecycle()

            SectionLabel("Live notifications")
            SwitchRow(
                "Show live progress outside the app",
                "While a read, write or flash runs and you are in another app or the screen is off, a live notification shows progress, speed and time left. It disappears when you open Bareflash.",
                liveOn
            ) { v -> Extras.setLiveNotifications(v) }
            BackupRow(
                Icons.Filled.Notifications, "Notification settings",
                "Allow notifications and choose whether they show on the lock screen."
            ) { openNotificationSettings(ctx, promoted = false) }
            // Re-read the system's answer when coming back from the settings page.
            var resumeTick by remember { mutableIntStateOf(0) }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
            val promotion by LiveHub.promotion.collectAsStateWithLifecycle()
            val sdk = android.os.Build.VERSION.SDK_INT
            val samsung = LiveNotificationFactory.isSamsung()
            val allowed = remember(resumeTick) { LiveNotificationFactory.canPromote(ctx) }
            BackupRow(
                Icons.Filled.Notifications, "Live updates (status bar chip, lock screen)",
                when {
                    sdk < 36 ->
                        "This phone runs Android ${android.os.Build.VERSION.RELEASE}. Live Updates need Android 16, so a normal progress notification is used." +
                            if (samsung) " One UI 7 only shows Samsung's own Live notifications for apps Samsung has approved." else ""
                    !allowed -> "Off for Bareflash. Tap to allow Live updates."
                    promotion == PromotionCheck.Promoted -> "Working: the system promoted the last live notification."
                    promotion == PromotionCheck.NotPromoted ->
                        "Allowed, but the system showed the last one as a normal notification." +
                            if (samsung) " Samsung needs the extra switch below." else ""
                    else -> "Allowed. Run the preview below to check that your phone promotes it."
                }
            ) { openNotificationSettings(ctx, promoted = sdk >= 36) }
            if (samsung && sdk >= 36) {
                BackupRow(
                    Icons.Filled.Notifications, "Samsung: Live notifications for all apps",
                    "Samsung keeps Live Updates from other apps behind a switch in Developer options called Live notifications for all apps. " +
                        "Some One UI versions also list apps under Settings > Notifications > Live notifications. Tap to open Developer options."
                ) {
                    runCatching {
                        ctx.startActivity(
                            Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
            BackupRow(
                Icons.Filled.Notifications, "Preview the live notification",
                "Starts a 40 second pretend read. Press Home or turn the screen off to see it; open the app and it goes away."
            ) { LiveHub.preview() }

            SectionLabel("Safety and automation")
            SwitchRow(
                "Read-only lock",
                "Refuses every write, erase and flash, in MediaTek, Qualcomm and Samsung mode. Reads, hashing and verification still work.",
                roOn
            ) { v -> Extras.setReadOnlyLock(v) }
            SwitchRow(
                "Resume cut-short dumps automatically",
                "If the cable drops during a read, continue it by itself when the device comes back, up to three times.",
                autoOn
            ) { v -> Extras.setAutoResume(v) }
            SwitchRow(
                "Remember settings per chip",
                "When a chip with saved settings is detected, apply them again so you do not have to open MediaTek settings.",
                chipOn
            ) { v -> Extras.setChipProfiles(v) }
            BackupRow(
                Icons.Filled.Download, "Save current MediaTek settings for this chip",
                "Needs a detected device. Overwrites the profile saved for this chip."
            ) {
                val ok = vm.saveChipProfile()
                scope.launch { snackbar.showSnackbar(if (ok) "Saved for this chip." else "Detect a device first.") }
            }
            if (vm.hasChipProfile()) {
                BackupRow(
                    Icons.Filled.Upload, "Forget the saved settings for this chip",
                    "Removes the profile. Settings stay as they are now."
                ) {
                    vm.forgetChipProfile()
                    scope.launch { snackbar.showSnackbar("Profile removed.") }
                }
            }

            SectionLabel("When a command finishes")
            SwitchRow(
                "Vibrate when a command finishes",
                "A short pattern on success, a longer one on failure. Useful when the screen is off during a dump.",
                s.general.vibrateOnCommandEnd
            ) { v -> repo.update { it.copy(general = it.general.copy(vibrateOnCommandEnd = v)) } }

            SwitchRow(
                "Play a sound when a command finishes",
                "Uses the notification volume. Stays silent if the device is in silent mode. Off by default.",
                s.general.soundOnCommandEnd
            ) { v -> repo.update { it.copy(general = it.general.copy(soundOnCommandEnd = v)) } }

            SectionLabel("Dump folder")
            val dest = if (DumpBridge.isEnabled() && s.general.dumpFolderName.isNotBlank()) {
                s.general.dumpFolderName
            } else {
                "App folder"
            }
            BackupRow(
                Icons.Filled.FolderOpen, "Save dumps to $dest",
                "Pick an SD card, USB drive, or Downloads folder. Partitions are written there directly as they are read."
            ) { folderLauncher.launch(DumpBridge.currentUri()) }
            if (DumpBridge.isEnabled()) {
                BackupRow(
                    Icons.Filled.Download, "Use the app folder again",
                    "Clears the chosen folder. New dumps go back to the app's private storage."
                ) {
                    DumpBridge.clearTree()
                    vm.notifyDumpsChanged()
                    scope.launch { snackbar.showSnackbar("Dumps will be saved in the app folder.") }
                }
            }

            SectionLabel("Backup")
            BackupRow(
                Icons.Filled.Upload, "Export settings",
                "Saves your settings to a file. Preloader and DA files are not included: pick them again after a reinstall."
            ) { exportLauncher.launch("bareflash-settings.json") }
            BackupRow(
                Icons.Filled.Download, "Import settings",
                "Restores settings from a previously exported file."
            ) { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }
}

@Composable
private fun BackupRow(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    val accent = LocalNcAccent.current
    Row(
        Modifier.fillMaxWidth().ncClickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = Nc.Fg)
            Text(description, fontSize = 11.sp, lineHeight = 15.sp, color = Nc.Muted)
        }
        Icon(icon, contentDescription = null, tint = accent.color, modifier = Modifier.size(20.dp))
    }
}

/** Opens the system page for this app's notifications; with [promoted] the Live updates page on Android 16. */
private fun openNotificationSettings(ctx: android.content.Context, promoted: Boolean) {
    val plain = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (promoted) {
        // Two names exist for the same page; Samsung ships neither on some builds, so fall through.
        for (action in listOf(
            "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS",
            "android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS",
        )) {
            val live = Intent(action)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(live) }.isSuccess) return
        }
    }
    runCatching { ctx.startActivity(plain) }
}
