package com.bareflash.app.data

import org.json.JSONObject

/** Material colour-scheme generator used when dynamic colour is off. */
enum class SchemeVariant(val label: String) {
    TONAL_SPOT("Tonal spot"),
    VIBRANT("Vibrant"),
    EXPRESSIVE("Expressive"),
    NEUTRAL("Neutral"),
    MONOCHROME("Monochrome"),
    FRUIT_SALAD("Fruit salad"),
    RAINBOW("Rainbow"),
    CONTENT("Content"),
    FIDELITY("Fidelity")
}

enum class ContrastLevel(val label: String, val value: Double) {
    STANDARD("Standard", 0.0),
    MEDIUM("Medium", 0.5),
    HIGH("High", 1.0)
}

enum class NavLabelMode(val label: String) { ALWAYS("Always"), SELECTED("Selected only"), NEVER("Never") }

enum class CardStyle(val label: String) { FILLED("Filled"), OUTLINED("Outlined"), ELEVATED("Elevated") }

/**
 * Everything that shapes how the app looks, beyond the dark / light choice in
 * [AppearanceSettings]. The defaults are stock Android: dynamic colour on
 * Android 12+, the AOSP fallback blue everywhere else, system fonts, standard
 * Material shapes and motion.
 */
data class LookSettings(
    /** Follow the wallpaper (Material You) on Android 12+. */
    val dynamicColor: Boolean = true,
    /** Seed used when dynamic colour is off or unavailable. AOSP's default blue. */
    val seedColor: Int = DEFAULT_SEED,
    val variant: SchemeVariant = SchemeVariant.TONAL_SPOT,
    val contrast: ContrastLevel = ContrastLevel.STANDARD,
    /** Per-role colour overrides applied on top of the scheme, by role id. */
    val overrides: Map<String, Int> = emptyMap(),
    /** Multiplies every corner radius. 0 = square, 1 = stock, 1.6 = very round. */
    val cornerScale: Float = 1f,
    val fontScale: Float = 1f,
    /** Multiplies list padding and gaps. 0.8 = compact, 1.2 = roomy. */
    val spacingScale: Float = 1f,
    /** Multiplies every animation's speed. 0 turns animations off. */
    val animationSpeed: Float = 1f,
    val navLabels: NavLabelMode = NavLabelMode.ALWAYS,
    val cardStyle: CardStyle = CardStyle.FILLED,
    /** Large collapsing-style page titles (Settings / Pixel look) vs small ones. */
    val largeTitles: Boolean = true,
    /** 0 = follow the theme. */
    val terminalBg: Int = 0,
    val terminalFg: Int = 0
) {
    companion object {
        const val DEFAULT_SEED: Int = 0xFF1B6EF3.toInt()
    }
}

internal fun LookSettings.toJson(): JSONObject = JSONObject().apply {
    put("dynamicColor", dynamicColor)
    put("seedColor", seedColor)
    put("variant", variant.name)
    put("contrast", contrast.name)
    put("overrides", JSONObject().also { o -> overrides.forEach { (k, v) -> o.put(k, v) } })
    put("cornerScale", cornerScale.toDouble())
    put("fontScale", fontScale.toDouble())
    put("spacingScale", spacingScale.toDouble())
    put("animationSpeed", animationSpeed.toDouble())
    put("navLabels", navLabels.name)
    put("cardStyle", cardStyle.name)
    put("largeTitles", largeTitles)
    put("terminalBg", terminalBg)
    put("terminalFg", terminalFg)
}

private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, fallback: E): E =
    runCatching { enumValueOf<E>(name ?: "") }.getOrDefault(fallback)

internal fun lookFromJson(o: JSONObject?, d: LookSettings = LookSettings()): LookSettings {
    if (o == null) return d
    val ov = o.optJSONObject("overrides")
    val overrides = buildMap {
        ov?.keys()?.forEach { k -> put(k, ov.optInt(k)) }
    }
    return LookSettings(
        dynamicColor = o.optBoolean("dynamicColor", d.dynamicColor),
        seedColor = o.optInt("seedColor", d.seedColor),
        variant = enumOrDefault(o.optString("variant"), d.variant),
        contrast = enumOrDefault(o.optString("contrast"), d.contrast),
        overrides = overrides,
        cornerScale = o.optDouble("cornerScale", d.cornerScale.toDouble()).toFloat().coerceIn(0f, 2f),
        fontScale = o.optDouble("fontScale", d.fontScale.toDouble()).toFloat().coerceIn(0.8f, 1.4f),
        spacingScale = o.optDouble("spacingScale", d.spacingScale.toDouble()).toFloat().coerceIn(0.7f, 1.5f),
        animationSpeed = o.optDouble("animationSpeed", d.animationSpeed.toDouble()).toFloat().coerceIn(0f, 3f),
        navLabels = enumOrDefault(o.optString("navLabels"), d.navLabels),
        cardStyle = enumOrDefault(o.optString("cardStyle"), d.cardStyle),
        largeTitles = o.optBoolean("largeTitles", d.largeTitles),
        terminalBg = o.optInt("terminalBg", d.terminalBg),
        terminalFg = o.optInt("terminalFg", d.terminalFg)
    )
}
