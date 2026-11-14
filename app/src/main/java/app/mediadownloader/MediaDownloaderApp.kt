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
        // Remove temporary download folders left behind by cancelled, failed or killed downloads.
        // (A new process means no download is running yet, so this is safe.)
        Thread {
            runCatching {
                (getExternalFilesDir(null) ?: filesDir).listFiles { f -> f.isDirectory && f.name.startsWith("dl-") }
                    ?.forEach { it.deleteRecursively() }
                java.io.File(cacheDir, "updates").deleteRecursively()
            }
        }.start()
        // Look for a new app version twice a day, even when the app is closed.
        runCatching {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
