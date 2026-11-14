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
    fun popup(ctx: Context) = p(ctx).getBoolean("popup_on", true)

    /** Index into PRESETS that a new download starts with. */
    fun defaultPreset(ctx: Context): Int =
        if (audioFormat(ctx)) 5 else (QUALITY_PRESETS.firstOrNull { it.first == quality(ctx) }?.second ?: 2)
}
