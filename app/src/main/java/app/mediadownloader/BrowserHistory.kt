package app.mediadownloader

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

data class VisitedPage(val url: String, val title: String, val time: Long)

/** Pages visited in the Browser tab. Saved on the phone, newest first. */
object BrowserHistory {
    private const val MAX = 500
    private var prefs: SharedPreferences? = null
    private val _items = MutableStateFlow<List<VisitedPage>>(emptyList())
    val items: StateFlow<List<VisitedPage>> = _items.asStateFlow()

    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("browser_history", Context.MODE_PRIVATE)
        prefs = p
        _items.value = runCatching {
            val a = JSONArray(p.getString("items", "[]"))
            (0 until a.length()).map { val o = a.getJSONObject(it); VisitedPage(o.getString("url"), o.optString("title"), o.optLong("time")) }
        }.getOrDefault(emptyList())
    }

    fun add(url: String?, title: String?) {
        if (url.isNullOrBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) return
        val t = title?.takeIf { it.isNotBlank() && it != url } ?: ""
        _items.update { l ->
            val first = l.firstOrNull()
            if (first != null && first.url == url) {
                listOf(first.copy(title = t.ifBlank { first.title }, time = System.currentTimeMillis())) + l.drop(1)
            } else listOf(VisitedPage(url, t, System.currentTimeMillis())) + l.filter { it.url != url }.take(MAX - 1)
        }
        persist()
    }

    fun remove(url: String) {
        _items.update { l -> l.filter { it.url != url } }
        persist()
    }

    fun clear() {
        _items.value = emptyList()
        persist()
    }

    private fun persist() {
        val p = prefs ?: return
        val a = JSONArray()
        _items.value.forEach { a.put(JSONObject().put("url", it.url).put("title", it.title).put("time", it.time)) }
        p.edit().putString("items", a.toString()).apply()
    }
}

/** Saved pages (bookmarks), newest first. */
object Bookmarks {
    private var prefs: SharedPreferences? = null
    private val _items = MutableStateFlow<List<VisitedPage>>(emptyList())
    val items: StateFlow<List<VisitedPage>> = _items.asStateFlow()

    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)
        prefs = p
        _items.value = runCatching {
            val a = JSONArray(p.getString("items", "[]"))
            (0 until a.length()).map { val o = a.getJSONObject(it); VisitedPage(o.getString("url"), o.optString("title"), o.optLong("time")) }
        }.getOrDefault(emptyList())
    }

    fun has(url: String) = _items.value.any { it.url == url }

    fun toggle(url: String, title: String): Boolean {
        val added = !has(url)
        _items.update { l -> if (added) listOf(VisitedPage(url, title, System.currentTimeMillis())) + l else l.filter { it.url != url } }
        persist()
        return added
    }

    fun remove(url: String) { _items.update { l -> l.filter { it.url != url } }; persist() }

    private fun persist() {
        val p = prefs ?: return
        val a = JSONArray()
        _items.value.forEach { a.put(JSONObject().put("url", it.url).put("title", it.title).put("time", it.time)) }
        p.edit().putString("items", a.toString()).apply()
    }
}
