package com.bareflash.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.AppGraph
import com.bareflash.app.data.AccessibilitySettings
import com.bareflash.app.ui.theme.Nc

/**
 * TalkBack and low-vision options. Visual changes (contrast, size, motion)
 * apply immediately through the theme; announcements only fire when a
 * screen reader is enabled.
 */
@Composable
internal fun AccessibilityPage() {
    val repo = AppGraph.settings
    val s by repo.state.collectAsStateWithLifecycle()
    val a = s.accessibility
    val ctx = LocalContext.current

    fun patch(f: (AccessibilitySettings) -> AccessibilitySettings) {
        repo.update { it.copy(accessibility = f(it.accessibility)) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        NcSection("Display") {
            NcToggle(
                "High contrast",
                a.highContrast,
                { v -> patch { it.copy(highContrast = v) } },
                hint = "Stronger colour contrast for text and controls"
            )
            NcToggle(
                "Larger controls",
                a.largeControls,
                { v -> patch { it.copy(largeControls = v) } },
                hint = "Bigger type and more space between rows"
            )
            NcToggle(
                "Reduce motion",
                a.reduceMotion,
                { v -> patch { it.copy(reduceMotion = v) } },
                hint = "Turns off in-app animation. System animator scale still applies."
            )
        }

        NcSection("Screen reader") {
            NcToggle(
                "Announce jobs",
                a.announceJobs,
                { v -> patch { it.copy(announceJobs = v) } },
                hint = "Speak when a flash, dump, or erase starts and ends"
            )
            NcToggle(
                "Announce progress",
                a.announceProgress,
                { v -> patch { it.copy(announceProgress = v) } },
                hint = "Speak 25%, 50%, and 75% during measurable transfers"
            )
            NcToggle(
                "Announce connections",
                a.announceConnections,
                { v -> patch { it.copy(announceConnections = v) } },
                hint = "Speak USB connect, disconnect, and connection errors"
            )
            NcToggle(
                "Detailed labels",
                a.detailedLabels,
                { v -> patch { it.copy(detailedLabels = v) } },
                hint = "Longer descriptions on icon buttons where available"
            )
        }

        NcSection("System") {
            Text(
                "TalkBack and other services are controlled by Android. Open system accessibility settings to enable a screen reader, change the display size, or adjust colour correction.",
                color = Nc.Muted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
            TextButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(intent) }
                }
            ) {
                Text("Open system accessibility settings")
            }
        }
    }
}
