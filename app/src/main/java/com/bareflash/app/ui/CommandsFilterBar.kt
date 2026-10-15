package com.bareflash.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.bareflash.app.ui.theme.LocalNcAccent
import com.bareflash.app.ui.theme.Nc

/**
 * One category chip. [id] is the McCatalog group title (null = all). [jp] is
 * a legacy short tag kept so the catalogs keep compiling; it is not shown.
 */
data class NcCategory(val id: String?, val label: String, val jp: String, val blurb: String)

val NcCategories = listOf(
    NcCategory(null, "All commands", "ALL", "Every DA, dump, and control command in one list."),
    NcCategory("DA Bypass", "DA Bypass", "BYPASS", "SLA / DAA skip, preloader crash, payload load."),
    NcCategory("Partition I/O", "Partition I/O", "I/O", "Read, write, and scatter-flash live partitions."),
    NcCategory("Erase (destructive)", "Erase", "ERASE", "Destructive wipes. Review twice before you run."),
    NcCategory("Device control", "Device", "DEVICE", "Reboot, lock state, and BROM / preloader mode."),
    NcCategory("Advanced", "Advanced", "ADVANCED", "Keys, RPMB, seccfg, and forensic extras.")
)

/** The Android-style search pill: filled, fully rounded, icon on the left. */
@Composable
internal fun CommandsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = LocalNcAccent.current
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .ncBlack(CircleShape)
            .padding(start = 20.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Nc.Muted)
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.merge(TextStyle(color = Nc.Fg)),
            cursorBrush = SolidColor(accent.color),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Search commands", style = MaterialTheme.typography.bodyLarge, color = Nc.Muted, maxLines = 1)
                    inner()
                }
            }
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = Nc.Muted)
            }
        }
    }
}

/**
 * Category filter chips. The strip scrolls the chosen chip into view, and the
 * one-line blurb under it cross-fades when the category changes.
 */
@Composable
internal fun CategoryChips(
    categories: List<NcCategory>,
    state: LazyListState,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    LaunchedEffect(selectedIndex) { state.animateScrollToItem(selectedIndex) }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp)
    ) {
        itemsIndexed(categories) { i, cat ->
            val on = i == selectedIndex
            FilterChip(
                selected = on,
                onClick = { onSelect(i) },
                label = { Text(cat.label, maxLines = 1) },
                leadingIcon = if (on) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else null
            )
        }
    }
    AnimatedContent(
        targetState = categories.getOrNull(selectedIndex)?.blurb.orEmpty(),
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
        label = "categoryBlurb"
    ) { blurb ->
        Text(
            blurb,
            style = MaterialTheme.typography.bodySmall,
            color = Nc.Muted,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}
