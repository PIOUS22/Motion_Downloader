package app.mediadownloader

import android.net.Uri

/** One video inside a playlist. */
data class Entry(
    val index: Int,
    val id: String?,
    val title: String,
    val duration: Long?,
    val url: String,
)

/** Result of analysing a pasted link. */
data class Analysis(
    val url: String,
    val isYoutube: Boolean,
    val kind: String,                 // "video" | "playlist"
    val playlistUrl: String?,
    val playlistTitle: String?,
    val playlistCount: Int?,
    val entries: List<Entry>,
    val title: String,
    val uploader: String,
    val duration: Long?,
    val thumb: String?,
    val platform: String,
    val platformColor: Long,          // 0xFFRRGGBB
    val formats: List<FmtInfo> = emptyList(),
)

data class FmtInfo(val height: Int?, val hasVideo: Boolean, val hasAudio: Boolean, val ext: String, val size: Long?)

data class Preset(
    val label: String,
    val height: Int? = null,
    val audio: String? = null,
    val quality: String? = null,
) {
    val isAudio get() = audio != null
}

data class SubOpts(val lang: String, val mode: String, val auto: Boolean)

val PRESETS = listOf(
    Preset("Best video (MP4)"),
    Preset("Video up to 1080p (MP4)", height = 1080),
    Preset("Video up to 720p (MP4)", height = 720),
    Preset("Video up to 480p (MP4)", height = 480),
    Preset("Video up to 360p (MP4)", height = 360),
    Preset("Audio only - MP3 (192 kbps)", audio = "mp3", quality = "192K"),
    Preset("Audio only - MP3 (320 kbps)", audio = "mp3", quality = "320K"),
    Preset("Audio only - M4A (AAC)", audio = "m4a", quality = "0"),
    Preset("Audio only - original (no conversion)", audio = "best", quality = "0"),
    Preset("Video up to 2160p (MP4)", height = 2160),
)

/** Video quality -> index in PRESETS (highest first). */
val QUALITY_PRESETS = listOf(2160 to 9, 1080 to 1, 720 to 2, 480 to 3, 360 to 4)

val Preset.shortLabel: String
    get() = when {
        !isAudio -> (height?.let { "${it}p" } ?: "Best") + " \u00B7 MP4"
        audio == "mp3" -> "MP3 \u00B7 ${quality?.removeSuffix("K")} kbps"
        audio == "m4a" -> "M4A \u00B7 AAC"
        else -> "Audio \u00B7 original"
    }

val SUB_LANGS = listOf(
    "English" to "en.*", "Bengali (বাংলা)" to "bn.*", "Hindi" to "hi.*",
    "Spanish" to "es.*", "French" to "fr.*", "German" to "de.*", "Portuguese" to "pt.*",
    "Russian" to "ru.*", "Arabic" to "ar.*", "Japanese" to "ja.*", "Korean" to "ko.*",
    "Chinese" to "zh.*", "All available" to "all",
)

val SUB_MODES = listOf(
    "Separate .srt file" to "file",
    "Embed in video" to "embed",
    "Embed and keep .srt file" to "both",
)

private val PLATFORMS = listOf(
    Triple("youtube", "YouTube", 0xFFE62117),
    Triple("vimeo", "Vimeo", 0xFF1AB7EA),
    Triple("soundcloud", "SoundCloud", 0xFFFF5500),
    Triple("twitter", "X / Twitter", 0xFF3B4452),
    Triple("x.com", "X / Twitter", 0xFF3B4452),
    Triple("facebook", "Facebook", 0xFF1877F2),
    Triple("instagram", "Instagram", 0xFFC13584),
    Triple("tiktok", "TikTok", 0xFF25F4EE),
    Triple("twitch", "Twitch", 0xFF9146FF),
    Triple("dailymotion", "Dailymotion", 0xFF0066DC),
    Triple("reddit", "Reddit", 0xFFFF4500),
    Triple("bilibili", "Bilibili", 0xFF00A1D6),
    Triple("bandcamp", "Bandcamp", 0xFF1DA0C3),
    Triple("mixcloud", "Mixcloud", 0xFF5000FF),
)

object Urls {
    private val YT_HOSTS = listOf("youtube.com", "youtu.be", "youtube-nocookie.com")

    fun normalize(text: String): String {
        val t = text.trim()
        Regex("https?://\\S+").find(t)?.let { return it.value }
        if (t.isNotEmpty() && !t.contains(' ') && t.contains('.')) return "https://$t"
        return t
    }

    fun host(url: String): String {
        val h = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: ""
        return h.removePrefix("www.")
    }

