package app.mediadownloader

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MediaDownloaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        History.init(this)
        DownloadQueue.watchNetwork(this)
        Telemetry.subscribe(this)
        // Remove temporary download folders left behind by cancelled, failed or killed downloads.
        // (A new process means no download is running yet, so this is safe.)
        Thread {
            runCatching {
                // keep folders of paused downloads so they can resume
                val keep = DownloadQueue.keepDirNames()
                (getExternalFilesDir(null) ?: filesDir).listFiles { f -> f.isDirectory && f.name.startsWith("dl-") && f.name !in keep }
                    ?.forEach { it.deleteRecursively() }
            }
        }.start()
        // New versions arrive as an instant push when Firebase is set up (see FIREBASE_SETUP.md).
        // Without Firebase, fall back to a slow check (every 6 hours) so the app is not woken all the time.
        runCatching {
            val wm = WorkManager.getInstance(this)
            if (Telemetry.on(this)) {
                wm.cancelUniqueWork("update-check")
            } else {
                val req = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                wm.enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.UPDATE, req)
            }
        }
    }
}
