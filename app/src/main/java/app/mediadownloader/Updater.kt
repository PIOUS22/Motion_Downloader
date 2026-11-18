package app.mediadownloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** readyVersion != null: the APK is downloaded and waiting for "Install now". */
data class UpdateState(val downloading: Boolean = false, val progress: Float = 0f, val readyVersion: String? = null)

data class UpdateInfo(val version: String, val url: String, val notes: String, val sizeBytes: Long)

/** In-app updates: looks at the newest GitHub release of BuildConfig.UPDATE_REPO. */
object Updater {
    private const val CHANNEL = "updates"
    private const val NOTIF_ID = 77

    /** Returns the newer release, or null when up to date. Throws on network errors. */
    fun check(): UpdateInfo? {
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "MotionDownloader-Android")
            if (conn.responseCode == 404) throw IllegalStateException("No public release found. The GitHub repo must be Public (Settings > Danger zone > Change visibility).")
            if (conn.responseCode != 200) throw IllegalStateException("GitHub answered ${conn.responseCode}")
            val j = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = j.optString("tag_name").removePrefix("v")
            val assets = j.optJSONArray("assets") ?: return null
            var apk: JSONObject? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk")) { apk = a; break }
            }
            if (apk == null || !isNewer(tag, BuildConfig.VERSION_NAME)) return null
            return UpdateInfo(tag, apk.optString("browser_download_url"), j.optString("body"), apk.optLong("size"))
        } finally {
            conn.disconnect()
        }
    }

    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun apkFile(ctx: Context, version: String) = File(File(ctx.cacheDir, "updates"), "MotionDownloader-$version.apk")

    fun downloadedFile(ctx: Context, info: UpdateInfo): File? =
        apkFile(ctx, info.version).takeIf { it.exists() && (info.sizeBytes <= 0 || it.length() == info.sizeBytes) }

    fun download(ctx: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File {
        downloadedFile(ctx, info)?.let { return it }
        val dir = File(ctx.cacheDir, "updates").also { it.mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = apkFile(ctx, info.version)
        val tmp = File(dir, "download.part")
        val conn = URL(info.url).openConnection() as HttpURLConnection   // follows GitHub's redirect
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "MotionDownloader-Android")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            var done = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (total > 0 && now - last > 200) { last = now; onProgress(done.toFloat() / total) }
                    }
                }
            }
            if (!tmp.renameTo(out)) throw IllegalStateException("Could not save the update")
            onProgress(1f)
            return out
        } finally {
            conn.disconnect()
        }
    }

    /** Opens Android's installer. Returns false if the user must first allow "install unknown apps". */
    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private fun sp(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Reads what was downloaded earlier; forgets it when it is not newer than the installed version. */
    fun loadState(ctx: Context) {
        val p = sp(ctx)
        val v = p.getString("ready_version", null)
        val path = p.getString("ready_path", null)
        val f = path?.let { File(it) }
        if (v != null && f != null && f.exists() && isNewer(v, BuildConfig.VERSION_NAME)) {
            _state.value = _state.value.copy(readyVersion = v)
        } else {
            p.edit().remove("ready_version").remove("ready_path").apply()
            File(ctx.cacheDir, "updates").deleteRecursively()
            _state.value = _state.value.copy(readyVersion = null)
        }
    }

    /** Opens the installer for the downloaded update. */
    fun installReady(ctx: Context): Boolean {
        val f = sp(ctx).getString("ready_path", null)?.let { File(it) }
        if (f == null || !f.exists()) return false
        return install(ctx, f)
    }

    /** "Download now": downloads the APK in the background (notification with progress), then offers "Install now". */
    fun startBackground(ctx: Context, info: UpdateInfo) {
        val app = ctx.applicationContext
        if (_state.value.downloading) return
        _state.value = _state.value.copy(downloading = true, progress = 0f)
        DownloadService.start(app)
        Thread {
            try {
                var last = 0L
                val file = download(app, info) { pr ->
                    _state.value = _state.value.copy(progress = pr)
                    val now = System.currentTimeMillis()
                    if (now - last > 1000) { last = now; DownloadService.update(app, "Downloading update ${info.version}", (pr * 100).toInt()) }
                }
                sp(app).edit().putString("ready_version", info.version).putString("ready_path", file.absolutePath).apply()
                _state.value = UpdateState(downloading = false, progress = 1f, readyVersion = info.version)
                notifyReady(app, info.version)
            } catch (e: Throwable) {
                _state.value = _state.value.copy(downloading = false)
                postSimple(app, "Update download failed", "Check your internet and try again from the app.")
            } finally {
                DownloadService.stop(app)
            }
        }.start()
    }

    private fun channel(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        }
        return nm
    }

    private fun postSimple(ctx: Context, title: String, text: String) {
        runCatching {
            channel(ctx).notify(
                NOTIF_ID + 2,
                NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(title).setContentText(text).setAutoCancel(true).build()
            )
        }
    }

    /** Second notification: update downloaded, with an "Install now" button. */
    private fun notifyReady(ctx: Context, version: String) {
        runCatching {
            val install = PendingIntent.getActivity(
                ctx, 8, Intent(ctx, MainActivity::class.java).putExtra("install_update", true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            channel(ctx).notify(
                NOTIF_ID + 1,
                NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Update ready")
                    .setContentText("Motion Downloader $version has been downloaded.")
                    .setContentIntent(install)
                    .addAction(0, "Install now", install)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    /** Notification-panel message: "Update available". Shown once per version. */
    fun notify(ctx: Context, info: UpdateInfo) {
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (prefs.getString("notified_version", null) == info.version) return
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = PendingIntent.getActivity(
                ctx, 7, Intent(ctx, MainActivity::class.java).putExtra("open_update", true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            nm.notify(
                NOTIF_ID,
                NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Update available")
                    .setContentText("Motion Downloader ${info.version} is ready. Tap to download and install.")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
            prefs.edit().putString("notified_version", info.version).apply()
        }
    }
}

/** Runs about twice a day in the background, even when the app is closed. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            Updater.check()?.let { Updater.notify(applicationContext, it) }
            Result.success()
        } catch (e: Throwable) {
            Result.retry()
        }
    }
}
