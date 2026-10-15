package com.bareflash.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.bareflash.app.ui.theme.LocalNcScreen
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A full-screen walkthrough of the whole app. Eleven pages, each with something
 * to tap or watch, so it teaches by doing. Nothing here touches a device or runs a
 * command: every demo is a self-contained mock.
 *
 * Opens once after the disclaimer on the first run, and any time from
 * Settings > Tutorial.
 */
private const val PAGE_COUNT = 11

@Composable
fun TutorialOverlay(onClose: () -> Unit) {
    val pager = rememberPagerState { PAGE_COUNT }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val page = pager.currentPage
    val last = page == PAGE_COUNT - 1
    val reduce = rememberTutorialReduceMotion()
    val overall by animateFloatAsState((page + 1f) / PAGE_COUNT, label = "tourProgress")

    // Back steps to the previous page; on the first page it closes the tutorial.
    BackHandler {
        if (page > 0) scope.launch { pager.animateScrollToPage(page - 1) } else onClose()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(LocalNcScreen.current)
            .windowInsetsPadding(WindowInsets.systemBars)
            .a11yModal("Tutorial")
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${page + 1} of $PAGE_COUNT",
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (!last) TextButton(onClick = onClose) { Text("Skip") }
            else Box(Modifier.height(48.dp))
        }
        LinearProgressIndicator(
            progress = { overall },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(3.dp).clip(CircleShape),
            color = cs.primary,
            trackColor = cs.outlineVariant
        )

        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { p ->
            // Fresh stagger counter per page index. Active is a reactive CompositionLocal
            // derived from pager.currentPage so reveals recompose and run on Next/Back.
            val reveal = remember(p) { RevealScope() }
            CompositionLocalProvider(
                LocalReveal provides reveal,
                LocalRevealActive provides (p == pager.currentPage)
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        // The page behind the one you are dragging shrinks and fades slightly.
                        .graphicsLayer {
                            if (!reduce) {
                                val away = abs((pager.currentPage - p) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                                alpha = 1f - 0.5f * away
                                val s = 1f - 0.06f * away
                                scaleX = s
                                scaleY = s
                            }
                        }
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    when (p) {
                        0 -> WelcomePage()
                        1 -> ModesPage()
                        2 -> TabsPage()
                        3 -> ConnectPage()
                        4 -> CommandsPage()
                        5 -> SamsungPage()
                        6 -> DumpsPage()
                        7 -> IslandPage()
                        8 -> LiveNotificationPage()
                        9 -> SettingsMapPage()
                        else -> SafetyPage()
                    }
                }
            }
        }

        HorizontalDivider(color = cs.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { scope.launch { pager.animateScrollToPage(page - 1) } },
                enabled = page > 0
            ) { Text("Back") }
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(PAGE_COUNT) { i -> Dot(on = i == page) }
            }
            Button(onClick = {
                if (last) onClose() else scope.launch { pager.animateScrollToPage(page + 1) }
            }) { Text(if (last) "Finish" else "Next") }
        }
    }
}

@Composable
private fun Dot(on: Boolean) {
    val cs = MaterialTheme.colorScheme
    val w by animateDpAsState(if (on) 18.dp else 6.dp, label = "dotW")
    val c by animateColorAsState(if (on) cs.primary else cs.outlineVariant, label = "dotC")
    Box(
        Modifier
            .padding(horizontal = 3.dp)
            .size(width = w, height = 6.dp)
            .clip(CircleShape)
            .background(c)
    )
}
