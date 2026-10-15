package com.bareflash.app.data

import org.json.JSONObject

private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
    runCatching { enumValueOf<E>(name ?: "") }.getOrDefault(fallback)

/** Serialises island preferences (same shape as the preview's localStorage blob). */
internal fun IslandSettings.toJson(): JSONObject = JSONObject().apply {
    put("enabled", enabled)
    put("tapToExpand", tapToExpand)
    put("autoExpandOnConnect", autoExpandOnConnect)
    put("autoExpandOnJob", autoExpandOnJob)
    put("autoCollapseSec", autoCollapseSec.toDouble())
    put("compactLiveActivity", compactLiveActivity)
    put("sizePreset", sizePreset.name)
    put("cornerRadius", cornerRadius)
    put("material", material.name)
    put("motion", motion.name)
    put("speed", speed.toDouble())
    put("overshoot", overshoot)
    put("opacity", opacity.toDouble())
    put("shadow", shadow)
    put("pulseLed", pulseLed)
    put("showLenses", showLenses)
    put("showLed", showLed)
    put("showDevice", showDevice)
    put("showMode", showMode)
    put("showHw", showHw)
    put("showCommand", showCommand)
    put("showProgressBar", showProgressBar)
    put("showPercent", showPercent)
    put("showEta", showEta)
    put("showSpeed", showSpeed)
    put("showTarget", showTarget)
    put("showStage", showStage)
    put("progressStyle", progressStyle.name)
}

/** Reads island preferences; anything missing keeps the value from [cur]. */
internal fun islandFromJson(o: JSONObject?, cur: IslandSettings): IslandSettings {
    if (o == null) return cur
    return IslandSettings(
        enabled = o.optBoolean("enabled", cur.enabled),
        tapToExpand = o.optBoolean("tapToExpand", cur.tapToExpand),
        autoExpandOnConnect = o.optBoolean("autoExpandOnConnect", cur.autoExpandOnConnect),
        autoExpandOnJob = o.optBoolean("autoExpandOnJob", cur.autoExpandOnJob),
        autoCollapseSec = o.optDouble("autoCollapseSec", cur.autoCollapseSec.toDouble()).toFloat().coerceIn(0f, 8f),
        compactLiveActivity = o.optBoolean("compactLiveActivity", cur.compactLiveActivity),
        sizePreset = enumOr(o.optString("sizePreset"), cur.sizePreset),
        cornerRadius = o.optInt("cornerRadius", cur.cornerRadius).coerceIn(24, 52),
        material = enumOr(o.optString("material"), cur.material),
        motion = enumOr(o.optString("motion"), cur.motion),
        speed = o.optDouble("speed", cur.speed.toDouble()).toFloat().coerceIn(0.6f, 1.5f),
        overshoot = o.optBoolean("overshoot", cur.overshoot),
        opacity = o.optDouble("opacity", cur.opacity.toDouble()).toFloat().coerceIn(0.72f, 1f),
        shadow = o.optBoolean("shadow", cur.shadow),
        pulseLed = o.optBoolean("pulseLed", cur.pulseLed),
        showLenses = o.optBoolean("showLenses", cur.showLenses),
        showLed = o.optBoolean("showLed", cur.showLed),
        showDevice = o.optBoolean("showDevice", cur.showDevice),
        showMode = o.optBoolean("showMode", cur.showMode),
        showHw = o.optBoolean("showHw", cur.showHw),
        showCommand = o.optBoolean("showCommand", cur.showCommand),
        showProgressBar = o.optBoolean("showProgressBar", cur.showProgressBar),
        showPercent = o.optBoolean("showPercent", cur.showPercent),
        showEta = o.optBoolean("showEta", cur.showEta),
        showSpeed = o.optBoolean("showSpeed", cur.showSpeed),
        showTarget = o.optBoolean("showTarget", cur.showTarget),
        showStage = o.optBoolean("showStage", cur.showStage),
        progressStyle = enumOr(o.optString("progressStyle"), cur.progressStyle)
    )
}
