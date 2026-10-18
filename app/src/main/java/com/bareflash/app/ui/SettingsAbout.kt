package com.bareflash.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bareflash.app.R
import com.bareflash.app.engine.EngineMode

// ---- about ----------------------------------------------------------------

@Composable
internal fun AboutPage() {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Bareflash", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Version ${ctx.getString(R.string.app_version)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "A flasher that lives on your phone. Connect a device over USB OTG, pick its mode, and read, " +
                "write, back up or restore its firmware without a computer.",
            style = MaterialTheme.typography.bodyLarge
        )

        HorizontalDivider()
        Text("Four modes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        ModeEntry(
            EngineMode.MTK.label,
            "Works with MediaTek chips in BootROM, Preloader and DA mode: partition read and write, erase, " +
                "full dumps and device control, with a live console and a searchable command list."
        )
        ModeEntry(
            EngineMode.QDL.label,
            "Flashes Qualcomm devices in EDL (9008) mode. Set up the programmer, firmware folder and storage " +
                "type once in Session, and every command uses it."
        )
        ModeEntry(
            EngineMode.BROKKR.label,
            "Flashes Samsung devices in Download mode from .tar and .md5 firmware packages, over USB or " +
                "wirelessly for watches, with per-partition progress that keeps running while the screen is off."
        )
        ModeEntry(
            EngineMode.ADB_FASTBOOT.label,
            "Talks to Android devices over USB OTG using its own native ADB and fastboot code, with no bundled " +
                "binaries and no network access. Fastboot variables and reboot work today; ADB, flashing, a " +
                "file manager and screen mirroring are being added step by step."
        )

        HorizontalDivider()
        Text("Before you flash", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Flashing can permanently brick a device and may void its warranty. Back up first, check every " +
                "file, and only flash images made for your exact model. Anything destructive asks you to " +
                "confirm before it runs.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()
        Text("Credits", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Bareflash would not exist without these open-source projects. Huge thanks to everyone who wrote and maintains them. " +
                "For the original versions, including PC builds, see their pages. If any of their authors has a problem with " +
                "this app, contact the developer through the GitHub profile eternalentr0py and it will be fixed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        CreditEntry(
            "mtkclient",
            "MediaTek engine. © B. Kerler, 2018–2025. GNU General Public License v3.0.",
            "https://github.com/bkerler/mtkclient", { uri.openUri(it) }
        )
        CreditEntry(
            "bypass_utility",
            "MediaTek engine. © Dinolek, 2021. MIT License.",
            "https://github.com/MTK-bypass/bypass_utility", { uri.openUri(it) }
        )
        CreditEntry(
            "qdl",
            "Qualcomm engine. © Linaro Ltd. and contributors. BSD 3-Clause License.",
            "https://github.com/linux-msm/qdl", { uri.openUri(it) }
        )
        CreditEntry(
            "Brokkr Flash",
            "Samsung engine (Odin protocol). © 2026 Gabriel2392. GNU General Public License v3.0 or later. " +
                "Includes LZ4 and MD5 components under their own licences.",
            "https://github.com/Gabriel2392/brokkr-flash", { uri.openUri(it) }
        )
        CreditEntry(
            "libusb",
            "USB access for every mode. GNU Lesser General Public License v2.1 or later.",
            "https://libusb.info", { uri.openUri(it) }
        )
        CreditEntry(
            "libxml2 and libzip",
            "Used by the Qualcomm engine. MIT License and BSD 3-Clause License.",
            null, { uri.openUri(it) }
        )
        CreditEntry(
            "Chaquopy",
            "Embedded Python runtime for the MediaTek engine. MIT License.",
            "https://chaquo.com/chaquopy/", { uri.openUri(it) }
        )
        CreditEntry(
            "PyCryptodome and colorama",
            "Python libraries used by the MediaTek engine. BSD 2-Clause (PyCryptodome, some files public domain) " +
                "and BSD 3-Clause (colorama) licences.",
            "https://www.pycryptodome.org", { uri.openUri(it) }
        )
        CreditEntry(
            "AndroidX, Jetpack Compose and Material Components",
            "App framework and interface libraries. Apache License 2.0.",
            "https://developer.android.com/jetpack", { uri.openUri(it) }
        )
        CreditEntry(
            "Oxanium, IBM Plex Sans, IBM Plex Mono",
            "Typefaces. SIL Open Font License 1.1.",
            null, { uri.openUri(it) }
        )
    }
}

@Composable
private fun ModeEntry(name: String, description: String) {
    Column {
        Text(name, style = MaterialTheme.typography.titleSmall)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CreditEntry(name: String, notice: String, url: String?, onOpen: (String) -> Unit) {
    Column {
        Text(name, style = MaterialTheme.typography.titleSmall)
        Text(notice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (url != null) TextButton(onClick = { onOpen(url) }) { Text("View source repository") }
    }
}