    fun isYoutube(url: String): Boolean {
        val h = host(url)
        return YT_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    /** (playlistId, videoId) found in a YouTube URL; either may be null. */
    fun youtubeIds(url: String): Pair<String?, String?> {
        val uri = Uri.parse(url)
        val list = uri.getQueryParameter("list")
        var vid = uri.getQueryParameter("v")
        if (vid == null && host(url) == "youtu.be") vid = uri.pathSegments.firstOrNull()
        if (vid == null) {
            Regex("^/(?:shorts|embed|live|v)/([\\w-]{6,})").find(uri.path ?: "")?.let { vid = it.groupValues[1] }
        }
        return list to vid
    }

    fun platform(url: String, extractorKey: String): Pair<String, Long> {
        if (isYoutube(url)) return "YouTube" to 0xFFE62117
        val key = extractorKey.lowercase()
        val host = host(url)
        for ((needle, name, color) in PLATFORMS) {
            if (key.contains(needle) || host.contains(needle)) return name to color
        }
        val parts = host.split(".")
        val name = if (parts.size >= 2) parts[parts.size - 2].replaceFirstChar { it.uppercase() } else host.ifEmpty { "Web" }
        return name to 0xFF5B6B7F
    }
}

/** Which download choices to offer: key = single | pick | playlist. */
fun buildOptions(a: Analysis): List<Pair<String, String>> {
    val noun = if (a.isYoutube) "videos" else "items"
    val playlistLabel = "Download entire playlist" + (a.playlistCount?.let { "  ($it $noun)" } ?: "")
    val canPick = a.kind == "playlist" || a.playlistUrl != null   // list is loaded on demand if empty
    if (a.kind == "playlist") {
        val o = mutableListOf<Pair<String, String>>()
        if (canPick) o += "pick" to "Single download (choose from the playlist)"
        o += "playlist" to playlistLabel
        return o
    }
    if (a.isYoutube) {
        if (a.playlistUrl != null) {
            val o = mutableListOf("single" to "Download single video (the one in this link)")
            if (canPick) o += "pick" to "Choose video(s) from the playlist"
            o += "playlist" to playlistLabel
            return o
        }
        return listOf("single" to "Single video")
    }
    return listOf("single" to "Single media download")
}

fun fmtDuration(sec: Long?): String {
    if (sec == null || sec <= 0) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private val ANSI = Regex("\u001B\\[[0-9;]*m")

fun cleanError(e: Throwable): String {
    var msg = ANSI.replace(e.message ?: e.toString(), "").trim()
    // yt-dlp prints several lines; the last "ERROR:" line is the useful one.
    val errLine = msg.lines().lastOrNull { it.trim().startsWith("ERROR:") }
    if (errLine != null) msg = errLine.trim()
    msg = msg.removePrefix("ERROR:").trim()
    if (msg.isEmpty()) msg = "Unknown error"
    if (msg.length > 700) msg = msg.take(700)
    val low = msg.lowercase()
    if ("sign in" in low || "not a bot" in low || "confirm your age" in low || "cookies" in low) {
        msg += "\n\nTip: import a cookies.txt file (exported from a browser where you are signed in) and try again."
    }
    return msg
}

/** (bytes, isExact). Null when the site does not tell us sizes. */
fun estimateSize(a: Analysis, p: Preset): Pair<Long, Boolean>? {
    val dur = a.duration ?: 0L
    val audioOnly = a.formats.filter { it.hasAudio && !it.hasVideo && it.size != null }
    if (p.isAudio) {
        if (p.audio == "mp3") {
            val kbps = p.quality?.removeSuffix("K")?.toIntOrNull() ?: 192
            return if (dur > 0) (dur * kbps * 125L) to false else null
        }
        val best = audioOnly.filter { it.ext == "m4a" }.maxByOrNull { it.size!! } ?: audioOnly.maxByOrNull { it.size!! }
        return best?.size?.let { it to true }
    }
    val h = p.height ?: Int.MAX_VALUE
    val vids = a.formats.filter { it.hasVideo && (it.height ?: 0) <= h && it.size != null }
    if (vids.isEmpty()) return null
    val maxH = vids.maxOf { it.height ?: 0 }
    val top = vids.filter { (it.height ?: 0) == maxH }
    val v = top.firstOrNull { it.ext == "mp4" } ?: top.first()
    val audio = if (v.hasAudio) 0L else ((audioOnly.firstOrNull { it.ext == "m4a" } ?: audioOnly.firstOrNull())?.size ?: 0L)
    return (v.size!! + audio) to true
}

fun fmtSize(bytes: Long): String {
    val mb = bytes / 1048576.0
    return if (mb >= 1024) "%.1f GB".format(mb / 1024) else if (mb >= 1) "%.1f MB".format(mb) else "%.0f KB".format(bytes / 1024.0)
}
