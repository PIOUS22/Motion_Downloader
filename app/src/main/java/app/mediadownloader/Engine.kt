package app.mediadownloader

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

interface DownloadListener {
    fun progress(percent: Float, headline: String, detail: String)
    fun status(text: String)
    fun log(line: String) {}
}

data class DownloadResult(
    val savedCount: Int,
    val hadErrors: Boolean,
    val cancelled: Boolean,
    val error: String?,
    val savedUri: Uri? = null,
    val savedMime: String? = null,
    val savedPath: String = "",
    val networkLost: Boolean = false,   // internet dropped: partial files were kept, try again later
)

class SaveOutcome(val count: Int, val firstUri: Uri?, val firstMime: String?, val path: String)

private fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

/** Thin wrapper around yt-dlp (youtubedl-android) that mirrors the desktop app's logic. */
class Engine(private val ctx: Context, private val processId: String = PROCESS_ID) {

    companion object {
        const val PROCESS_ID = "md-download"
        const val SAVE_FOLDER = "MotionDownloader"

        private var inited = false

        /** Safe to call from the app and from the share popup; runs only once per process. */
        @Synchronized
        fun ensureInit(ctx: Context) {
            if (inited) return
            YoutubeDL.getInstance().init(ctx.applicationContext)
            FFmpeg.getInstance().init(ctx.applicationContext)
            inited = true
        }

        /** First run after install: create Downloads/MotionDownloader so the folder exists. */
        @Synchronized
        fun ensureBaseFolder(ctx: Context) {
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (prefs.getBoolean("folder_made", false)) return
            val ok = runCatching {
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, "README.txt")
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SAVE_FOLDER")
                    }
                    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    uri?.let {
                        ctx.contentResolver.openOutputStream(it)?.use { o ->
                            o.write("Files downloaded with Motion Downloader are saved here, in sub-folders by site (YouTube, Facebook...) and type (Video, Audio).\n".toByteArray())
                        }
                    }
                    uri != null
                } else {
                    @Suppress("DEPRECATION")
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SAVE_FOLDER).let { it.exists() || it.mkdirs() }
                }
            }.getOrDefault(false)
            if (ok) prefs.edit().putBoolean("folder_made", true).apply()
        }

        /** Updates yt-dlp at most every 12 hours. */
        @Synchronized
        fun updateIfStale(ctx: Context) {
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (System.currentTimeMillis() - prefs.getLong("last_update", 0L) < 12 * 3600 * 1000L) return
            val r = Engine(ctx).updateYtdlp()
            if (!r.startsWith("update failed")) prefs.edit().putLong("last_update", System.currentTimeMillis()).apply()
        }
    }

    @Volatile
    private var cancelRequested = false

    fun init() = ensureInit(ctx)

    // ---------------------------------------------------------------- analyse

    private fun json(url: String, cookies: String?, vararg flags: String): JSONObject {
        val req = YoutubeDLRequest(url)
        req.addOption("-J")
        req.addOption("--no-warnings")
        req.addOption("--socket-timeout", 25)
        flags.forEach { req.addOption(it) }
        if (cookies != null) req.addOption("--cookies", cookies)
        val out = YoutubeDL.getInstance().execute(req).out
        val i = out.indexOf('{')
        if (i < 0) throw IllegalStateException("No information could be extracted from this link.")
        return JSONObject(out.substring(i))
    }

    private fun entryList(info: JSONObject, yt: Boolean): List<Entry> {
        val arr = info.optJSONArray("entries") ?: return emptyList()
        val out = mutableListOf<Entry>()
        var i = 0
        for (k in 0 until arr.length()) {
            i++
            val e = arr.optJSONObject(k) ?: continue
            var url = e.str("url") ?: e.str("webpage_url") ?: ""
            val id = e.str("id")
            if (!url.startsWith("http") && id != null && yt) url = "https://www.youtube.com/watch?v=$id"
            out += Entry(
                index = i,
                id = id,
                title = e.str("title") ?: "Video $i",
                duration = if (e.isNull("duration")) null else e.optDouble("duration").toLong(),
                url = if (url.startsWith("http")) url else "",
            )
        }
        return out
    }

    private fun thumbOf(o: JSONObject): String? {
        o.str("thumbnail")?.let { if (it.startsWith("http")) return it }
        val t = o.optJSONArray("thumbnails")
        if (t != null) {
            for (i in t.length() - 1 downTo 0) {
                val u = t.optJSONObject(i)?.str("url")
                if (u != null && u.startsWith("http")) return u
            }
        }
        return null
    }

    fun analyze(url: String, cookies: String?): Analysis {
        val yt = Urls.isYoutube(url)
        val (listId, videoId) = if (yt) Urls.youtubeIds(url) else (null to null)

        var kind = "video"
        var playlistUrl: String? = null
        var playlistTitle: String? = null
        var playlistCount: Int? = null
        var entries: List<Entry> = emptyList()
        val info: JSONObject

        if (yt && videoId != null) {
            info = json(url, cookies, "--no-playlist")
            if (listId != null) {                       // video inside a playlist -> offer both
                val purl = "https://www.youtube.com/playlist?list=$listId"
                playlistUrl = purl                       // always offer the playlist; entries load on demand
                runCatching { json(purl, cookies, "--flat-playlist") }.onSuccess { p ->
                    entries = entryList(p, yt)
                    playlistUrl = purl
                    playlistTitle = p.str("title")
                    playlistCount = if (p.has("playlist_count") && !p.isNull("playlist_count"))
                        p.optInt("playlist_count") else entries.size.takeIf { it > 0 }
                }                                        // private / auto-generated mixes: ignore
            }
        } else {
            info = json(url, cookies, "--flat-playlist")
            if (info.optString("_type") == "playlist") {
                kind = "playlist"
                entries = entryList(info, yt)
                playlistUrl = if (yt && listId != null) "https://www.youtube.com/playlist?list=$listId" else url
                playlistTitle = info.str("title")
                playlistCount = if (info.has("playlist_count") && !info.isNull("playlist_count"))
                    info.optInt("playlist_count") else entries.size.takeIf { it > 0 }
            }
        }

        val extractor = info.str("extractor_key") ?: ""
        val (platform, color) = Urls.platform(url, extractor)
        var thumb = thumbOf(info)
        if (thumb == null) thumb = info.optJSONArray("entries")?.objects()?.firstNotNullOfOrNull { thumbOf(it) }

        val fmts = info.optJSONArray("formats")?.objects()?.map { f ->
            val v = f.str("vcodec")?.let { it != "none" } ?: false
            val au = f.str("acodec")?.let { it != "none" } ?: false
            val size = when {
                !f.isNull("filesize") -> f.optLong("filesize")
                !f.isNull("filesize_approx") -> f.optLong("filesize_approx")
                else -> 0L
            }
            FmtInfo(
                height = if (f.isNull("height")) null else f.optInt("height"),
                hasVideo = v, hasAudio = au, ext = f.str("ext") ?: "",
                size = size.takeIf { it > 0 },
            )
        } ?: emptyList()

        return Analysis(
            url = url,
            isYoutube = yt,
            kind = kind,
            playlistUrl = playlistUrl,
            playlistTitle = playlistTitle,
            playlistCount = playlistCount,
            entries = entries,
            title = info.str("title") ?: playlistTitle ?: "Untitled",
            uploader = info.str("uploader") ?: info.str("channel") ?: info.str("uploader_id") ?: "",
            duration = if (info.isNull("duration")) null else info.optDouble("duration").toLong().takeIf { it > 0 },
            thumb = thumb,
            platform = platform,
            platformColor = color,
            formats = fmts,
        )
    }

    /** Loads the video list of a playlist (used when the picker is opened and the list is empty). */
    fun loadEntries(playlistUrl: String, cookies: String?): List<Entry> {
        val info = json(playlistUrl, cookies, "--flat-playlist")
        return entryList(info, Urls.isYoutube(playlistUrl))
    }

    // --------------------------------------------------------------- download

    private fun buildRequest(
        url: String, outDir: File, preset: Preset, isPlaylist: Boolean,
        items: List<Int>?, subs: SubOpts?, cookies: String?,
        extra: List<Pair<String, String?>> = emptyList(),
    ): YoutubeDLRequest {
        val r = YoutubeDLRequest(url)
        val tmpl = if (isPlaylist) "%(playlist_title)s/%(playlist_index)03d - %(title)s.%(ext)s"
        else "%(title)s.%(ext)s"
        r.addOption("-o", File(outDir, tmpl).absolutePath)
        r.addOption("--newline")
        r.addOption("--no-mtime")
        r.addOption("--no-warnings")
        r.addOption("--socket-timeout", 25)
        r.addOption("--retries", 10)
        r.addOption("--fragment-retries", 10)
        r.addOption("--retry-sleep", 3)
        r.addOption("--trim-filenames", 160)
        r.addOption("--embed-metadata")
        if (isPlaylist) {
            r.addOption("--yes-playlist")
            r.addOption("--ignore-errors")
        } else {
            r.addOption("--no-playlist")
        }
        if (!items.isNullOrEmpty()) r.addOption("--playlist-items", items.joinToString(","))
        if (cookies != null) r.addOption("--cookies", cookies)

        if (preset.isAudio) {
            val codec = preset.audio!!
            r.addOption("-f", if (codec == "m4a") "ba[ext=m4a]/ba/b" else "ba/b")
            r.addOption("-x")
            r.addOption("--audio-format", codec)
            r.addOption("--audio-quality", preset.quality ?: "0")
        } else {
            val h = preset.height
            r.addOption("-f", if (h != null) "bv*[height<=?$h]+ba/b[height<=?$h]" else "bv*+ba/b")
            r.addOption("-S", "res,ext:mp4:m4a")
            r.addOption("--merge-output-format", "mp4")
        }

        if (subs != null) {
            r.addOption("--write-subs")
            if (subs.auto) r.addOption("--write-auto-subs")
            r.addOption("--sub-langs", if (subs.lang == "all") "all,-live_chat" else subs.lang)
            if (subs.mode != "embed" || preset.isAudio) r.addOption("--convert-subs", "srt")
            if (!preset.isAudio && (subs.mode == "embed" || subs.mode == "both")) {
                r.addOption("--embed-subs")
                if (subs.mode == "both") r.addOption("-k")
            }
        }
        extra.forEach { (k, v) -> if (v == null) r.addOption(k) else r.addOption(k, v) }
        return r
    }

    fun ytdlpVersion(): String = runCatching { YoutubeDL.getInstance().version(ctx) }.getOrNull() ?: ""

    /** Downloads the newest yt-dlp (nightly). YouTube changes often; old builds get HTTP 403. */
    fun updateYtdlp(): String = try {
        when (YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.NIGHTLY)) {
            YoutubeDL.UpdateStatus.DONE -> "updated"
            YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "already up to date"
            else -> "done"
        }
    } catch (e: Throwable) {
        "update failed: ${e.message?.take(120)}"
    }

    @Volatile
    private var preserveTmp = false

    /** keepFiles = true is used for Pause: partly downloaded files stay so the download can resume. */
    fun cancel(keepFiles: Boolean = false) {
        preserveTmp = keepFiles
        cancelRequested = true
        YoutubeDL.getInstance().destroyProcessById(processId)
    }

    fun download(
        url: String, preset: Preset, isPlaylist: Boolean, items: List<Int>?,
        subs: SubOpts?, cookies: String?, saveTree: Uri?, platform: String, tmpName: String, listener: DownloadListener,
    ): DownloadResult {
        cancelRequested = false
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val tmp = File(base, tmpName).also { it.mkdirs() }

        // YouTube often answers HTTP 403 for some "player clients". Try several, in order.
        val remote = if (ytdlpVersion() >= "2025.11.12") listOf("--remote-components" to "ejs:github") else emptyList()
        val strategies: List<List<Pair<String, String?>>> =
            if (Urls.isYoutube(url)) listOf(
                remote,
                listOf("--extractor-args" to "youtube:player_client=android_vr"),
                remote + listOf("--extractor-args" to "youtube:player_client=web_safari,mweb,web_embedded"),
            ) else listOf(emptyList())

        var error: String? = null
        var attempt = 0
        while (true) {
            val req = buildRequest(url, tmp, preset, isPlaylist, items, subs, cookies, strategies[attempt])

            // progress bookkeeping (same idea as the desktop app: item x stream)
            var ord = 0
            var total: Int? = items?.size
            var streamIdx = 0
            var nStreams = if (preset.isAudio) 1 else 2
            var title = "media"
            var lastDest: String? = null
            val pct = Regex("\\[download\\]\\s+([\\d.]+)%")
            val itemRe = Regex("Downloading item (\\d+) of (\\d+)")
            var lastEmit = 0L
            var downloadedOnce = false
            val logLines = mutableListOf<String>()
            var forbidden = false
            val canRetry = attempt < strategies.size - 1

            error = null
            val lastOutput = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
            listener.log("Attempt ${attempt + 1}/${strategies.size}: yt-dlp " + req.buildCommand().joinToString(" "))
            val watchdog = Thread {
                try {
                    while (true) {
                        Thread.sleep(10_000)
                        val secs = (System.currentTimeMillis() - lastOutput.get()) / 1000
                        if (secs >= 10) listener.log("(no output from yt-dlp for ${secs}s - working or stuck)")
                    }
                } catch (_: InterruptedException) { }
            }.also { it.isDaemon = true; it.start() }
            try {
                YoutubeDL.getInstance().execute(req, processId, true) { _, _, line ->
                    lastOutput.set(System.currentTimeMillis())
                    if (line.isNotBlank()) {
                        synchronized(logLines) { logLines += line; if (logLines.size > 300) logLines.removeAt(0) }
                        listener.log(line)
                    }
                    if (canRetry && !forbidden && line.contains("HTTP Error 403")) {
                        forbidden = true   // stop this attempt now instead of failing all playlist items
                        Thread { YoutubeDL.getInstance().destroyProcessById(processId) }.start()
                    }
                    when {
                        itemRe.containsMatchIn(line) -> {
                            val m = itemRe.find(line)!!
                            ord = m.groupValues[1].toInt()
                            total = m.groupValues[2].toInt()
                            streamIdx = 0
                            lastDest = null
                        }
                        line.contains("format(s):") && line.contains("Downloading") -> {
                            val f = line.substringAfter("format(s):").trim()
                            nStreams = if (preset.isAudio) 1 else f.split("+").size.coerceAtLeast(1)
                        }
                        line.startsWith("[download] Destination:") -> {
                            val f = line.substringAfter("Destination:").trim()
                            if (lastDest != null) streamIdx++
                            lastDest = f
                            title = File(f).nameWithoutExtension.replace(Regex("\\.f\\d+$"), "")
                        }
                        !downloadedOnce -> {}
                        line.startsWith("[Merger]") -> listener.status("Merging video and audio\u2026")
                        line.startsWith("[ExtractAudio]") -> listener.status("Converting audio\u2026")
                        line.startsWith("[EmbedSubtitle]") || line.startsWith("[Metadata]") ||
                            line.startsWith("[SubtitlesConvertor]") -> listener.status("Finalizing\u2026")
                    }
                    val m = pct.find(line)
                    if (m != null) {
                        val frac = (m.groupValues[1].toFloatOrNull() ?: 0f) / 100f
                        if (frac >= 1f) downloadedOnce = true
                        val item = ((minOf(streamIdx, nStreams - 1)) + frac) / nStreams
                        val t = total
                        val overall = if (isPlaylist && t != null && t > 0 && ord > 0)
                            ((ord - 1) + item) / t else item
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 120 || frac >= 1f) {
                            lastEmit = now
                            val head = if (isPlaylist && t != null && ord > 0) "Downloading $ord of $t: $title"
                            else "Downloading: $title"
                            val detail = line.substringAfter("[download]").trim().replace(Regex("\\s+"), " ")
                            listener.progress(minOf(99.9f, overall * 100f), head, detail)
                        }
                    }
                }
            } catch (e: Throwable) {
                if (!cancelRequested) {
                    val errLine = synchronized(logLines) { logLines.lastOrNull { it.contains("ERROR") } }
                    error = if (errLine != null) cleanError(IllegalStateException(errLine)) else cleanError(e)
                }
            }
            watchdog.interrupt()

            if (cancelRequested) break
            if (forbidden && canRetry) {
                attempt++
                listener.log("--- YouTube refused this client (403). Retrying with another method ---")
                listener.status("YouTube blocked the first method - trying another\u2026")
                continue
            }
            break
        }

        if (cancelRequested) {
            if (!preserveTmp) tmp.deleteRecursively()
            return DownloadResult(0, false, true, null)
        }

        // Internet dropped (nothing finished): keep the partial files so the download resumes later.
        if (error != null && isNetworkError(error) && !hasFinishedMedia(tmp)) {
            return DownloadResult(0, false, false, error, networkLost = true)
        }

        listener.status("Saving\u2026")
        val saved = runCatching { saveAll(tmp, saveTree, platform, preset.isAudio) }.getOrElse { e ->
            tmp.deleteRecursively()
            return DownloadResult(0, false, false, "Could not save the file: ${e.message}")
        }
        tmp.deleteRecursively()

        // A playlist with --ignore-errors can fail partially: keep what worked.
        return if (saved.count == 0) DownloadResult(0, false, false, error ?: "The download failed.")
        else DownloadResult(saved.count, error != null, false, error, saved.firstUri, saved.firstMime, saved.path)
    }

    private fun isNetworkError(msg: String): Boolean {
        val m = msg.lowercase()
        return listOf(
            "unable to connect", "timed out", "name resolution", "failed to resolve", "getaddrinfo",
            "network is unreachable", "no route to host", "connection reset", "connection aborted",
            "connection refused", "incompleteread", "remote end closed", "unable to download", "urlopen error",
            "transporterror", "temporary failure", "broken pipe", "eof occurred",
        ).any { it in m } && !m.contains("403") && !m.contains("forbidden") && !m.contains("sign in")
    }

    /** True when at least one complete (non .part) media file already exists. */
    private fun hasFinishedMedia(dir: File): Boolean =
        dir.walkTopDown().any { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") &&
            !it.name.endsWith(".temp") && it.extension.lowercase() in setOf("mp4", "m4a", "mp3", "webm", "mkv", "opus", "ogg", "aac") }

    // ------------------------------------------------- copy into Downloads/

    private fun mimeOf(f: File): String {
        val ext = f.extension.lowercase()
        return when (ext) {
            "srt" -> "application/x-subrip"
            "m4a" -> "audio/mp4"
            "opus" -> "audio/ogg"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    private fun folderName(s: String) =
        s.replace(Regex("\\s*[\\\\/:*?\"<>|]\\s*"), "-").trim().ifEmpty { "Other" }

    /** Saves into a folder the user picked: <picked>/<Platform>/<Video|Audio>/... */
    fun saveFile(tmp: File, saveTree: Uri?): SaveOutcome = saveAll(tmp, saveTree, "Files", false, flat = true)

    private fun saveToTree(tmp: File, files: List<File>, tree: Uri, parts: List<String>): SaveOutcome {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: throw IllegalStateException("Chosen folder is not available")
        if (!root.canWrite()) throw IllegalStateException("No permission for the chosen folder - pick it again")
        var n = 0
        var first: Uri? = null
        var firstMime: String? = null
        for (f in files) {
            var dir = root
            val rel = f.relativeTo(tmp).parent?.split(File.separatorChar)?.filter { it.isNotEmpty() } ?: emptyList()
            for (part in parts + rel) {
                dir = dir.findFile(part)?.takeIf { it.isDirectory } ?: dir.createDirectory(part)
                    ?: throw IllegalStateException("Cannot create folder $part")
            }
            val out = dir.createFile(mimeOf(f), f.name) ?: throw IllegalStateException("Cannot create ${f.name}")
            ctx.contentResolver.openOutputStream(out.uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            if (first == null && !f.name.endsWith(".srt")) { first = out.uri; firstMime = mimeOf(f) }
            n++
        }
        return SaveOutcome(n, first, firstMime, "${root.name ?: "chosen folder"}/${parts.joinToString("/")}")
    }

    /** Default: Downloads/MotionDownloader/<Platform>/<Video|Audio>/ (playlists get a sub-folder). */
    private fun saveAll(tmp: File, saveTree: Uri?, platform: String, isAudio: Boolean, flat: Boolean = false): SaveOutcome {
        val skip = listOf(".part", ".ytdl", ".temp", ".tmp")
        val files = tmp.walkTopDown().filter { it.isFile && skip.none { s -> it.name.endsWith(s) } }.toList()
        val kind = if (isAudio) "Audio" else "Video"
        val plat = folderName(platform)
        val parts = if (flat) listOf(plat) else listOf(plat, kind)
        if (saveTree != null) return saveToTree(tmp, files, saveTree, parts)

        var n = 0
        var first: Uri? = null
        var firstMime: String? = null
        for (f in files) {
            val rel = f.relativeTo(tmp).parent
            val sub = (listOf(SAVE_FOLDER) + parts + listOfNotNull(rel)).joinToString("/")
            val mime = mimeOf(f)
            var uri: Uri? = null
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$sub")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: continue
                resolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sub)
                dir.mkdirs()
                val target = File(dir, f.name)
                f.copyTo(target, overwrite = true)
                uri = Uri.fromFile(target)
            }
            if (first == null && !f.name.endsWith(".srt")) { first = uri; firstMime = mime }
            n++
        }
        return SaveOutcome(n, first, firstMime, "Download/$SAVE_FOLDER/" + parts.joinToString("/"))
    }
}
