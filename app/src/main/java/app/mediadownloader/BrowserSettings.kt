package app.mediadownloader

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Browser settings (stored in the normal "settings" preferences, keys start with b_). */
object BPrefs {
    /** Bumped after every change so open tabs re-apply the settings. */
    var version by mutableIntStateOf(0)

    private fun p(ctx: Context) = AppPrefs.p(ctx)
    fun bool(ctx: Context, k: String, d: Boolean) = p(ctx).getBoolean("b_$k", d)
    fun str(ctx: Context, k: String, d: String) = p(ctx).getString("b_$k", d) ?: d
    fun int(ctx: Context, k: String, d: Int) = p(ctx).getInt("b_$k", d)
    fun setBool(ctx: Context, k: String, v: Boolean) { p(ctx).edit().putBoolean("b_$k", v).apply(); version++ }
    fun setStr(ctx: Context, k: String, v: String) { p(ctx).edit().putString("b_$k", v).apply(); version++ }
    fun setInt(ctx: Context, k: String, v: Int) { p(ctx).edit().putInt("b_$k", v).apply(); version++ }

    fun newWindows(ctx: Context) = bool(ctx, "newwin", true)
    fun pauseTimers(ctx: Context) = bool(ctx, "pause", true)
    fun homepage(ctx: Context) = str(ctx, "home", "")

    fun searchUrl(ctx: Context, q: String): String {
        val e = android.net.Uri.encode(q)
        return when (str(ctx, "search", "google")) {
            "bing" -> "https://www.bing.com/search?q=$e"
            "ddg" -> "https://duckduckgo.com/?q=$e"
            "yahoo" -> "https://search.yahoo.com/search?p=$e"
            else -> "https://www.google.com/search?q=$e"
        }
    }

    private const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    @Suppress("DEPRECATION")
    fun apply(ctx: Context, web: WebView, appDark: Boolean) {
        val s = web.settings
        runCatching {
            s.javaScriptEnabled = bool(ctx, "js", true)
            s.loadsImagesAutomatically = !bool(ctx, "noimg", false)
            s.blockNetworkImage = bool(ctx, "noimg", false)
            s.useWideViewPort = bool(ctx, "wide", true)
            s.loadWithOverviewMode = bool(ctx, "overview", true)
            s.textZoom = int(ctx, "text", 100).coerceIn(50, 200)
            s.setSupportZoom(bool(ctx, "zoom", true))
            s.builtInZoomControls = bool(ctx, "zoom", true)
            s.displayZoomControls = false
            s.defaultTextEncodingName = str(ctx, "enc", "UTF-8")
            s.userAgentString = if (str(ctx, "ua", "default") == "desktop") DESKTOP_UA else WebSettings.getDefaultUserAgent(ctx)
            if (Build.VERSION.SDK_INT >= 26) s.safeBrowsingEnabled = bool(ctx, "safe", true)
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(bool(ctx, "cookies", true))
            cm.setAcceptThirdPartyCookies(web, !bool(ctx, "no3p", false))
            web.setLayerType(if (str(ctx, "render", "normal") == "software") android.view.View.LAYER_TYPE_SOFTWARE else android.view.View.LAYER_TYPE_NONE, null)
            val dark = when (str(ctx, "dark", "theme")) { "on" -> true; "off" -> false; else -> appDark }
            if (Build.VERSION.SDK_INT >= 33) s.isAlgorithmicDarkeningAllowed = dark
            else if (Build.VERSION.SDK_INT >= 29) s.forceDark = if (dark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
        }
    }

    fun clearCache(ctx: Context, web: WebView?) {
        runCatching { web?.clearCache(true) }
    }

    fun clearCookies() {
        runCatching { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush() }
    }
}

// ------------------------------------------------------------------------------------------ screens

private data class Opt(val id: String, val label: String)

@Composable
private fun Head(title: String, onBack: () -> Unit) {
    val p = LocalPal.current
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onBack), contentAlignment = Alignment.Center) {
            Ico(Ic.Back, 22.dp, p.text)
        }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun Row2(title: String, sub: String?, onClick: (() -> Unit)?, trailing: @Composable () -> Unit = {}) {
    val p = LocalPal.current
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.text)
            if (!sub.isNullOrBlank()) Text(sub, fontSize = 12.sp, color = p.muted)
        }
        trailing()
    }
}

