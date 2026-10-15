@file:OptIn(ExperimentalTextApi::class)

package com.bareflash.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.bareflash.app.R

/**
 * The app now uses the system UI font (Roboto / Google Sans on Pixels, the
 * OEM face elsewhere), like stock Android. These two names are kept so
 * existing styles resolve to it without edits.
 */
val Oxanium: FontFamily = FontFamily.Default
val PlexSans: FontFamily = FontFamily.Default

/** The status island keeps its own display face. Variable font. */
val IslandDisplay = FontFamily(
    Font(R.font.oxanium, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.oxanium, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.oxanium, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700)))
)

/** Log and numeric readouts. */
val PlexMono = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium)
)

/** Stock Material 3 type scale in the system font. */
fun ncTypography(): Typography = Typography()
