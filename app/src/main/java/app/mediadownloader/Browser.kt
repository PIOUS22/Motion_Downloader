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

/** One WebView that lives as long as the main screen, so the page stays when you switch tabs. */
@SuppressLint("SetJavaScriptEnabled")
class BrowserHolder(private val ctx: Context) {
    var url by mutableStateOf("")
    var progress by mutableIntStateOf(0)
    var canBack by mutableStateOf(false)
    var started by mutableStateOf(false)

    val web: WebView = WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    }

    init {
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                url = pageUrl ?: url
                canBack = view?.canGoBack() == true
            }

            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                url = pageUrl ?: url
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
            }
        }
        // Normal file links (pdf, apk, zip, mp4 ...): saved by Android's download manager.
        web.setDownloadListener { link, userAgent, contentDisposition, mime, _ ->
            try {
                val name = URLUtil.guessFileName(link, contentDisposition, mime)
                val req = DownloadManager.Request(Uri.parse(link)).apply {
                    setMimeType(mime)
                    addRequestHeader("User-Agent", userAgent)
                    CookieManager.getInstance().getCookie(link)?.let { addRequestHeader("Cookie", it) }
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MotionDownloader/Files/$name")
                }
                (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(ctx, "Downloading $name (see notifications)", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Cannot download this file here", Toast.LENGTH_LONG).show()
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
        url = ""
    }

    fun isPage() = started && (url.startsWith("http://") || url.startsWith("https://"))
}

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
    LaunchedEffect(b.url) { if (!editing) field = b.url }
    LaunchedEffect(Unit) { b.web.onResume() }
    DisposableEffect(Unit) { onDispose { b.web.onPause() } }

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
                keyboardActions = KeyboardActions(onGo = { b.go(field); focus.clearFocus() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = p.soft, unfocusedContainerColor = p.soft,
                    focusedBorderColor = p.primary, unfocusedBorderColor = p.border,
                ),
            )
            RoundIconButton(Ic.Retry, "Reload", p.soft, p.text) { if (b.started) b.web.reload() else b.go(field) }
            RoundIconButton(Ic.Home, "Start page", p.soft, p.text) { b.showStartPage(); field = "" }
        }
        if (b.started && b.progress in 1..99) {
            Box(Modifier.fillMaxWidth().height(3.dp).background0(p.border)) {
                Box(Modifier.fillMaxWidth(b.progress / 100f).height(3.dp).background0(p.primary))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!b.started) {
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
                        (b.web.parent as? android.view.ViewGroup)?.removeView(b.web)
                        b.web
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (b.isPage()) {
                BigButton(
                    "Download", { onDownloadPage(b.url) },
                    Modifier.align(Alignment.BottomEnd).padding(16.dp), icon = Ic.Download,
                )
            }
        }
    }
}

private fun Modifier.background0(c: androidx.compose.ui.graphics.Color) =
    this.then(Modifier.background(c))
