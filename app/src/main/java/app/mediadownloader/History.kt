package app.mediadownloader

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** One row in the "My downloads" list. status: active | done | failed | cancelled */
data class DlItem(
    val id: String,
    val title: String,
    val platform: String,
    val audio: Boolean,
    val status: String,
    val progress: Float,          // 0..100
    val detail: String,
    val uri: String?,
    val mime: String?,
    val path: String,
    val time: Long,
    val thumb: String?,
    val error: String?,
)

/** Download list shared by the app screen and the background service; saved on the phone. */
object History {
    private const val MAX = 300
    private var prefs: SharedPreferences? = null
    private val _items = MutableStateFlow<List<DlItem>>(emptyList())
    val items: StateFlow<List<DlItem>> = _items.asStateFlow()

    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("history", Context.MODE_PRIVATE)
        prefs = p
        val list = runCatching {
            val arr = JSONArray(p.getString("items", "[]"))
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        // A download that was running when the app was killed cannot continue.
        _items.value = list.map {
            if (it.status == "active") it.copy(status = "failed", error = "Interrupted (app was closed)") else it
        }
        persist()
    }

    fun add(item: DlItem) {
        _items.update { (listOf(item) + it).take(MAX) }
        persist()
    }

    /** Live progress: not saved to disk on every tick. */
    fun progress(id: String, percent: Float, detail: String) {
        _items.update { l -> l.map { if (it.id == id) it.copy(progress = percent, detail = detail) else it } }
    }

    fun finish(id: String, r: DownloadResult) {
        val status = when {
            r.cancelled -> "cancelled"
            r.savedCount > 0 -> "done"
            else -> "failed"
        }
        _items.update { l ->
            l.map {
                if (it.id != id) it else it.copy(
                    status = status, progress = if (status == "done") 100f else it.progress, detail = "",
                    uri = r.savedUri?.toString(), mime = r.savedMime, path = r.savedPath,
                    time = System.currentTimeMillis(), error = r.error,
                )
            }
        }
        persist()
    }

    fun remove(id: String) {
        _items.update { l -> l.filter { it.id != id } }
        persist()
    }

    fun clearFinished() {
        _items.update { l -> l.filter { it.status == "active" } }
        persist()
    }

    private fun persist() {
        val p = prefs ?: return
        val arr = JSONArray()
        _items.value.forEach { arr.put(toJson(it)) }
        p.edit().putString("items", arr.toString()).apply()
    }

    private fun toJson(i: DlItem) = JSONObject().apply {
        put("id", i.id); put("title", i.title); put("platform", i.platform); put("audio", i.audio)
        put("status", i.status); put("progress", i.progress.toDouble()); put("detail", i.detail)
        put("uri", i.uri ?: JSONObject.NULL); put("mime", i.mime ?: JSONObject.NULL); put("path", i.path)
        put("time", i.time); put("thumb", i.thumb ?: JSONObject.NULL); put("error", i.error ?: JSONObject.NULL)
    }

    private fun fromJson(o: JSONObject) = DlItem(
        id = o.getString("id"), title = o.optString("title"), platform = o.optString("platform"),
        audio = o.optBoolean("audio"), status = o.optString("status", "done"),
        progress = o.optDouble("progress", 0.0).toFloat(), detail = o.optString("detail"),
        uri = if (o.isNull("uri")) null else o.optString("uri"),
        mime = if (o.isNull("mime")) null else o.optString("mime"),
        path = o.optString("path"), time = o.optLong("time"),
        thumb = if (o.isNull("thumb")) null else o.optString("thumb"),
        error = if (o.isNull("error")) null else o.optString("error"),
    )
}
