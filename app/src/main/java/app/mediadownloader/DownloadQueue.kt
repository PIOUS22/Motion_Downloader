package app.mediadownloader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs download jobs: up to N at a time (Settings), the rest wait as "Pending".
 * Pause keeps the partly downloaded files so Resume continues where it stopped.
 */
object DownloadQueue {
    private val engines = ConcurrentHashMap<String, Engine>()
    private val userStop = ConcurrentHashMap<String, String>()   // job id -> "pause" | "cancel"
    private val lock = Any()
    private val fileStops = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>()
    private val netFail = ConcurrentHashMap<String, Long>()       // job id -> time the internet dropped
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private const val RETRY_MS = 15_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun maxParallel(ctx: Context) = prefs(ctx).getInt("max_parallel", 2).coerceIn(1, 3)
    private fun wifiOnly(ctx: Context) = prefs(ctx).getBoolean("wifi_only", false)

    private fun tmpDir(ctx: Context, id: String) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "dl-$id")

    private fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** Call once: restarts waiting jobs when Wi-Fi comes back. */
    fun watchNetwork(ctx: Context) {
        runCatching {
            val app = ctx.applicationContext
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // give the connection a moment to settle, then resume waiting downloads
                    handler.postDelayed({ pump(app) }, 2500)
                }
            })
        }
    }

    /** start = false adds the job as "Paused" (press Resume in Downloads to begin). */
    fun enqueue(ctx: Context, job: DlItem, start: Boolean = true) {
        if (start) {
            History.add(job.copy(status = "pending"))
            pump(ctx)
        } else History.add(job.copy(status = "paused", detail = "Added \u2014 press Resume to start"))
    }

    /** Starts waiting jobs while there is a free slot. */
    fun pump(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(lock) {
            while (true) {
                val items = History.items.value
                if (items.count { it.status == "active" } >= maxParallel(app)) return
                val now = System.currentTimeMillis()
                val waiting = items.filter { it.status == "pending" }
                if (waiting.isEmpty()) return
                if (!online(app)) {
                    History.setDetailForPending("Waiting for internet\u2026")
                    return
                }
                val next = waiting.lastOrNull { now - (netFail[it.id] ?: 0L) > RETRY_MS }   // oldest first
                if (next == null) {   // all of them dropped very recently: try again shortly
                    handler.postDelayed({ pump(app) }, RETRY_MS)
                    return
                }
                if ((wifiOnly(app) || next.wifiOnly) && !onWifi(app)) {
                    History.setDetailForPending("Waiting for Wi-Fi")
                    return
                }
                History.setStatus(next.id, "active", "Starting…")
                DownloadService.start(app)
                Thread { run(app, next) }.start()
            }
        }
    }

    /** Direct file download (from the browser) with resume support. */
    private fun runFile(ctx: Context, item: DlItem, engine: Engine): DownloadResult {
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        fileStops[item.id] = stop
        val dir = tmpDir(ctx, item.id).also { it.mkdirs() }
        val name = item.fileName.ifBlank { "file" }
        val part = File(dir, "$name.part")
        try {
            var have = if (part.exists()) part.length() else 0L
            val conn = java.net.URL(item.fileUrl).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            item.fileUa?.let { conn.setRequestProperty("User-Agent", it) }
            item.fileCookie?.let { conn.setRequestProperty("Cookie", it) }
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            if (code != 200 && code != 206) return DownloadResult(0, false, false, "The server answered HTTP $code")
            if (code == 200 && have > 0) { have = 0; part.delete() }      // server ignored the resume request
            val total = conn.contentLengthLong.let { if (it > 0) it + have else -1L }
            var done = have
            var lastBytes = done
            var lastT = System.currentTimeMillis()
            var lastN = 0L
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, have > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (!stop.get()) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastT >= 700) {
                            val speed = (done - lastBytes) * 1000.0 / (now - lastT)
                            lastBytes = done
                            lastT = now
                            val pct = if (total > 0) done * 100f / total else 0f
                            val eta = if (total > 0 && speed > 1) ((total - done) / speed).toLong() else -1L
                            val detail = fmtSize(done) + (if (total > 0) " of ${fmtSize(total)}" else "") +
                                " \u00B7 ${fmtSize(speed.toLong())}/s" + (if (eta >= 0) " \u00B7 ${eta}s left" else "")
                            History.progress(item.id, minOf(99.9f, pct), detail)
                            if (now - lastN > 1000) { lastN = now; DownloadService.update(ctx, "Downloading: $name", pct.toInt()) }
                        }
                    }
                }
            }
            if (stop.get()) {
                if (userStop[item.id] != "pause") dir.deleteRecursively()
                return DownloadResult(0, false, true, null)
            }
            if (total > 0 && done < total) return DownloadResult(0, false, false, "Connection lost", networkLost = item.retry)
            val finalFile = File(dir, name)
            finalFile.delete()
            part.renameTo(finalFile)
            val tree = prefs(ctx).getString("save_uri", null)?.let { Uri.parse(it) }
            val saved = engine.saveFile(dir, tree)
            dir.deleteRecursively()
            return if (saved.count == 0) DownloadResult(0, false, false, "Could not save the file")
            else DownloadResult(saved.count, false, false, null, saved.firstUri, saved.firstMime, saved.path)
        } catch (e: java.io.IOException) {
            if (stop.get()) return DownloadResult(0, false, true, null)
            return DownloadResult(0, false, false, "Connection problem: ${e.message}", networkLost = item.retry)
        } catch (e: Throwable) {
            return DownloadResult(0, false, false, cleanError(e))
        } finally {
            fileStops.remove(item.id)
        }
    }

    private fun prettyDetail(line: String): String {
        val m = Regex("of\\s+~?\\s*([\\d.]+\\s*\\w+)\\s+at\\s+([\\d.]+\\s*\\w+/s)\\s+ETA\\s+([\\d:]+)").find(line)
        return if (m != null) "${m.groupValues[1]} · ${m.groupValues[2]} · ${m.groupValues[3]} left" else line
    }

    private fun run(ctx: Context, item: DlItem) {
        val id = item.id
        val engine = Engine(ctx, "md-$id")
        engines[id] = engine
        Telemetry.event(ctx, "download_started", "platform" to item.platform, "kind" to (if (item.fileUrl != null) "file" else if (item.audio) "audio" else "video"))
        var res: DownloadResult
        try {
            if (item.fileUrl != null) {
                res = runFile(ctx, item, engine)
            } else {
            Engine.ensureInit(ctx)
            val cookies = File(ctx.filesDir, "cookies.txt").takeIf { it.exists() }?.absolutePath
            val tree = prefs(ctx).getString("save_uri", null)?.let { Uri.parse(it) }
            val subs = item.subsLang?.let { SubOpts(it, item.subsMode, item.subsAuto) }
            var lastN = 0L
            res = engine.download(
                item.url, PRESETS[item.presetIdx], item.isPlaylist, item.items.ifEmpty { null },
                subs, cookies, tree, item.platform, "dl-$id",
                object : DownloadListener {
                    override fun progress(percent: Float, headline: String, detail: String) {
                        History.progress(id, percent, prettyDetail(detail))
                        val now = System.currentTimeMillis()
                        if (now - lastN > 1000) {
                            lastN = now
                            DownloadService.update(ctx, headline, percent.toInt())
                        }
                    }

                    override fun status(text: String) {
                        History.progress(id, History.get(id)?.progress ?: 0f, text)
                        DownloadService.update(ctx, text, -1)
                    }
                },
            )
            }
        } catch (e: Throwable) {
            res = DownloadResult(0, false, false, cleanError(e))
        }
        engines.remove(id)
        try {
            if (res.networkLost && userStop[id] == null) {
                // Internet dropped: keep the partial file and continue automatically when it is back.
                netFail[id] = System.currentTimeMillis()
                History.setStatus(id, "pending", "Waiting for internet\u2026")
                return
            }
            netFail.remove(id)
            when (userStop.remove(id)) {
                "pause" -> History.setStatus(id, "paused", "Paused")
                "cancel" -> History.finish(id, res.copy(cancelled = true))
                else -> {
                    History.finish(id, res)
                    Telemetry.event(ctx, if (res.savedCount > 0) "download_done" else "download_failed", "platform" to item.platform)
                    if (res.savedCount > 0) {
                        DownloadService.finished(ctx, "${item.title} — saved to ${res.savedPath}", res.savedUri, res.savedMime)
                    } else if (!res.cancelled) {
                        DownloadService.finished(ctx, "Download failed: ${(res.error ?: "unknown error").take(150)}")
                    }
                }
            }
        } finally {
            DownloadService.stop(ctx)
            pump(ctx)
        }
    }

    fun pause(ctx: Context, id: String) {
        val it = History.get(id) ?: return
        when (it.status) {
            "active" -> {
                userStop[id] = "pause"
                Thread { engines[id]?.cancel(keepFiles = true); fileStops[id]?.set(true) }.start()
            }
            "pending" -> History.setStatus(id, "paused", "Paused")
        }
    }

    fun resume(ctx: Context, id: String) {
        if (History.get(id)?.status == "paused") {
            History.setStatus(id, "pending", "")
            pump(ctx)
        }
    }

    fun cancel(ctx: Context, id: String) {
        val it = History.get(id) ?: return
        when (it.status) {
            "active" -> {
                userStop[id] = "cancel"
                Thread { engines[id]?.cancel(keepFiles = false); fileStops[id]?.set(true) }.start()
            }
            "pending", "paused" -> {
                tmpDir(ctx, id).deleteRecursively()
                History.setStatus(id, "cancelled", "")
            }
        }
    }

    fun retry(ctx: Context, id: String) {
        val it = History.get(id) ?: return
        if (it.status == "failed" || it.status == "cancelled") {
            tmpDir(ctx, id).deleteRecursively()
            History.resetForRetry(id)
            pump(ctx)
        }
    }

    /** Folders to keep when cleaning leftovers at start-up. */
    fun keepDirNames(): Set<String> =
        History.items.value.filter { it.status == "paused" || it.status == "pending" }.map { "dl-${it.id}" }.toSet()
}
