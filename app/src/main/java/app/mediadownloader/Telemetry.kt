package app.mediadownloader

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.messaging.FirebaseMessaging

/** Usage numbers, crash reports and push topic. Does nothing unless Firebase is set up (app/google-services.json). */
object Telemetry {
    fun on(ctx: Context) = runCatching { FirebaseApp.getApps(ctx).isNotEmpty() }.getOrDefault(false)

    fun event(ctx: Context, name: String, vararg params: Pair<String, String>) {
        if (!on(ctx)) return
        runCatching {
            FirebaseAnalytics.getInstance(ctx).logEvent(name, Bundle().apply { params.forEach { putString(it.first, it.second.take(100)) } })
        }
    }

    fun error(ctx: Context, e: Throwable) {
        if (!on(ctx)) return
        runCatching { FirebaseCrashlytics.getInstance().recordException(e) }
    }

    /** Everyone is in the topic "all": send a push to that topic from the Firebase console. */
    fun subscribe(ctx: Context) {
        if (!on(ctx)) return
        runCatching { FirebaseMessaging.getInstance().subscribeToTopic("all") }
    }
}
