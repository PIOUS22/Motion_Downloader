package app.mediadownloader

import android.annotation.SuppressLint
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

object BrowserSettings { var blockAds by mutableStateOf(true) }

private val AD_HOSTS = listOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "adsystem.com",
    "adnxs.com", "taboola.com", "outbrain.com", "popads.net", "propellerads.com", "exoclick.com", "criteo.com",
    "pubmatic.com", "rubiconproject.com", "moatads.com", "ads.yahoo.com", "adsterra.com", "popcash.net", "juicyads.com",
)
private const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

class Detected(val url: String, val name: String)

class FileOffer(val url: String, val ua: String?, val mime: String?, val length: Long, val name: String)

/** One browser tab: its own WebView and page state. */
@SuppressLint("SetJavaScriptEnabled")
class BrowserTab(private val ctx: Context, private val onOffer: (FileOffer) -> Unit) {
    val id = System.nanoTime()
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(0)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var started by mutableStateOf(false)
    var hasVideo by mutableStateOf(false)
    var desktop by mutableStateOf(false)
    var dark by mutableStateOf(false)
    var js by mutableStateOf(true)
    val found = mutableStateListOf<Detected>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val MEDIA_EXT = setOf("mp4", "webm", "mkv", "mov", "flv", "mp3", "m4a", "aac", "ogg", "wav")

    private var defaultUa: String? = null

    fun applyDesktop(v: Boolean) {
        desktop = v
        web.settings.userAgentString = if (v) DESKTOP_UA else defaultUa
        if (started) web.reload()
    }

