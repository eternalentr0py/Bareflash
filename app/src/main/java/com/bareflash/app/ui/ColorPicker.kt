package com.bareflash.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.bareflash.app.ui.theme.Nc

private fun hsvToColor(h: Float, s: Float, v: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))))

private fun hex6(c: Color): String = "%06X".format(c.toArgb() and 0xFFFFFF)

/**
 * A colour picker dialog: hue / saturation / brightness sliders drawn in the
 * colour they control, a hex field, and a live swatch. [onReset] (when given)
 * puts back the stock colour.
 */
@Composable
fun ColorPickerDialog(
    title: String,
    initial: Color,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit,
    onReset: (() -> Unit)? = null
) {
    val start = remember(initial) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) }
    }
    var h by remember { mutableFloatStateOf(start[0]) }
    var s by remember { mutableFloatStateOf(start[1]) }
    var v by remember { mutableFloatStateOf(start[2]) }
    var hex by remember { mutableStateOf(hex6(initial)) }
    val color = hsvToColor(h, s, v)

    fun syncHex() { hex = hex6(hsvToColor(h, s, v)) }

    NcAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(color)
                        .border(1.dp, Nc.Line, MaterialTheme.shapes.large)
                )
                GradientTrack(
                    label = "Hue",
                    value = h / 360f,
                    colors = List(7) { hsvToColor(it * 60f, 1f, 1f) },
                    onChange = { h = it * 360f; syncHex() }
                )
                GradientTrack(
                    label = "Saturation",
                    value = s,
                    colors = listOf(hsvToColor(h, 0f, v), hsvToColor(h, 1f, v)),
                    onChange = { s = it; syncHex() }
                )
                GradientTrack(
                    label = "Brightness",
                    value = v,
                    colors = listOf(Color.Black, hsvToColor(h, s, 1f)),
                    onChange = { v = it; syncHex() }
                )
                OutlinedTextField(
                    value = hex,
                    onValueChange = { raw ->
                        val clean = raw.removePrefix("#").filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }.take(6).uppercase()
                        hex = clean
                        if (clean.length == 6) {
                            val parsed = Color(0xFF000000.toInt() or clean.toInt(16))
                            val hsv = FloatArray(3)
                            android.graphics.Color.colorToHSV(parsed.toArgb(), hsv)
                            h = hsv[0]; s = hsv[1]; v = hsv[2]
                        }
                    },
                    prefix = { Text("#") },
                    label = { Text("Hex") },
                    singleLine = true,
                    isError = hex.length != 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(enabled = hex.length == 6, onClick = { onConfirm(color) }) { Text("Apply") } },
        dismissButton = {
            Row {
                if (onReset != null) TextButton(onClick = onReset) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

/** A rounded gradient bar with a round thumb; drag or tap to set 0..1. */
@Composable
private fun GradientTrack(label: String, value: Float, colors: List<Color>, onChange: (Float) -> Unit) {
    val current by rememberUpdatedState(onChange)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Nc.Muted)
        Box(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .pointerInput(Unit) {
                    detectTapGestures { current((it.x / size.width).coerceIn(0f, 1f)) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        current((change.position.x / size.width).coerceIn(0f, 1f))
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.height / 2
                drawRoundRect(Brush.horizontalGradient(colors), cornerRadius = CornerRadius(r, r))
                val cx = (value.coerceIn(0f, 1f) * size.width).coerceIn(r, size.width - r)
                drawCircle(Color.White, radius = r - 2.dp.toPx(), center = Offset(cx, size.height / 2))
                drawCircle(Color(0x66000000), radius = r - 2.dp.toPx(), center = Offset(cx, size.height / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/** A round colour swatch (optionally ringed when selected). */
@Composable
fun ColorDot(color: Color, selected: Boolean, size: androidx.compose.ui.unit.Dp = 36.dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .border(if (selected) 3.dp else 1.dp, if (selected) Nc.Fg else Nc.Line, CircleShape)
            .ncClickable(onClick = onClick)
    )
}
