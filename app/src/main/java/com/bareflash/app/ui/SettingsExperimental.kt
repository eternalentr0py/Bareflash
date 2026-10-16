package com.bareflash.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.UsbTuning
import com.bareflash.app.ui.theme.LocalNcAccent
import com.bareflash.app.ui.theme.Nc
import com.bareflash.app.ui.theme.NcDuration
import com.bareflash.app.ui.theme.motionTween

private enum class UsbPreset { Cool, Speed, Custom }

/**
 * Settings → Experimental. Presets flip the whole set in one tap and
 * highlight the matching profile with a short colour / scale animation.
 */
@Composable
internal fun ExperimentalPage() {
    val fused by UsbTuning.fusedRead.collectAsStateWithLifecycle()
    val daWin by UsbTuning.daWindowsEnv.collectAsStateWithLifecycle()
    val daLog by UsbTuning.daLogUsb.collectAsStateWithLifecycle()
    val busy by UsbTuning.busyWait.collectAsStateWithLifecycle()
    val hold by UsbTuning.holdClusters.collectAsStateWithLifecycle()
    val keeper by UsbTuning.boostKeeper.collectAsStateWithLifecycle()
    val hashDumps by UsbTuning.hashDumps.collectAsStateWithLifecycle()
    val accent = LocalNcAccent.current

    val selected = when {
        fused && !busy && !hold && !keeper -> UsbPreset.Cool
        fused && busy && hold && keeper -> UsbPreset.Speed
        else -> UsbPreset.Custom
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionLabel("Presets")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PresetChip(
                label = "Cool",
                subtitle = "Fused on, no clock spin",
                selected = selected == UsbPreset.Cool,
                accentColor = accent.color,
                accentDim = accent.dim,
                modifier = Modifier.weight(1f),
                onClick = { UsbTuning.applyPresetCool() }
            )
            PresetChip(
                label = "Speed",
                subtitle = "Busy-wait + clusters + keeper",
                selected = selected == UsbPreset.Speed,
                accentColor = accent.color,
                accentDim = accent.dim,
                modifier = Modifier.weight(1f),
                onClick = { UsbTuning.applyPresetSpeed() }
            )
        }
        if (selected == UsbPreset.Custom) {
            Text(
                "Custom — toggles below don’t match a preset",
                color = Nc.Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        SectionLabel("USB throughput")
        SwitchRow(
            "Fused MediaTek read",
            "Receives each read frame (64 KiB unless the DA offers more) in one native call. Efficient — no extra heat.",
            fused
        ) { UsbTuning.setFusedRead(it) }

        SectionLabel("MediaTek download agent")
        SwitchRow(
            "Larger read frames (report Windows)",
            "Tells the MediaTek XFlash (V5) DA the host is Windows instead of Linux. On the MT6765 DA this appears to " +
                "raise the read frame from 64 KiB to 2 MiB, which can speed up dumps. Untested on hardware: " +
                "try reading a small partition first and keep it off for writes until a read has worked. " +
                "Applies from the next command. " +
                "The log line \"get_packet_length\" shows the frame size the DA chose.",
            daWin
        ) { UsbTuning.setDaWindowsEnv(it) }
        SwitchRow(
            "DA log over USB (diagnostic)",
            "The MediaTek XFlash DA prints its own speed dump after each transfer (storage read, USB write, " +
                "memcpy), but only to a UART nobody can read. This makes the DA send those lines over USB and " +
                "writes them to Settings → Logging as \"[DA] ...\". Useful for finding what limits dump speed. " +
                "Untested on hardware: it adds a few small log packets to the protocol, so try a small read " +
                "first, and leave it off for writes. Applies from the next command.",
            daLog
        ) { UsbTuning.setDaLogUsb(it) }

        SectionLabel("Dump integrity")
        SwitchRow(
            "Hash dumps while writing",
            "Computes SHA-256 on a side thread as each image is written, and saves a manifest next to it. " +
                "No second pass over a large file. Turn off if a dump is slower than before.",
            hashDumps
        ) { UsbTuning.setHashDumps(it) }

        SectionLabel("Clock / power fallbacks")
        SwitchRow(
            "Busy-wait for USB",
            "Polls instead of sleeping while a transfer is in flight. Costs heat. Off by default.",
            busy
        ) { UsbTuning.setBusyWait(it) }
        SwitchRow(
            "Hold all CPU clusters at full speed",
            "Background spinner per cluster during a command. Main heat source. Off by default.",
            hold
        ) { UsbTuning.setHoldClusters(it) }
        SwitchRow(
            "Continuous boost keeper",
            "Re-asserts transfer priority every 100 ms. Off by default.",
            keeper
        ) { UsbTuning.setBoostKeeper(it) }
    }
}

@Composable
private fun PresetChip(
    label: String,
    subtitle: String,
    selected: Boolean,
    accentColor: Color,
    accentDim: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val borderColor by animateColorAsState(
        targetValue = if (selected) accentColor else Nc.BorderSoft,
        animationSpec = motionTween(NcDuration.Medium),
        label = "presetBorder"
    )
    val bgColor by animateColorAsState(
        targetValue = if (selected) accentDim else Nc.Raised,
        animationSpec = motionTween(NcDuration.Medium),
        label = "presetBg"
    )
    val titleColor by animateColorAsState(
        targetValue = if (selected) accentColor else Nc.Fg,
        animationSpec = motionTween(NcDuration.Medium),
        label = "presetTitle"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (selected) 2.dp else 1.dp,
        animationSpec = motionTween(NcDuration.Short),
        label = "presetBorderW"
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.03f else 1f,
        animationSpec = motionTween(NcDuration.Short),
        label = "presetScale"
    )

    Column(
        modifier
            .scale(scale)
            .height(68.dp)
            .clip(shape)
            .background(bgColor, shape)
            .border(borderWidth, borderColor, shape)
            .ncClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(label, color = titleColor, fontSize = 14.sp)
        Text(subtitle, color = Nc.Muted, fontSize = 11.sp)
    }
}