    @Suppress("DEPRECATION")
    fun applyDark(v: Boolean) {
        dark = v
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) web.settings.isAlgorithmicDarkeningAllowed = v
            else if (android.os.Build.VERSION.SDK_INT >= 29) web.settings.forceDark = if (v) android.webkit.WebSettings.FORCE_DARK_ON else android.webkit.WebSettings.FORCE_DARK_OFF
        }
        if (started) web.reload()
    }

    fun applyJs(v: Boolean) {
        js = v
        web.settings.javaScriptEnabled = v
        if (started) web.reload()
    }

    val web: WebView = WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    }

    @Volatile
    private var atTop = true

    /** Called by the page script at every touch start: true when nothing on the page is scrolled down. */
    private inner class Bridge {
        @JavascriptInterface
        fun top(v: Boolean) { atTop = v }
    }

    private val trackScroll = """
        (function(){
          if (window.__mdScroll) return; window.__mdScroll = 1;
          function chk(t){
            while (t && t !== document && t.nodeType === 1) { if (t.scrollTop > 0) return false; t = t.parentElement; }
            var se = document.scrollingElement || document.documentElement;
            return (se ? se.scrollTop : 0) <= 0 && (window.scrollY || 0) <= 0;
          }
          document.addEventListener('touchstart', function(e){ try { MDBridge.top(chk(e.target)); } catch(x){} }, {passive:true, capture:true});
        })();
    """.trimIndent()

    /** Pull down at the top of a page to reload it. */
    val swipe = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(ctx).apply {
        addView(web, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        setColorSchemeColors(0xFF4F46E5.toInt())
        setOnRefreshListener { web.reload() }
        setDistanceToTriggerSync((140 * ctx.resources.displayMetrics.density).toInt())
        // Refresh only when the page really is at the top (also inside pages that scroll in a inner box).
        setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 || !atTop }
    }

    private fun sync(view: WebView?) {
        canBack = view?.canGoBack() == true
        canForward = view?.canGoForward() == true
    }

    init {
        BrowserHistory.init(ctx)
        Bookmarks.init(ctx)
        defaultUa = web.settings.userAgentString
        web.addJavascriptInterface(Bridge(), "MDBridge")
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                url = pageUrl ?: url
                found.clear()
                atTop = true
                view?.evaluateJavascript(trackScroll, null)
                sync(view)
            }

            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                url = pageUrl ?: url
                title = view?.title ?: title
                swipe.isRefreshing = false
                view?.evaluateJavascript(trackScroll, null)
                BrowserHistory.add(pageUrl, view?.title)
                sync(view)
            }

            override fun doUpdateVisitedHistory(view: WebView?, pageUrl: String?, isReload: Boolean) {
                url = pageUrl ?: url
                sync(view)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                val h = request?.url?.host ?: return null
                request.url.path?.substringAfterLast('.', "")?.lowercase()?.takeIf { it in MEDIA_EXT }?.let {
                    val u = request.url.toString()
                    mainHandler.post {
                        if (found.none { f -> f.url == u }) found.add(Detected(u, (request.url.lastPathSegment ?: "file.$it")))
                    }
                }
                if (BrowserSettings.blockAds && AD_HOSTS.any { h == it || h.endsWith(".$it") }) {
                    return android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                if (u.scheme == "http" || u.scheme == "https") return false
                // app links (intent:, fb:, ...) are not opened: stay in the browser
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress = newProgress
                if (newProgress >= 100) swipe.isRefreshing = false
            }

            override fun onReceivedTitle(view: WebView?, t: String?) {
                if (!t.isNullOrBlank()) title = t
            }
        }
        // File links (pdf, apk, zip, mp4 ...): ask first, like a download manager.
        web.setDownloadListener { link, userAgent, contentDisposition, mime, length ->
            if (link.startsWith("http://") || link.startsWith("https://")) {
                val name = URLUtil.guessFileName(link, contentDisposition, mime).replace(Regex("[\\\\/:*?\"<>|]"), "_")
                onOffer(FileOffer(link, userAgent, mime, length, name))
            } else {
                Toast.makeText(ctx, "This kind of link cannot be downloaded here", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val target = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains('.') && !t.contains(' ') -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        started = true
        web.loadUrl(target)
    }

    fun showStartPage() {
        started = false
        hasVideo = false
        url = ""
        title = ""
    }

    fun isPage() = started && (url.startsWith("http://") || url.startsWith("https://"))

    fun destroy() {
        runCatching { swipe.removeAllViews(); web.destroy() }
    }
}

/** All browser tabs. Lives as long as the main screen, so pages stay when you switch app tabs. */
class BrowserHolder(private val ctx: Context) {
    var offer by mutableStateOf<FileOffer?>(null)
    val tabs = mutableStateListOf<BrowserTab>()
    var index by mutableIntStateOf(0)
    var showTabs by mutableStateOf(false)
    var showHistory by mutableStateOf(false)
    var showBookmarks by mutableStateOf(false)
    var showFind by mutableStateOf(false)
    var showFound by mutableStateOf(false)
    var findQuery by mutableStateOf("")
    var sourceText by mutableStateOf<String?>(null)

    fun cloneTab() {
        val u = cur.url
        addTab()
        if (u.isNotBlank()) cur.go(u)
    }

    private fun makeTab() = BrowserTab(ctx) { offer = it }

    init { tabs.add(makeTab()) }

    val cur: BrowserTab get() = tabs[index.coerceIn(0, tabs.lastIndex)]
    val started: Boolean get() = cur.started
    val web: WebView get() = cur.web

    fun addTab() {
        cur.web.onPause()
        tabs.add(makeTab())
        index = tabs.lastIndex
        showTabs = false
        showHistory = false
        showBookmarks = false
    }

    fun select(i: Int) {
        cur.web.onPause()
        index = i.coerceIn(0, tabs.lastIndex)
        showTabs = false
        showHistory = false
        cur.web.onResume()
    }

    fun closeTab(i: Int) {
        if (i !in tabs.indices) return
        val t = tabs.removeAt(i)
        t.destroy()
        if (tabs.isEmpty()) tabs.add(makeTab())
        if (i < index) index -= 1
        index = index.coerceIn(0, tabs.lastIndex)
    }

    fun destroyAll() {
        tabs.forEach { it.destroy() }
    }
}

private val VIDEO_URL = Regex(
    "youtube\\.com/(watch|shorts|live)|youtu\\.be/|facebook\\.com/(watch|reel|.+/videos/)|fb\\.watch|" +
        "instagram\\.com/(reel|reels|p|tv)/|tiktok\\.com/.+/video/|vimeo\\.com/\\d+|(twitter|x)\\.com/.+/status/|dailymotion\\.com/video/",
    RegexOption.IGNORE_CASE,
)

private val SHORTCUTS = listOf(
    "YouTube" to "https://m.youtube.com",
    "Facebook" to "https://m.facebook.com",
    "Instagram" to "https://www.instagram.com",
    "TikTok" to "https://www.tiktok.com",
    "X" to "https://x.com",
    "Vimeo" to "https://vimeo.com",
    "Google" to "https://www.google.com",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowserScreen(pad: PaddingValues, b: BrowserHolder, onExit: () -> Unit = {}, onDownloadPage: (String) -> Unit) {
    val p = LocalPal.current
    val focus = LocalFocusManager.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val tab = b.cur
    var field by remember { mutableStateOf(tab.url) }
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(tab.id, tab.url) { if (!editing) field = tab.url }
    LaunchedEffect(tab.id) { tab.web.onResume() }
    DisposableEffect(tab.id) { onDispose { runCatching { tab.web.onPause() } } }
    // Is a video open on this page? (checked every 1.5 s because many sites change pages without reloading)
    LaunchedEffect(tab.id, tab.started) {
        while (tab.started) {
            tab.web.evaluateJavascript(
                "(function(){try{var v=document.querySelector('video');return !!(v&&(v.currentSrc||v.src||v.readyState>0))}catch(e){return false}})()"
            ) { r -> tab.hasVideo = r == "true" || VIDEO_URL.containsMatchIn(tab.url) }
            kotlinx.coroutines.delay(1500)
        }
        tab.hasVideo = false
    }
    b.offer?.let { o ->
        FileOfferDialog(o, onClose = { b.offer = null }) { name, wifi, retry, start, speed, parts ->
            val cookie = CookieManager.getInstance().getCookie(o.url)
            DownloadQueue.enqueue(ctx, newFileJob(o.url, name, o.length, o.ua, cookie, wifi, retry, speed, parts), start)
            Toast.makeText(ctx, if (start) "Download started" else "Added to Downloads (press Resume to start)", Toast.LENGTH_SHORT).show()
            b.offer = null
        }
    }

    Column(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding())) {
        Row(
            Modifier.fillMaxWidth().background(Color.White).statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // tab count: square with the number
            Box(
                Modifier.size(34.dp).border(2.dp, if (b.showTabs) Idm.Purple else Color(0xFF777777), RoundedCornerShape(4.dp))
                    .clickable(role = Role.Button) { b.showHistory = false; b.showTabs = !b.showTabs },
                contentAlignment = Alignment.Center,
            ) { Text(b.tabs.size.toString(), fontSize = 14.sp, color = Idm.Grey) }
            // address field (grey pill)
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFFEEEEEE)).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (field.isEmpty()) Text("Search", fontSize = 15.sp, color = Color(0xFF666666))
                androidx.compose.foundation.text.BasicTextField(
                    value = field, onValueChange = { field = it }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = Color(0xFF444444)),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { editing = it.isFocused },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { b.showHistory = false; b.showTabs = false; tab.go(field); focus.clearFocus() }),
                )
            }
            Box(
                Modifier.size(34.dp).clip(CircleShape).clickable(role = Role.Button) {
                    if (tab.found.isNotEmpty()) b.showFound = true
                    else Toast.makeText(ctx, "No files detected on this page yet", Toast.LENGTH_SHORT).show()
                },
                contentAlignment = Alignment.Center,
            ) { Ico(Ic.DDownArrow, 24.dp, Idm.Grey) }
            Box {
                Box(Modifier.size(34.dp).clip(CircleShape).clickable(role = Role.Button) { menu = true }, contentAlignment = Alignment.Center) { Ico(Ic.DDots, 22.dp, Idm.Grey) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.width(260.dp).background(Color.White)) {
                    @Composable fun item(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, check: Boolean? = null, action: () -> Unit) =
                        DropdownMenuItem(
                            text = { Text(label, fontSize = 14.sp, color = Idm.Text) }, enabled = enabled,
                            leadingIcon = { Ico(icon, 22.dp, Idm.Grey) },
                            trailingIcon = if (check != null) ({
                                Box(Modifier.size(20.dp).border(2.dp, Idm.Purple, RoundedCornerShape(3.dp)).background(if (check) Idm.Purple else Color.White))
                            }) else null,
                            onClick = { menu = false; action() },
                        )
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
                        Box(Modifier.size(32.dp).clickable { menu = false; if (tab.web.canGoBack()) tab.web.goBack() }, contentAlignment = Alignment.Center) { Ico(Ic.DArrowL, 22.dp, if (tab.canBack) Idm.Grey else Color(0xFFBBBBBB)) }
                        Box(Modifier.size(32.dp).clickable { menu = false; tab.web.goForward() }, contentAlignment = Alignment.Center) { Ico(Ic.DArrowR, 22.dp, if (tab.canForward) Idm.Grey else Color(0xFFBBBBBB)) }
                        Box(Modifier.size(32.dp).clickable {
                            menu = false
                            if (tab.isPage()) { val a = Bookmarks.toggle(tab.url, tab.title); Toast.makeText(ctx, if (a) "Bookmark added" else "Bookmark removed", Toast.LENGTH_SHORT).show() }
                        }, contentAlignment = Alignment.Center) { Ico(Ic.DStar, 22.dp, if (Bookmarks.has(tab.url)) Idm.Purple else Idm.Grey) }
                        Box(Modifier.size(32.dp).clickable { menu = false; if (tab.started) tab.web.reload() }, contentAlignment = Alignment.Center) { Ico(Ic.Retry, 22.dp, Idm.Grey) }
                        Box(Modifier.size(32.dp).clickable { menu = false; tab.applyDark(!tab.dark) }, contentAlignment = Alignment.Center) { Ico(Ic.DSun, 22.dp, Idm.Grey) }
                        Box(Modifier.size(32.dp).clickable { menu = false; b.showTabs = false; b.showBookmarks = false; b.showHistory = true }, contentAlignment = Alignment.Center) { Ico(Ic.DSort, 22.dp, Idm.Grey) }
                    }
                    item("New tab", Ic.DPlus) { b.addTab() }
                    item("Clone tab", Ic.Copy, tab.isPage()) { b.cloneTab() }
                    item("Detected files (${tab.found.size})", Ic.DReel, tab.found.isNotEmpty()) { b.showFound = true }
                    item("Find in Page", Ic.DSearch, tab.isPage()) { b.showFind = true }
                    item("Copy link", Ic.Copy, tab.isPage()) {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("link", tab.url))
                        Toast.makeText(ctx, "Link copied", Toast.LENGTH_SHORT).show()
                    }
                    item("Copy all tab links", Ic.Copy) {
                        val all = b.tabs.filter { it.isPage() }.joinToString("\n") { it.url }
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("links", all))
                        Toast.makeText(ctx, "Links copied", Toast.LENGTH_SHORT).show()
                    }
                    item("Bookmarks", Ic.DStar) { b.showTabs = false; b.showHistory = false; b.showBookmarks = true }
                    item("Share link", Ic.Link, tab.isPage()) {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tab.url)
                        runCatching { ctx.startActivity(Intent.createChooser(send, "Share link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    item("Open tabs for links in clipboard", Ic.Paste) {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val txt = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
                        val links = Regex("https?://\\S+").findAll(txt).map { it.value }.take(10).toList()
                        if (links.isEmpty()) Toast.makeText(ctx, "No links in the clipboard", Toast.LENGTH_SHORT).show()
                        links.forEach { b.addTab(); b.cur.go(it) }
                    }
                    item("Dark mode", Ic.DSun, true, tab.dark) { tab.applyDark(!tab.dark) }
                    item("Request desktop site", Ic.Globe, tab.started, tab.desktop) { tab.applyDesktop(!tab.desktop) }
                    item("Enable JavaScript", Ic.FileIc, true, tab.js) { tab.applyJs(!tab.js) }
                    item("Block ads", Ic.Close, true, BrowserSettings.blockAds) { BrowserSettings.blockAds = !BrowserSettings.blockAds; if (tab.started) tab.web.reload() }
                    item("View page source", Ic.FileIc, tab.isPage()) {
                        tab.web.evaluateJavascript("document.documentElement.outerHTML") { r ->
                            b.sourceText = runCatching { (org.json.JSONTokener(r).nextValue() as? String) ?: r }.getOrDefault(r ?: "").take(60000)
                        }
                    }
                    item("Clear browsing data", Ic.Trash) {
                        BrowserHistory.clear()
                        CookieManager.getInstance().removeAllCookies(null)
                        android.webkit.WebStorage.getInstance().deleteAllData()
                        tab.web.clearCache(true)
                        tab.web.clearFormData()
                        Toast.makeText(ctx, "Browsing data cleared", Toast.LENGTH_SHORT).show()
                    }
                    item("Start page", Ic.Home) { b.showHistory = false; b.showBookmarks = false; tab.showStartPage(); field = "" }
                    item("Close tab", Ic.Close) { b.closeTab(b.index) }
                    item("Exit browser", Ic.Back) { onExit() }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFDDDDDD)))
        if (tab.started && tab.progress in 1..99) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(p.border)) {
                Box(Modifier.fillMaxWidth(tab.progress / 100f).height(3.dp).background(p.primary))
            }
        }
        if (b.showFind && !b.showTabs && !b.showHistory && !b.showBookmarks) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = b.findQuery, onValueChange = { b.findQuery = it; tab.web.findAllAsync(it) },
                    modifier = Modifier.weight(1f), placeholder = { Text("Find in page") }, singleLine = true,
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = p.soft, unfocusedContainerColor = p.soft, focusedBorderColor = p.primary, unfocusedBorderColor = p.border),
                )
                TextButton(onClick = { tab.web.findNext(false) }) { Text("\u2191") }
                TextButton(onClick = { tab.web.findNext(true) }) { Text("\u2193") }
                TextButton(onClick = { b.showFind = false; b.findQuery = ""; tab.web.clearMatches() }) { Text("Done") }
            }
        }
        if (b.showFound) {
            IdmDialog(top = 28.dp, onDismiss = { b.showFound = false }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("File(s) detected!", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF444444))
                    Ico(Ic.DSort, 24.dp, Idm.Grey)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Select any file to download", fontSize = 14.sp, color = Color(0xFF666666))
                    Box(Modifier.background(Idm.Purple).clickable { tab.found.clear(); b.showFound = false }.padding(horizontal = 18.dp, vertical = 8.dp)) {
                        Text("RESET", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
                Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(1.dp).background(Color(0xFFDDDDDD)))
                tab.found.toList().forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            b.showFound = false
                            b.offer = FileOffer(f.url, tab.web.settings.userAgentString, null, -1L, f.name)
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) { Ico(Ic.DReel, 32.dp, Idm.Grey) }
                        Column(Modifier.weight(1f)) {
                            Text(f.url, fontSize = 12.sp, color = Color(0xFF777777), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(f.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Idm.Grey, maxLines = 2)
                        }
                        Ico(Ic.DDots, 20.dp, Idm.Grey)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFDDDDDD)))
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("CLEAR ALL", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Idm.Purple, modifier = Modifier.clickable { tab.found.clear(); b.showFound = false })
                    Text("SORT", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Idm.Purple, modifier = Modifier.clickable {
                        val s = tab.found.sortedBy { it.name.lowercase() }; tab.found.clear(); tab.found.addAll(s)
                    })
                    Text("CLOSE", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Idm.Purple, modifier = Modifier.clickable { b.showFound = false })
                }
            }
        }
        b.sourceText?.let { src ->
            AlertDialog(
                onDismissRequest = { b.sourceText = null }, containerColor = p.surface,
                title = { Text("Page source") },
                text = { Text(src, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = { b.sourceText = null }) { Text("Close") } },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (b.showTabs) {
                TabList(b)
            } else if (b.showBookmarks) {
                BookmarkList(onOpen = { u -> b.showBookmarks = false; tab.go(u) }, onClose = { b.showBookmarks = false })
            } else if (b.showHistory) {
                HistoryList(onOpen = { u -> b.showHistory = false; tab.go(u) }, onClose = { b.showHistory = false })
            } else if (!tab.started) {
                Column(Modifier.fillMaxSize().background(Color(0xFFF4F4F4)).padding(top = 140.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Motion", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4285F4), letterSpacing = (-2).sp)
                    Text("Downloader", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Idm.Purple, letterSpacing = (-1).sp)
                    var q by remember { mutableStateOf("") }
                    Row(
                        Modifier.padding(top = 28.dp).width(330.dp).height(52.dp).shadow(3.dp, RoundedCornerShape(26.dp)).background(Color.White, RoundedCornerShape(26.dp)).padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            if (q.isEmpty()) Text("Search\u2026", fontSize = 15.sp, color = Color(0xFF777777))
                            androidx.compose.foundation.text.BasicTextField(
                                value = q, onValueChange = { q = it }, singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = Idm.Text),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { tab.go(q); focus.clearFocus() }),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Ico(Ic.DSearch, 22.dp, Idm.Grey)
                    }
                    FlowRow(Modifier.padding(horizontal = 24.dp, vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SHORTCUTS.forEach { (n, u) -> TintChip(n, false) { tab.go(u) } }
                    }
                }
            } else {
                androidx.compose.runtime.key(tab.id) {
                    AndroidView(
                        factory = {
                            (tab.swipe.parent as? android.view.ViewGroup)?.removeView(tab.swipe)
                            tab.swipe
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (!b.showTabs && !b.showHistory && !b.showBookmarks && tab.isPage() && tab.hasVideo) {
                // small button: only while a video is open
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(16.dp).size(52.dp).clip(CircleShape)
                        .background(p.primary).clickable(role = Role.Button) { onDownloadPage(tab.url) },
                    contentAlignment = Alignment.Center,
                ) { Ico(Ic.Download, 24.dp, Color.White) }
            }
        }
    }
}

@Composable
private fun TabList(b: BrowserHolder) {
    val p = LocalPal.current
    Column(Modifier.fillMaxSize().background(p.bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tabs (${b.tabs.size})", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            PillButton("New tab", p.primary, Color.White) { b.addTab() }
            TextButton(onClick = { b.showTabs = false }) { Text("Close") }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(b.tabs.size, key = { b.tabs[it].id }) { i ->
                val t = b.tabs[i]
                val sel = i == b.index
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(if (sel) p.tint else p.surface)
                        .border(1.5.dp, if (sel) p.primary else p.border, RoundedCornerShape(14.dp))
                        .clickable { b.select(i) }.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            t.title.ifBlank { if (t.started) Urls.host(t.url).ifBlank { "Loading…" } else "New tab" },
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Muted(if (t.started) t.url else "Start page", maxLines = 1)
                    }
                    RoundIconButton(Ic.Close, "Close tab", if (sel) p.surface else p.soft, p.text) { b.closeTab(i) }
                }
            }
        }
    }
}

@Composable
private fun FileOfferDialog(o: FileOffer, onClose: () -> Unit, onSubmit: (String, Boolean, Boolean, Boolean, Int, Int) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var name by remember { mutableStateOf(o.name) }
    var wifi by remember { mutableStateOf(AppPrefs.wifiOnly(ctx)) }
    var retry by remember { mutableStateOf(true) }
    var advanced by remember { mutableStateOf(true) }
    var speedStep by remember { mutableIntStateOf(SPEEDS.lastIndex) }
    var parts by remember { mutableIntStateOf(8) }
    val where = if (AppPrefs.p(ctx).getString("save_uri", null) != null) "Your chosen folder / Files" else "Downloads/MotionDownloader/Files"
    val size = if (o.length > 0) "${fmtSize(o.length)} (${o.length} bytes)" else "Unknown size"
    IdmDialog(top = 40.dp, onDismiss = onClose) {
        IdmTitle("Download file!")
        Text("Link (Resume: N/A)", fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.padding(top = 14.dp))
        Text(o.url, fontSize = 12.sp, color = Color(0xFF999999), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        IdmField(name.substringBeforeLast('.', name), { name = it + (if (name.contains('.')) "." + name.substringAfterLast('.') else "") }, "File name", Modifier.padding(top = 10.dp), 15) {
            Text(name.substringAfterLast('.', "").lowercase(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Idm.Orange, modifier = Modifier.padding(start = 16.dp))
        }
        Text(size, fontSize = 13.sp, color = Color(0xFF666666), modifier = Modifier.padding(top = 12.dp))
        StorageText()
        Text(where, fontSize = 14.sp, color = Idm.Text, modifier = Modifier.padding(top = 12.dp))
        Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(1.dp).background(Color(0xFFCCCCCC)))
        Column(Modifier.padding(top = 4.dp)) {
            IdmCheck("Wifi only", wifi, Idm.Orange) { wifi = it }
            IdmCheck("Retry", retry) { retry = it }
            IdmCheck("Advance option", advanced) { advanced = it }
        }
        if (advanced) {
            Text(buildAnnotatedString { append("Number of parts: "); pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); append(parts.toString()); pop() }, fontSize = 13.sp, color = Idm.Grey, modifier = Modifier.padding(top = 10.dp))
            IdmSlider(parts.toFloat(), { parts = it.toInt().coerceIn(1, 32) }, 1f..32f, 30)
            val sp = SPEEDS[speedStep]
            Text(buildAnnotatedString {
                append("Download speed limit: "); pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(if (sp == 0) "MAX" else if (sp >= 1024) "${sp / 1024} MB/s" else "$sp KB/s"); pop()
            }, fontSize = 13.sp, color = Idm.Grey, modifier = Modifier.padding(top = 4.dp))
            IdmSlider(speedStep.toFloat(), { speedStep = it.toInt().coerceIn(0, SPEEDS.lastIndex) }, 0f..SPEEDS.lastIndex.toFloat(), SPEEDS.size - 2)
        }
        val n = name.trim().ifEmpty { o.name }
        IdmButtons("ADD", { onSubmit(n, wifi, retry, false, if (advanced) SPEEDS[speedStep] else 0, if (advanced) parts else 1) }, onClose) {
            onSubmit(n, wifi, retry, true, if (advanced) SPEEDS[speedStep] else 0, if (advanced) parts else 1)
        }
    }
}

/** Speed choices of the slider: 0 = maximum (last position). */
private val SPEEDS = listOf(64, 128, 256, 512, 1024, 2048, 5120, 0)

internal fun storageLine(ctx: Context): String = runCatching {
    val st = android.os.StatFs(Environment.getExternalStorageDirectory().path)
    val total = st.totalBytes
    val free = st.availableBytes
    "Storage: ${fmtSize(total - free)} / ${fmtSize(total)}, ${"%.1f".format(free * 100.0 / total)}% free"
}.getOrDefault("")
