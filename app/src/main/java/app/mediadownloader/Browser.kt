package app.mediadownloader

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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

class FileOffer(val url: String, val ua: String?, val mime: String?, val length: Long, val name: String)

/** One WebView that lives as long as the main screen, so the page stays when you switch tabs. */
@SuppressLint("SetJavaScriptEnabled")
class BrowserHolder(private val ctx: Context) {
    var url by mutableStateOf("")
    var progress by mutableIntStateOf(0)
    var canBack by mutableStateOf(false)
    var started by mutableStateOf(false)
    var hasVideo by mutableStateOf(false)
    var offer by mutableStateOf<FileOffer?>(null)

    val web: WebView = WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    }

    /** Pull down at the top of a page to reload it. */
    val swipe = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(ctx).apply {
        addView(web, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        setColorSchemeColors(0xFF4F46E5.toInt())
        setOnRefreshListener { web.reload() }
        setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 }
    }

    init {
        BrowserHistory.init(ctx)
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                url = pageUrl ?: url
                canBack = view?.canGoBack() == true
            }

            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                url = pageUrl ?: url
                swipe.isRefreshing = false
                BrowserHistory.add(pageUrl, view?.title)
                canBack = view?.canGoBack() == true
            }

            override fun doUpdateVisitedHistory(view: WebView?, pageUrl: String?, isReload: Boolean) {
                url = pageUrl ?: url
                canBack = view?.canGoBack() == true
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
        }
        // File links (pdf, apk, zip, mp4 ...): ask first, like a download manager.
        web.setDownloadListener { link, userAgent, contentDisposition, mime, length ->
            if (link.startsWith("http://") || link.startsWith("https://")) {
                val name = URLUtil.guessFileName(link, contentDisposition, mime).replace(Regex("[\\\\/:*?\"<>|]"), "_")
                offer = FileOffer(link, userAgent, mime, length, name)
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
    }

    fun isPage() = started && (url.startsWith("http://") || url.startsWith("https://"))
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
fun BrowserScreen(pad: PaddingValues, b: BrowserHolder, onDownloadPage: (String) -> Unit) {
    val p = LocalPal.current
    val focus = LocalFocusManager.current
    var field by remember { mutableStateOf(b.url) }
    var editing by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    LaunchedEffect(b.url) { if (!editing) field = b.url }
    LaunchedEffect(Unit) { b.web.onResume() }
    DisposableEffect(Unit) { onDispose { b.web.onPause() } }
    // Is a video open on this page? (checked every 1.5 s because many sites change pages without reloading)
    LaunchedEffect(b.started) {
        while (b.started) {
            b.web.evaluateJavascript(
                "(function(){try{var v=document.querySelector('video');return !!(v&&(v.currentSrc||v.src||v.readyState>0))}catch(e){return false}})()"
            ) { r -> b.hasVideo = r == "true" || VIDEO_URL.containsMatchIn(b.url) }
            kotlinx.coroutines.delay(1500)
        }
        b.hasVideo = false
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    b.offer?.let { o ->
        FileOfferDialog(o, onClose = { b.offer = null }) { name, wifi, retry, start ->
            val cookie = CookieManager.getInstance().getCookie(o.url)
            DownloadQueue.enqueue(ctx, newFileJob(o.url, name, o.length, o.ua, cookie, wifi, retry), start)
            Toast.makeText(ctx, if (start) "Download started" else "Added to Downloads (press Resume to start)", Toast.LENGTH_SHORT).show()
            b.offer = null
        }
    }

    Column(Modifier.fillMaxSize().padding(pad)) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RoundIconButton(Ic.Back, "Back", if (b.canBack) p.tint else p.soft, if (b.canBack) p.onTint else p.muted) {
                if (b.web.canGoBack()) b.web.goBack()
            }
            OutlinedTextField(
                value = field, onValueChange = { field = it },
                modifier = Modifier.weight(1f).onFocusChanged { editing = it.isFocused },
                placeholder = { Text("Search or type a link") }, singleLine = true, shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { showHistory = false; b.go(field); focus.clearFocus() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = p.soft, unfocusedContainerColor = p.soft,
                    focusedBorderColor = p.primary, unfocusedBorderColor = p.border,
                ),
            )
            RoundIconButton(Ic.Clock, "History", if (showHistory) p.tint else p.soft, if (showHistory) p.onTint else p.text) { showHistory = !showHistory }
            RoundIconButton(Ic.Home, "Start page", p.soft, p.text) { showHistory = false; b.showStartPage(); field = "" }
        }
        if (b.started && b.progress in 1..99) {
            Box(Modifier.fillMaxWidth().height(3.dp).background0(p.border)) {
                Box(Modifier.fillMaxWidth(b.progress / 100f).height(3.dp).background0(p.primary))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showHistory) {
                HistoryList(onOpen = { u -> showHistory = false; b.go(u) }, onClose = { showHistory = false })
            } else if (!b.started) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Browse anything", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Open a video page, then tap Download to save it. File links (PDF, APK, ZIP …) download directly.",
                        fontSize = 15.sp, color = p.muted,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SHORTCUTS.forEach { (n, u) -> TintChip(n, false) { b.go(u) } }
                    }
                }
            } else {
                AndroidView(
                    factory = {
                        (b.swipe.parent as? android.view.ViewGroup)?.removeView(b.swipe)
                        b.swipe
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (!showHistory && b.isPage() && b.hasVideo) {
                // small button: only while a video is open
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(16.dp).size(52.dp).clip(CircleShape)
                        .background(p.primary).clickable(role = Role.Button) { onDownloadPage(b.url) },
                    contentAlignment = Alignment.Center,
                ) { Ico(Ic.Download, 24.dp, Color.White) }
            }
        }
    }
}

