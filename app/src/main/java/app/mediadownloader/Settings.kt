package app.mediadownloader

import android.content.Context

/** App settings (Settings screen). Used by the queue, the share pop-up and new downloads. */
object AppPrefs {
    fun p(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun quality(ctx: Context) = p(ctx).getInt("def_quality", 720)               // 2160/1080/720/480/360
    fun audioFormat(ctx: Context) = p(ctx).getBoolean("def_audio", false)       // false = Video MP4, true = Audio MP3
    fun subLang(ctx: Context) = p(ctx).getString("def_sub", "off") ?: "off"     // "off" or yt-dlp code like en.*
    fun wifiOnly(ctx: Context) = p(ctx).getBoolean("wifi_only", false)
    fun maxParallel(ctx: Context) = p(ctx).getInt("max_parallel", 2).coerceIn(1, 3)
    fun inAppPlayer(ctx: Context) = p(ctx).getBoolean("in_app_player", true) && Remote.feature(ctx, "in_app_player")
    fun popup(ctx: Context) = p(ctx).getBoolean("popup_on", true) && Remote.feature(ctx, "share_popup")

    fun speedLimit(ctx: Context) = p(ctx).getInt("speed_kb", 0)                 // KB/s, 0 = no limit
    fun batteryStop(ctx: Context) = p(ctx).getInt("battery_stop", 0)            // percent, 0 = off
    fun removeFinished(ctx: Context) = p(ctx).getBoolean("remove_finished", false)
    fun notifications(ctx: Context) = p(ctx).getBoolean("notify_done", true)

    /** True when the battery is below the chosen level and the phone is not charging. */
    fun lowBattery(ctx: Context): Boolean {
        val limit = batteryStop(ctx)
        if (limit <= 0) return false
        val i = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 100) * 100 / i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val st = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        val charging = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL
        return !charging && level < limit
    }

    /** Index into PRESETS that a new download starts with. */
    fun defaultPreset(ctx: Context): Int =
        if (audioFormat(ctx)) 5 else (QUALITY_PRESETS.firstOrNull { it.first == quality(ctx) }?.second ?: 2)
}

/** App language: chosen on first start and in Settings. */
object Lang {
    val LIST = listOf(
        "English" to "en", "Default (Android system)" to "system", "\u09AC\u09BE\u0982\u09B2\u09BE" to "bn", "\u0939\u093F\u0902\u0926\u0940" to "hi",
        "T\u00FCrk\u00E7e" to "tr", "Afrikaans" to "af", "Chinese (Simplified)" to "zh", "Czech" to "cs",
        "Espa\u00F1ol" to "es", "French" to "fr", "German" to "de", "Greek" to "el", "Italiano" to "it",
        "Indonesia" to "in", "Portugu\u00EAs" to "pt", "\u0420\u0443\u0441\u0441\u043A\u0438\u0439" to "ru", "\u0627\u0644\u0639\u0631\u0628\u064A\u0629" to "ar",
    )

    @Volatile var code: String = "en"

    fun get(ctx: Context) = (AppPrefs.p(ctx).getString("lang", "en") ?: "en").also { code = it }
    fun set(ctx: Context, c: String) { AppPrefs.p(ctx).edit().putString("lang", c).apply(); code = c }

    fun wrap(base: Context): Context {
        val code = get(base)
        if (code == "system") return base
        val loc = java.util.Locale(code)
        java.util.Locale.setDefault(loc)
        val cfg = android.content.res.Configuration(base.resources.configuration)
        cfg.setLocale(loc)
        return base.createConfigurationContext(cfg)
    }
}
