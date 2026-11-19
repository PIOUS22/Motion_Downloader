package app.mediadownloader

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Announcement(val id: String, val title: String, val message: String, val url: String)

/** Owner settings read from remote.json in the GitHub repo (see OWNER_GUIDE.md). */
data class RemoteState(
    val minVersion: String = "",                 // versions older than this must update
    val maintenance: String? = null,             // message when the app is switched off
    val announcement: Announcement? = null,
    val features: Map<String, Boolean> = emptyMap(),
)

object Remote {
    private const val URL_RAW = "https://raw.githubusercontent.com/PIOUS22/Motion_Downloader/main/remote.json"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE)

    /** Last downloaded settings (so they also apply offline). */
    fun cached(ctx: Context): RemoteState = parse(prefs(ctx).getString("json", null))

    fun feature(ctx: Context, key: String): Boolean = cached(ctx).features[key] ?: true

    /** Downloads the file; returns null when the internet or GitHub is not reachable. */
    fun fetch(ctx: Context): RemoteState? = try {
        val conn = URL("$URL_RAW?t=${System.currentTimeMillis() / 60000}").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "MotionDownloader-Android")
            if (conn.responseCode != 200) null
            else {
                val text = conn.inputStream.bufferedReader().readText()
                JSONObject(text)                       // must be valid JSON before we keep it
                prefs(ctx).edit().putString("json", text).apply()
                parse(text)
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Throwable) {
        null
    }

    fun parse(text: String?): RemoteState {
        if (text.isNullOrBlank()) return RemoteState()
        return try {
            val j = JSONObject(text)
            val m = j.optJSONObject("maintenance")
            val maint = if (m != null && m.optBoolean("enabled")) m.optString("message").ifBlank { "The app is under maintenance. Please try again later." } else null
            val a = j.optJSONObject("announcement")
            val ann = if (a != null && a.optString("id").isNotBlank() && a.optString("message").isNotBlank())
                Announcement(a.optString("id"), a.optString("title").ifBlank { "Motion Downloader" }, a.optString("message"), a.optString("url")) else null
            val f = j.optJSONObject("features")
            val feats = HashMap<String, Boolean>()
            f?.keys()?.forEach { k -> feats[k] = f.optBoolean(k, true) }
            RemoteState(j.optString("min_version"), maint, ann, feats)
        } catch (e: Throwable) {
            RemoteState()
        }
    }
}