private fun Modifier.background0(c: androidx.compose.ui.graphics.Color) =
    this.then(Modifier.background(c))

@Composable
private fun FileOfferDialog(o: FileOffer, onClose: () -> Unit, onSubmit: (String, Boolean, Boolean, Boolean) -> Unit) {
    val p = LocalPal.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var name by remember { mutableStateOf(o.name) }
    var wifi by remember { mutableStateOf(AppPrefs.wifiOnly(ctx)) }
    var retry by remember { mutableStateOf(true) }
    val where = if (AppPrefs.p(ctx).getString("save_uri", null) != null) "Your chosen folder / Files"
    else "Downloads/MotionDownloader/Files"
    val size = if (o.length > 0) "${fmtSize(o.length)} (${o.length} bytes)" else "Unknown"
    AlertDialog(
        onDismissRequest = onClose, containerColor = p.surface,
        title = { Text("Download file!", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Muted("Link")
                Text(o.url, fontSize = 13.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Muted("Save as")
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Size: $size", fontSize = 13.sp, color = p.muted)
                Muted("Download location:")
                Text(where, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = wifi, onCheckedChange = { wifi = it })
                    Text("Wi-Fi only", fontSize = 14.sp)
                    Checkbox(checked = retry, onCheckedChange = { retry = it })
                    Text("Retry", fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            Row {
                val n = name.trim().ifEmpty { o.name }
                TextButton(onClick = { onSubmit(n, wifi, retry, false) }) { Text("ADD") }
                TextButton(onClick = onClose) { Text("CANCEL") }
                TextButton(onClick = { onSubmit(n, wifi, retry, true) }) { Text("START", fontWeight = FontWeight.Bold) }
            }
        },
    )
}

@Composable
private fun HistoryList(onOpen: (String) -> Unit, onClose: () -> Unit) {
    val p = LocalPal.current
    val list by BrowserHistory.items.collectAsState()
    val fmt = remember { java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.getDefault()) }
    Column(Modifier.fillMaxSize().background(p.bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("History", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (list.isNotEmpty()) TextButton(onClick = { BrowserHistory.clear() }) { Text("Clear all") }
            TextButton(onClick = onClose) { Text("Close") }
        }
        if (list.isEmpty()) Text("No pages yet. Pages you open will be listed here.", color = p.muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 24.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(list, key = { it.url }) { v ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(v.url) }.padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(v.title.ifBlank { Urls.host(v.url).ifBlank { v.url } }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Muted(v.url, maxLines = 1)
                        Muted(fmt.format(java.util.Date(v.time)))
                    }
                    RoundIconButton(Ic.Close, "Remove", p.soft, p.text) { BrowserHistory.remove(v.url) }
                }
            }
        }
    }
}
