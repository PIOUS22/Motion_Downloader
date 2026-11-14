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

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun maxParallel(ctx: Context) = prefs(ctx).getInt("max_parallel", 2).coerceIn(1, 3)
    private fun wifiOnly(ctx: Context) = prefs(ctx).getBoolean("wifi_only", false)

    private fun tmpDir(ctx: Context, id: String) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "dl-$id")

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
                override fun onAvailable(network: Network) = pump(app)
            })
        }
    }

    fun enqueue(ctx: Context, job: DlItem) {
        History.add(job.copy(status = "pending"))
        pump(ctx)
    }

    /** Starts waiting jobs while there is a free slot. */
    fun pump(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(lock) {
            while (true) {
                val items = History.items.value
                if (items.count { it.status == "active" } >= maxParallel(app)) return
                val next = items.lastOrNull { it.status == "pending" } ?: return   // oldest first
                if (wifiOnly(app) && !onWifi(app)) {
                    History.setDetailForPending("Waiting for Wi-Fi")
                    return
                }
                History.setStatus(next.id, "active", "Starting…")
                DownloadService.start(app)
                Thread { run(app, next) }.start()
            }
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
        var res: DownloadResult
        try {
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
        } catch (e: Throwable) {
            res = DownloadResult(0, false, false, cleanError(e))
        }
        engines.remove(id)
        try {
            when (userStop.remove(id)) {
                "pause" -> History.setStatus(id, "paused", "Paused")
                "cancel" -> History.finish(id, res.copy(cancelled = true))
                else -> {
                    History.finish(id, res)
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
                Thread { engines[id]?.cancel(keepFiles = true) }.start()
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
                Thread { engines[id]?.cancel(keepFiles = false) }.start()
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
