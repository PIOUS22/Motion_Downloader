package app.mediadownloader

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * One download job / row in the Downloads list.
 * status: pending | active | paused | done | failed | cancelled
 */
data class DlItem(
    val id: String,
    val title: String,
    val platform: String,
    val audio: Boolean,
    val status: String,
    val progress: Float = 0f,          // 0..100
    val detail: String = "",
    val uri: String? = null,
    val mime: String? = null,
    val path: String = "",
    val time: Long = System.currentTimeMillis(),
    val thumb: String? = null,
    val error: String? = null,
    // what is needed to (re)run the job
    val url: String = "",
    val presetIdx: Int = 2,
    val isPlaylist: Boolean = false,
    val items: List<Int> = emptyList(),
    val subsLang: String? = null,       // null = no subtitles
    val subsMode: String = "file",
    val subsAuto: Boolean = true,
    val label: String = "",             // e.g. "1080p · MP4"
    // direct file downloads started from the browser
    val fileUrl: String? = null,
    val fileName: String = "",
    val fileUa: String? = null,
    val fileCookie: String? = null,
    val wifiOnly: Boolean = false,
    val retry: Boolean = true,
)

fun newFileJob(
    url: String, name: String, size: Long, ua: String?, cookie: String?, wifiOnly: Boolean, retry: Boolean,
) = DlItem(
    id = java.util.UUID.randomUUID().toString(),
    title = name, platform = "Files", audio = false, status = "pending",
    url = url, label = (if (size > 0) fmtSize(size) else "File") + " \u00B7 File",
    fileUrl = url, fileName = name, fileUa = ua, fileCookie = cookie, wifiOnly = wifiOnly, retry = retry,
)

fun newJob(
    a: Analysis, presetIdx: Int, subsLang: String?, subsMode: String, subsAuto: Boolean,
    isPlaylist: Boolean = false, items: List<Int> = emptyList(),
): DlItem {
    val preset = PRESETS[presetIdx]
    return DlItem(
        id = java.util.UUID.randomUUID().toString(),
        title = if (isPlaylist) (a.playlistTitle ?: a.title) else a.title,
        platform = a.platform,
        audio = preset.isAudio,
        status = "pending",
        thumb = a.thumb,
        url = if (isPlaylist) (a.playlistUrl ?: a.url) else a.url,
        presetIdx = presetIdx,
        isPlaylist = isPlaylist,
        items = items,
        subsLang = subsLang,
        subsMode = subsMode,
        subsAuto = subsAuto,
        label = preset.shortLabel + (if (isPlaylist) " · playlist" else ""),
    )
}

/** Download list shared by the app screens, the share pop-up and the queue; saved on the phone. */
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
        // Jobs that were running when the app was killed become "paused" and can be resumed.
        _items.value = list.map {
            if (it.status == "active" || it.status == "pending") it.copy(status = "paused", detail = "Paused") else it
        }
        persist()
    }

    fun get(id: String): DlItem? = _items.value.firstOrNull { it.id == id }

    fun add(item: DlItem) {
        _items.update { (listOf(item) + it).take(MAX) }
        persist()
    }

    /** Live progress: not saved to disk on every tick. */
    fun progress(id: String, percent: Float, detail: String) {
        _items.update { l -> l.map { if (it.id == id) it.copy(progress = percent, detail = detail) else it } }
    }

    fun setStatus(id: String, status: String, detail: String = "", error: String? = null) {
        _items.update { l ->
            l.map { if (it.id == id) it.copy(status = status, detail = detail, error = error) else it }
        }
        persist()
    }

    fun setDetailForPending(detail: String) {
        _items.update { l -> l.map { if (it.status == "pending") it.copy(detail = detail) else it } }
    }

    fun resetForRetry(id: String) {
        _items.update { l ->
            l.map {
                if (it.id == id) it.copy(status = "pending", progress = 0f, detail = "", error = null, uri = null, path = "")
                else it
            }
        }
        persist()
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
        _items.update { l -> l.filter { it.status == "active" || it.status == "pending" || it.status == "paused" } }
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
        put("url", i.url); put("presetIdx", i.presetIdx); put("isPlaylist", i.isPlaylist)
        put("items", JSONArray(i.items)); put("subsLang", i.subsLang ?: JSONObject.NULL)
        put("subsMode", i.subsMode); put("subsAuto", i.subsAuto); put("label", i.label)
        put("fileUrl", i.fileUrl ?: JSONObject.NULL); put("fileName", i.fileName)
        put("fileUa", i.fileUa ?: JSONObject.NULL); put("fileCookie", i.fileCookie ?: JSONObject.NULL)
        put("wifiOnly", i.wifiOnly); put("retry", i.retry)
    }

    private fun fromJson(o: JSONObject): DlItem {
        val arr = o.optJSONArray("items")
        return DlItem(
            id = o.getString("id"), title = o.optString("title"), platform = o.optString("platform"),
            audio = o.optBoolean("audio"), status = o.optString("status", "done"),
            progress = o.optDouble("progress", 0.0).toFloat(), detail = o.optString("detail"),
            uri = if (o.isNull("uri")) null else o.optString("uri"),
            mime = if (o.isNull("mime")) null else o.optString("mime"),
            path = o.optString("path"), time = o.optLong("time"),
            thumb = if (o.isNull("thumb")) null else o.optString("thumb"),
            error = if (o.isNull("error")) null else o.optString("error"),
            url = o.optString("url"), presetIdx = o.optInt("presetIdx", 2).coerceIn(0, PRESETS.size - 1),
            isPlaylist = o.optBoolean("isPlaylist"),
            items = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getInt(it) },
            subsLang = if (o.isNull("subsLang")) null else o.optString("subsLang"),
            subsMode = o.optString("subsMode", "file"), subsAuto = o.optBoolean("subsAuto", true),
            label = o.optString("label"),
            fileUrl = if (o.isNull("fileUrl")) null else o.optString("fileUrl"),
            fileName = o.optString("fileName"),
            fileUa = if (o.isNull("fileUa")) null else o.optString("fileUa"),
            fileCookie = if (o.isNull("fileCookie")) null else o.optString("fileCookie"),
            wifiOnly = o.optBoolean("wifiOnly"), retry = o.optBoolean("retry", true),
        )
    }
}
