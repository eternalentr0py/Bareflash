@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.bareflash.app.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bareflash.app.data.CardStyle
import com.bareflash.app.ui.theme.LocalLook
import com.bareflash.app.ui.theme.LocalNcAccent
import com.bareflash.app.ui.theme.Nc
import com.bareflash.app.ui.theme.PlexMono
import kotlin.math.roundToInt

/**
 * The island's own easing (cubic-bezier 0.22, 1, 0.36, 1). Kept under this
 * name because the status island is timed against it; the rest of the app
 * uses the Material emphasized curves in [com.bareflash.app.ui.theme.NcEasing].
 */
val NcSmoothOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

// ---- text styles ----------------------------------------------------------

/** Small label above a title (sentence case, no tracking). */
val NcKickerStyle = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.5.sp)
val NcTitleStyle = TextStyle(fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 36.sp)
val NcMono11 = TextStyle(fontFamily = PlexMono, fontSize = 11.sp)

/**
 * Page header in the AOSP Settings style: a large title, with an optional
 * small label above it and an optional trailing widget. With "large titles"
 * off in Settings it collapses to a compact title bar.
 */
@Composable
fun NcHeader(kicker: String, title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val large = LocalLook.current.largeTitles
    Row(
        modifier.fillMaxWidth().padding(top = if (large) 8.dp else 0.dp, bottom = if (large) 4.dp else 0.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            if (kicker.isNotBlank()) {
                Text(kicker, style = MaterialTheme.typography.labelLarge, color = LocalNcAccent.current.color)
            }
            Text(
                title,
                style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                color = Nc.Fg
            )
        }
        if (trailing != null) trailing()
    }
}

// ---- surfaces ---------------------------------------------------------------

/** A card surface in the user's chosen style (filled, outlined or elevated). */
@Composable
fun Modifier.ncTile(shape: Shape = MaterialTheme.shapes.large): Modifier = when (LocalLook.current.cardStyle) {
    CardStyle.FILLED -> this.clip(shape).background(Nc.Raised)
    CardStyle.OUTLINED -> this.clip(shape).border(1.dp, Nc.Line, shape)
    CardStyle.ELEVATED -> this.shadow(2.dp, shape).clip(shape).background(Nc.Ink)
}

/** A flat filled surface (fields, search bars, console). */
fun Modifier.ncBlack(shape: Shape = RoundedCornerShape(16.dp), ring: Color = Color.Unspecified): Modifier {
    val m = this.clip(shape).background(Nc.Raised)
    return if (ring == Color.Unspecified) m else m.border(1.dp, ring, shape)
}

/** Raised chip / round icon-button background. */
fun Modifier.ncRaised(shape: Shape = CircleShape): Modifier = this.clip(shape).background(Nc.Raised)

/** Clickable with the standard Material ripple. */
@Composable
fun Modifier.ncClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.clickable(enabled = enabled, role = Role.Button, onClick = onClick)

// ---- section / toggle / chips / slider ----------------------------------------

/** A titled group: small primary-coloured label over a rounded card. */
@Composable
fun NcSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val gap = (4 * LocalLook.current.spacingScale).dp
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = LocalNcAccent.current.color,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
        )
        Column(
            Modifier.fillMaxWidth().ncTile().padding(vertical = 8.dp, horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(gap),
            content = content
        )
    }
}

/** The stock Material 3 switch (with the check mark in the thumb). */
@Composable
fun NcSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        thumbContent = if (checked) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
        } else null
    )
}

@Composable
fun NcToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, hint: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = (10 * LocalLook.current.spacingScale).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = Nc.Fg)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = Nc.Muted)
        }
        NcSwitch(checked, onCheckedChange)
    }
}

data class NcChoice<T>(val id: T, val label: String)

/** Single-choice filter chips that wrap onto as many rows as they need. */
@Composable
fun <T> NcChips(value: T, onChange: (T) -> Unit, options: List<NcChoice<T>>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { o ->
            val on = o.id == value
            FilterChip(
                selected = on,
                onClick = { onChange(o.id) },
                label = { Text(o.label) },
                leadingIcon = if (on) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else null
            )
        }
    }
}

@Composable
fun NcCaption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Nc.Muted, modifier = modifier.padding(horizontal = 12.dp))
}

/** Label + value over a slider (`Auto-collapse  2.4s`). */
@Composable
fun NcSliderRow(
    label: String,
    valueText: String,
    value: Float,
    onChange: (Float) -> Unit,
    min: Float,
    max: Float,
    step: Float
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = Nc.Fg)
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = Nc.Muted)
        }
        NcSlider(value, onChange, min, max, step)
    }
}

/** The stock Material 3 slider, snapped to [step]. */
@Composable
fun NcSlider(value: Float, onChange: (Float) -> Unit, min: Float, max: Float, step: Float, modifier: Modifier = Modifier) {
    val steps = (((max - min) / step).roundToInt() - 1).coerceAtLeast(0)
    Slider(
        value = value.coerceIn(min, max),
        onValueChange = { raw ->
            val snapped = (min + ((raw - min) / step).roundToInt() * step).coerceIn(min, max)
            onChange(snapped)
        },
        valueRange = min..max,
        steps = steps,
        modifier = modifier.fillMaxWidth()
    )
}
