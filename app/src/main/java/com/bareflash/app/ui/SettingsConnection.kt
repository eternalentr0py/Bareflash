package com.bareflash.app.ui

import android.content.Context
import android.hardware.usb.UsbManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.console.ConsoleViewModel
import com.bareflash.app.console.LinkMode
import com.bareflash.app.console.LinkState
import com.bareflash.app.ui.theme.LocalNcAccent
import com.bareflash.app.ui.theme.Nc
import com.bareflash.app.ui.theme.Oxanium
import com.bareflash.app.ui.theme.PlexMono

@Composable
internal fun ConnectionPage(vm: ConsoleViewModel) {
    val ctx = LocalContext.current
    val device by vm.device.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val target by vm.target.collectAsStateWithLifecycle()
    val accent = LocalNcAccent.current
    val busy = status.state == LinkState.Detecting ||
        status.state == LinkState.Reconnecting ||
        status.state == LinkState.Running
    val mono = TextStyle(fontFamily = PlexMono, fontSize = 12.sp)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        NcSection("Host") {
            val d = device
            if (d == null) {
                Text(
                    "Nothing on the line. Detect from Console.",
                    fontSize = 14.sp, color = Nc.Muted,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
                )
            } else {
                val usbName = remember(d.vid, d.pid) {
                    runCatching {
                        (ctx.getSystemService(Context.USB_SERVICE) as UsbManager).deviceList.values
                            .firstOrNull { it.vendorId == d.vid && it.productId == d.pid }?.productName
                    }.getOrNull()
                }
                val mode = when (status.mode) {
                    LinkMode.Preloader -> "PRELOADER"
                    LinkMode.BootRom -> "BROM"
                    LinkMode.Da -> "DA"
                    LinkMode.Unknown -> "\u2014"
                }
                Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow("Chipset", target.chipset ?: "\u2014", mono)
                    InfoRow("Model", target.cpu ?: "%04X:%04X".format(d.vid, d.pid), mono)
                    InfoRow("Mode", mode, mono)
                    InfoRow("HW", target.hwCode ?: d.hwCode ?: "\u2014", mono)
                    InfoRow("Preloader", target.blVer ?: "\u2014", mono)
                    InfoRow("BROM", target.bromVer ?: "\u2014", mono)
                    Column(Modifier.fillMaxWidth()) {
                        Text("USB", style = mono, color = Nc.Muted)
                        Text(
                            usbName ?: "%04X:%04X".format(d.vid, d.pid), style = mono, color = Nc.Fg, textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (target.cpu == null) {
                        Text(
                            "Chip details are read during a command handshake. Run any command once and they appear here.",
                            fontSize = 11.sp, color = Nc.Subtle, lineHeight = 15.sp
                        )
                    }
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .alpha(if (busy) 0.4f else 1f)
                .clip(RoundedCornerShape(16.dp))
                .drawBehind { drawRect(accent.brush) }
                .ncClickable(enabled = !busy) { vm.detectDevice() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Detect device",
                style = TextStyle(fontFamily = Oxanium, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                color = accent.onColor
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, style: TextStyle) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = style, color = Nc.Muted, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Text(
            value, style = style, color = Nc.Fg, textAlign = TextAlign.End,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
    }
}