@Composable
private fun Sw(ctx: Context, key: String, def: Boolean, title: String, sub: String? = null, onChange: ((Boolean) -> Unit)? = null) {
    BPrefs.version
    val v = BPrefs.bool(ctx, key, def)
    Row2(title, sub, { BPrefs.setBool(ctx, key, !v); onChange?.invoke(!v) }) {
        Switch(checked = v, onCheckedChange = { BPrefs.setBool(ctx, key, it); onChange?.invoke(it) })
    }
}

@Composable
private fun Pick(ctx: Context, key: String, def: String, title: String, opts: List<Opt>) {
    BPrefs.version
    var open by remember { mutableStateOf(false) }
    val cur = BPrefs.str(ctx, key, def)
    Row2(title, opts.firstOrNull { it.id == cur }?.label ?: cur, { open = true })
    if (open) {
        val p = LocalPal.current
        AlertDialog(
            onDismissRequest = { open = false }, containerColor = p.surface, title = { Text(title) },
            text = {
                Column {
                    opts.forEach { o ->
                        Row(
                            Modifier.fillMaxWidth().clickable { BPrefs.setStr(ctx, key, o.id); open = false },
                            verticalAlignment = Alignment.CenterVertically,
                        ) { RadioButton(selected = cur == o.id, onClick = { BPrefs.setStr(ctx, key, o.id); open = false }); Text(o.label) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun Num(ctx: Context, key: String, def: Int, title: String, unit: String, min: Int, max: Int) {
    BPrefs.version
    var open by remember { mutableStateOf(false) }
    val cur = BPrefs.int(ctx, key, def)
    Row2(title, "$cur$unit", { open = true })
    if (open) {
        var t by remember { mutableStateOf(cur.toString()) }
        val p = LocalPal.current
        AlertDialog(
            onDismissRequest = { open = false }, containerColor = p.surface, title = { Text(title) },
            text = { OutlinedTextField(value = t, onValueChange = { t = it.filter(Char::isDigit).take(4) }, singleLine = true, label = { Text("$min - $max") }) },
            confirmButton = {
                TextButton(onClick = { BPrefs.setInt(ctx, key, (t.toIntOrNull() ?: def).coerceIn(min, max)); open = false }) { Text("Save", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Txt(ctx: Context, key: String, title: String, hint: String) {
    BPrefs.version
    var open by remember { mutableStateOf(false) }
    val cur = BPrefs.str(ctx, key, "")
    Row2(title, cur.ifBlank { "Default" }, { open = true })
    if (open) {
        var t by remember { mutableStateOf(cur) }
        val p = LocalPal.current
        AlertDialog(
            onDismissRequest = { open = false }, containerColor = p.surface, title = { Text(title) },
            text = { OutlinedTextField(value = t, onValueChange = { t = it }, singleLine = true, placeholder = { Text(hint) }) },
            confirmButton = { TextButton(onClick = { BPrefs.setStr(ctx, key, t.trim()); open = false }) { Text("Save", fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { BPrefs.setStr(ctx, key, ""); open = false }) { Text("Default") } },
        )
    }
}

@Composable
private fun Act(title: String, sub: String? = null, onClick: () -> Unit) = Row2(title, sub, onClick)

/** The settings pages of the in-app browser. `page` is "" for the list. */
@Composable
fun BrowserSettingsScreen(page: String, onPage: (String) -> Unit, onClose: () -> Unit, onClearCache: () -> Unit, onClearHistory: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(p.bg)) {
        val title = when (page) {
            "general" -> "General settings"; "display" -> "Display settings"; "privacy" -> "Privacy settings"
            "perm" -> "Website permissions"; "adv" -> "Advanced settings"; else -> "Browser settings"
        }
        Head(title) { if (page.isEmpty()) onClose() else onPage("") }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            when (page) {
                "" -> {
                    Act("General settings", "Search engine, homepage, user agent, JavaScript, images") { onPage("general") }
                    Act("Display settings", "Dark mode, text size, zoom, viewport") { onPage("display") }
                    Act("Privacy settings", "Cookies, clear data on exit, SSL") { onPage("privacy") }
                    Act("Website permissions", "New windows and ad / pop-up blocker") { onPage("perm") }
                    Act("Advanced settings", "Encoding, rendering mode, safe browsing") { onPage("adv") }
                }
                "general" -> {
                    Sw(ctx, "norefresh", false, "Disable refresh", "Turn off pull to refresh in browser")
                    Pick(ctx, "search", "google", "Search engine", listOf(Opt("google", "Google"), Opt("bing", "Bing"), Opt("ddg", "DuckDuckGo"), Opt("yahoo", "Yahoo")))
                    Txt(ctx, "home", "Homepage", "https://example.com")
                    Pick(ctx, "ua", "default", "User agent", listOf(Opt("default", "Default (phone)"), Opt("desktop", "Desktop site")))
                    Sw(ctx, "noimg", false, "Block images", "Pages load faster and use less data")
                    Sw(ctx, "js", true, "Enable JavaScript", "Turning this off breaks many sites")
                }
                "display" -> {
                    Pick(ctx, "dark", "theme", "Dark mode in browser", listOf(Opt("theme", "Based on theme"), Opt("on", "Always on"), Opt("off", "Always off")))
                    Sw(ctx, "zoom", true, "Allow pinch zoom")
                    Sw(ctx, "wide", true, "Use wide viewport", "Recommended")
                    Sw(ctx, "overview", true, "Load pages in overview mode", "Recommended")
                    Num(ctx, "text", 100, "Text size", "%", 50, 200)
                }
                "privacy" -> {
                    Sw(ctx, "cookies", true, "Enable cookies", "Recommended")
                    Sw(ctx, "no3p", false, "Block 3rd party cookies")
                    Sw(ctx, "ssl", false, "Ignore SSL errors on websites and continue browsing", "Not safe: only for sites you trust")
                    Sw(ctx, "xcache", false, "Clear cache on exit")
                    Sw(ctx, "xhist", false, "Clear history on exit")
                    Sw(ctx, "xcookie", false, "Clear cookies on exit")
                    Act("Clear cache") { onClearCache(); Toast.makeText(ctx, "Cache cleared", Toast.LENGTH_SHORT).show() }
                    Act("Clear browser history") { onClearHistory(); Toast.makeText(ctx, "History cleared", Toast.LENGTH_SHORT).show() }
                    Act("Clear cookies") { BPrefs.clearCookies(); Toast.makeText(ctx, "Cookies cleared", Toast.LENGTH_SHORT).show() }
                }
                "perm" -> {
                    Sw(ctx, "newwin", true, "Allow sites to open new windows", "Pop-ups are still blocked while Ad block is on")
                    BPrefs.version
                    Row2("Ad block", if (AdBlock.enabled) "On: blocks ads, trackers and pop-ups" else "Off", { AdBlock.set(ctx, !AdBlock.enabled); BPrefs.version++ }) {
                        Switch(checked = AdBlock.enabled, onCheckedChange = { AdBlock.set(ctx, it); BPrefs.version++ })
                    }
                }
                "adv" -> {
                    Pick(ctx, "enc", "UTF-8", "Text encoding", listOf(Opt("UTF-8", "UTF-8"), Opt("ISO-8859-1", "ISO-8859-1"), Opt("windows-1252", "windows-1252"), Opt("Shift_JIS", "Shift_JIS"), Opt("GBK", "GBK")))
                    Pick(ctx, "render", "normal", "Rendering mode", listOf(Opt("normal", "Normal"), Opt("software", "Software (fixes some display glitches)")))
                    Sw(ctx, "safe", true, "Enable safe browsing", "Warns about dangerous sites")
                    Sw(ctx, "pause", true, "Pause JavaScript timers when the browser is closed or switched")
                }
            }
        }
    }
}
