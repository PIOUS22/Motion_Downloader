package app.mediadownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.net.Uri
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service whose only job is to keep the app process alive (and show a progress
 * notification) while a download runs, so Android does not kill it when you leave the app.
 */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val n = build(this, "Preparing download…", -1)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 42
        private const val EXTRA_URL = "url"
        private const val EXTRA_PRESET = "preset"
        private const val EXTRA_PLATFORM = "platform"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_THUMB = "thumb"
        private const val CHANNEL_DONE = "done"
        private val active = AtomicInteger(0)

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
                    )
                }
            }
        }

        private fun build(ctx: Context, text: String, percent: Int): Notification {
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val b = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Motion Downloader")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            if (percent in 0..100) b.setProgress(100, percent, false) else b.setProgress(0, 0, true)
            return b.build()
        }

        fun start(ctx: Context) {
            active.incrementAndGet()
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        }

        fun update(ctx: Context, text: String, percent: Int) {
            runCatching {
                ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(ctx, text, percent))
            }
        }

        /** Stops the service only when no download is using it any more. */
        fun stop(ctx: Context) {
            if (active.decrementAndGet() <= 0) {
                active.set(0)
                ctx.stopService(Intent(ctx, DownloadService::class.java))
            }
        }

        /** "Finished" notification on the notification panel; tapping it opens the downloaded file. */
        fun finished(ctx: Context, text: String, uri: Uri? = null, mime: String? = null) {
            runCatching {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_DONE) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_DONE, "Download finished", NotificationManager.IMPORTANCE_DEFAULT)
                    )
                }
                val inApp = uri != null && AppPrefs.inAppPlayer(ctx) && (mime == null || mime.startsWith("video/") || mime.startsWith("audio/"))
                val intent = if (inApp) {
                    PlayerActivity.intent(ctx, uri.toString(), text, mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                } else if (uri != null) {
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "*/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                } else Intent(ctx, MainActivity::class.java)
                val open = PendingIntent.getActivity(
                    ctx, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val failed = text.startsWith("Download failed")
                val n = NotificationCompat.Builder(ctx, CHANNEL_DONE)
                    .setSmallIcon(if (failed) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done)
                    .setContentTitle(if (failed) "Download failed" else "Download complete")
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                nm.notify(NOTIF_ID + 1 + (System.currentTimeMillis() % 1000).toInt(), n)
            }
        }
    }
}
