package app.mediadownloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/** True when the app was opened from the "Update available" notification. */
private fun activityWantsUpdate(ctx: Context): Boolean =
    (ctx as? ComponentActivity)?.intent?.getBooleanExtra("open_update", false) == true

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        handleInstall(intent)
        DownloadQueue.watchNetwork(this)
        setContent { App(vm) }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshRemote()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
        handleInstall(intent)
    }

    private fun handleInstall(i: Intent?) {
        if (i?.getBooleanExtra("install_update", false) == true) {
            i.removeExtra("install_update")
            Updater.installReady(this)
        }
    }

    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            vm.setUrl(Urls.normalize(text))
            vm.analyze()   // queued by the view model if the engine is still starting
        }
    }
}

// ====================================================================== root

@Composable
private fun App(vm: MainViewModel) {
    val s by vm.ui.collectAsState()
    val ctx = LocalContext.current
    val hist by History.items.collectAsState()
    var screen by rememberSaveable { mutableStateOf("home") }
    var dlFilter by rememberSaveable { mutableStateOf("all") }
    var lastScreen by rememberSaveable { mutableStateOf("home") }
    val browser = remember { BrowserHolder(ctx) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { browser.destroyAll() } }
    LaunchedEffect(screen) { if (screen != "result") lastScreen = screen }
    // analysis problems while browsing show as a message (the Home screen is not visible)
    LaunchedEffect(s.status, s.statusIsError) {
        if (screen == "browser" && s.statusIsError && s.status.isNotBlank()) {
            Toast.makeText(ctx, s.status.take(200), Toast.LENGTH_LONG).show()
        }
    }

    // first-run permissions
    val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var showPerm by remember { mutableStateOf(!prefs.getBoolean("perm_asked", false)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        prefs.edit().putBoolean("perm_asked", true).apply()
        showPerm = false
    }
    LaunchedEffect(Unit) { Updater.loadState(ctx) }
    // "What's new": shown once after the app was updated (not on a fresh install)
    var whatsNew by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val last = prefs.getString("seen_version", null)
        if (last != BuildConfig.VERSION_NAME) {
            prefs.edit().putString("seen_version", BuildConfig.VERSION_NAME).apply()
            if (last != null) whatsNew = runCatching {
                ctx.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
                    .lineSequence().dropWhile { !it.startsWith("## ") }.drop(1)
                    .takeWhile { !it.startsWith("## ") }.joinToString("\n").trim()
            }.getOrNull()?.ifBlank { null }
        }
    }
    val us by Updater.state.collectAsState()
    var readyLater by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { if (activityWantsUpdate(ctx)) { vm.checkAppUpdate(true); screen = "settings" } }
    LaunchedEffect(s.analysis) { if (s.analysis != null) screen = "result" }
    val browserOn = s.remote.features["browser"] != false
    LaunchedEffect(browserOn, screen) { if (!browserOn && screen == "browser") screen = "home" }
    BackHandler(enabled = screen != "home") {
        when {
            screen == "result" -> { vm.clearAnalysis(); screen = lastScreen }
            screen == "browser" && browser.showTabs -> browser.showTabs = false
            screen == "browser" && browser.showHistory -> browser.showHistory = false
            screen == "browser" && browser.started && browser.web.canGoBack() -> browser.web.goBack()
            screen == "browser" && browser.tabs.size > 1 -> browser.closeTab(browser.index)
            else -> screen = "home"
        }
    }

    AppTheme(s.theme) {
        val p = LocalPal.current
        if (showPerm) {
            AlertDialog(
                onDismissRequest = { },
                containerColor = p.surface,
                title = { Text("Permissions") },
                text = {
                    Text(
                        "Motion Downloader needs:\n\n" +
                            "• Notifications – to show download progress and app updates\n" +
                            "• Storage – to save files (only asked on Android 9 and older)\n" +
                            "• Install unknown apps – asked later, only when you install an update"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val perms = mutableListOf<String>()
                        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                        if (Build.VERSION.SDK_INT < 29) perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
                        if (perms.isEmpty()) {
                            prefs.edit().putBoolean("perm_asked", true).apply()
                            showPerm = false
                        } else permLauncher.launch(perms.toTypedArray())
                    }) { Text("Allow") }
                },
            )
        }
        // "New version available" pop-up
        val upd = s.appUpdate
        var showBg by remember { mutableStateOf(!prefs.getBoolean("bg_asked", false)) }
        if (!showPerm && showBg) {
            AlertDialog(
                onDismissRequest = { }, containerColor = p.surface,
                title = { Text("Keep update notifications working") },
                text = {
                    Text(
                        "Some phones (Xiaomi, Redmi, Oppo, Vivo) stop apps in the background, so new-version notifications can be late.\n\n" +
                            "Open the settings and turn ON Autostart for Motion Downloader, and set Battery saver to No restrictions."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        prefs.edit().putBoolean("bg_asked", true).apply(); showBg = false
                        openBackgroundSettings(ctx)
                    }) { Text("Open settings", fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = { prefs.edit().putBoolean("bg_asked", true).apply(); showBg = false }) { Text("Later") } },
            )
        }
        if (!showPerm && upd != null) {
            AlertDialog(
                onDismissRequest = { vm.dismissUpdate() },
                containerColor = p.surface,
                title = { Text("New version available") },
                text = { Text("Motion Downloader v${upd.version} is ready.\nYou have v${BuildConfig.VERSION_NAME}.") },
                confirmButton = {
                    TextButton(onClick = {
                        Updater.startBackground(ctx, upd)
                        Toast.makeText(ctx, "Downloading the update in the background\u2026", Toast.LENGTH_LONG).show()
                        vm.dismissUpdate()
                    }) { Text("Download now", fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = { vm.dismissUpdate() }) { Text("Cancel") } },
            )
        }
        // Update downloaded: "Install now"
        val ready = us.readyVersion
        if (ready != null && upd == null && !showPerm && readyLater != ready) {
            AlertDialog(
                onDismissRequest = { readyLater = ready },
                containerColor = p.surface,
                title = { Text("Update ready") },
                text = { Text("Motion Downloader v$ready has been downloaded. Install it now?") },
                confirmButton = { TextButton(onClick = { Updater.installReady(ctx) }) { Text("Install now", fontWeight = FontWeight.Bold) } },
                dismissButton = { TextButton(onClick = { readyLater = ready }) { Text("Later") } },
            )
        }
        // Owner controls (remote.json): maintenance, forced update, announcement
        val rem = s.remote
        val forced = rem.minVersion.isNotBlank() && Updater.isNewer(rem.minVersion, BuildConfig.VERSION_NAME)
        val ann = rem.announcement
        val noBack = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        if (rem.maintenance != null) {
            AlertDialog(
                onDismissRequest = { }, properties = noBack, containerColor = p.surface,
                title = { Text("Under maintenance") }, text = { Text(rem.maintenance) },
                confirmButton = { TextButton(onClick = { (ctx as? ComponentActivity)?.finishAffinity() }) { Text("Close") } },
            )
        } else if (forced) {
            AlertDialog(
                onDismissRequest = { }, properties = noBack, containerColor = p.surface,
                title = { Text("Update required") },
                text = { Text("This version (v${BuildConfig.VERSION_NAME}) is no longer supported. Please update to continue.") },
                confirmButton = {
                    TextButton(onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/PIOUS22/Motion_Downloader"))) }
                    }) { Text("Download now", fontWeight = FontWeight.Bold) }
                },
            )
        } else if (!showPerm && upd == null && ann != null && ann.id != s.annSeen) {
            AlertDialog(
                onDismissRequest = { vm.dismissAnnouncement(ann.id) }, containerColor = p.surface,
                title = { Text(ann.title) }, text = { Text(ann.message) },
                confirmButton = {
                    TextButton(onClick = {
                        if (ann.url.isNotBlank()) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ann.url))) }
                        vm.dismissAnnouncement(ann.id)
                    }) { Text(if (ann.url.isNotBlank()) "Open" else "OK", fontWeight = FontWeight.Bold) }
                },
                dismissButton = if (ann.url.isNotBlank()) {
                    { TextButton(onClick = { vm.dismissAnnouncement(ann.id) }) { Text("Close") } }
                } else null,
            )
        }
        val wn = whatsNew
        if (wn != null && !showPerm) {
            AlertDialog(
                onDismissRequest = { whatsNew = null }, containerColor = p.surface,
                title = { Text("What's new in v${BuildConfig.VERSION_NAME}") },
                text = { Text(wn) },
                confirmButton = { TextButton(onClick = { whatsNew = null }) { Text("OK", fontWeight = FontWeight.Bold) } },
            )
        }
        Scaffold(
            modifier = Modifier.fillMaxSize().background(p.bg),
            containerColor = Color.Transparent,
            bottomBar = {
                if (screen != "result") NavBar(screen, hist.count { it.status == "active" || it.status == "pending" }, browserOn) { screen = it }
            },
        ) { pad ->
            androidx.compose.animation.Crossfade(targetState = screen, animationSpec = androidx.compose.animation.core.tween(220), label = "screen") { sc ->
            when (sc) {
                "result" -> ResultScreen(pad, vm, s) { queued ->
                    vm.clearAnalysis()
                    if (queued) {
                        Toast.makeText(ctx, "Added to Downloads", Toast.LENGTH_SHORT).show()
                        dlFilter = "all"
                        screen = "downloads"
                    } else screen = lastScreen
                }
                "browser" -> BrowserScreen(pad, browser) { link ->
                    Toast.makeText(ctx, "Analyzing link\u2026", Toast.LENGTH_SHORT).show()
                    vm.setUrl(Urls.normalize(link))
                    vm.analyze()
                }
                "downloads" -> DownloadsScreen(pad, hist, dlFilter) { dlFilter = it }
                "settings" -> SettingsScreen(pad, vm, s)
                else -> HomeScreen(pad, vm, s, hist) { screen = "downloads" }
            }
            }
        }
    }
}

/** Soft curved bands at the top and bottom of the screen (from the design). */
internal fun Modifier.androidxShadow(e: androidx.compose.ui.unit.Dp, s: androidx.compose.ui.graphics.Shape) =
    this.shadow(e, s, clip = false, ambientColor = Color(0x33000000), spotColor = Color(0x33000000))

internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCurves(
    c: Color, topEdge: androidx.compose.ui.unit.Dp = 92.dp, topCenter: androidx.compose.ui.unit.Dp = 120.dp, bottom: Boolean = true,
) {
    val w = size.width; val h = size.height
    val te = topEdge.toPx(); val tc = topCenter.toPx()
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(0f, 0f); lineTo(w, 0f); lineTo(w, te)
        quadraticBezierTo(w / 2, 2 * tc - te, 0f, te); close()
    }, c)
    if (!bottom) return
    val be = 84.dp.toPx(); val bc = 108.dp.toPx()
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(0f, h); lineTo(w, h); lineTo(w, h - be)
        quadraticBezierTo(w / 2, h - (2 * bc - be), 0f, h - be); close()
    }, c)
}

@Composable
private fun NavBar(cur: String, running: Int, browserOn: Boolean, onNav: (String) -> Unit) {
    // floating dark pill; the selected item is a white pill with icon + name
    val pp = LocalPal.current
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().height(60.dp)
                .androidxShadow(10.dp, RoundedCornerShape(30.dp)).clip(RoundedCornerShape(30.dp)).background(pp.surface).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            NavItem("Home", Ic.Home, cur == "home") { onNav("home") }
            if (browserOn) NavItem("Browser", Ic.Globe, cur == "browser") { onNav("browser") }
            NavItem("Downloads", Ic.Download, cur == "downloads", if (running > 0) running.toString() else null) { onNav("downloads") }
            NavItem("Settings", Ic.Settings, cur == "settings") { onNav("settings") }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    label: String, icon: ImageVector, selected: Boolean, badge: String? = null, onClick: () -> Unit,
) {
    // the selected item grows, turns white and shows its name - all animated
    val dk = LocalPal.current.dark
    val selBg = if (dk) Color(0xFFF2F2F5) else Color(0xFF111113)
    val selFg = if (dk) Color(0xFF111113) else Color.White
    val offFg = if (dk) Color(0xFFB5B5C0) else Color(0xFF55555F)
    val w by androidx.compose.animation.core.animateFloatAsState(
        if (selected) 2.4f else 1f, androidx.compose.animation.core.tween(280), label = "w",
    )
    val bg by androidx.compose.animation.animateColorAsState(
        if (selected) selBg else Color.Transparent, androidx.compose.animation.core.tween(280), label = "bg",
    )
    val fg by androidx.compose.animation.animateColorAsState(
        if (selected) selFg else offFg, androidx.compose.animation.core.tween(280), label = "fg",
    )
    Row(
        Modifier.weight(w).fillMaxSize().clip(RoundedCornerShape(22.dp)).background(bg)
            .clickable(role = Role.Tab, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Box {
            Ico(icon, 20.dp, fg)
            if (badge != null && !selected) {
                Text(
                    badge, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(start = 14.dp)
                        .clip(RoundedCornerShape(8.dp)).background(Color(0xFFE9967A)).padding(horizontal = 4.dp),
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = selected,
            enter = androidx.compose.animation.expandHorizontally(androidx.compose.animation.core.tween(280)) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280)),
            exit = androidx.compose.animation.shrinkHorizontally(androidx.compose.animation.core.tween(200)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)),
        ) {
            Text(label, color = selFg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

// ====================================================================== Home

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen(pad: PaddingValues, vm: MainViewModel, s: UiState, hist: List<DlItem>, onSeeAll: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(p.surface), contentAlignment = Alignment.Center) { Logo(20.dp) }
            Column(Modifier.weight(1f)) {
                Text("Hello!", fontSize = 12.sp, color = p.muted)
                Text("Motion Downloader", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text("Download any\nvideo, fast", fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)

        s.appUpdate?.let { u ->
            Card(pad = 16.dp) {
                Text("Update available — v${u.version}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Muted("You have v${BuildConfig.VERSION_NAME}." + if (u.sizeBytes > 0) "  Size: ${fmtSize(u.sizeBytes)}" else "")
                if (u.notes.isNotBlank()) Muted(u.notes.take(200))
                if (s.updateProgress >= 0f) Bar(s.updateProgress.coerceIn(0f, 1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::dismissUpdate, enabled = !s.updateBusy) { Text("Later") }
                    BigButton(
                        if (s.updateReady) "Install" else "Download & install", vm::installUpdate,
                        Modifier.weight(1f), enabled = !s.updateBusy,
                    )
                }
            }
        }
        s.updateMsg?.let { Muted(it) }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(p.tint).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Paste a link", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = p.onTint)
            val ink = if (p.dark) Color.White else Color(0xFF231F20)
            val capsule = if (p.dark) Color.White else Color(0xFF000000)
            val knob = if (p.dark) Color(0xFF000000) else Color.White
            val canGo = !s.analyzing && s.url.isNotBlank()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(p.surface).padding(horizontal = 18.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = s.url, onValueChange = vm::setUrl, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(color = ink, fontSize = 15.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(ink),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (canGo) vm.analyze() }),
                        decorationBox = { inner ->
                            if (s.url.isEmpty()) Text("Paste Link", fontSize = 15.sp, color = p.muted)
                            inner()
                        },
                    )
                }
                Row(
                    Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(capsule).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(knob).clickable(role = Role.Button) {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                            if (!t.isNullOrBlank()) vm.setUrl(Urls.normalize(t))
                        },
                        contentAlignment = Alignment.Center,
                    ) { Ico(Ic.Paste, 20.dp, if (p.dark) Color.White else Color(0xFF231F20)) }
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).clickable(enabled = canGo, role = Role.Button) { vm.analyze() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (s.analyzing) androidx.compose.material3.CircularProgressIndicator(
                            Modifier.size(20.dp), strokeWidth = 2.dp, color = if (p.dark) Color(0xFF231F20) else Color.White,
                        ) else Ico(Ic.Search, 22.dp, (if (p.dark) Color(0xFF231F20) else Color.White).copy(alpha = if (canGo) 1f else 0.45f))
                    }
                }
            }
            if (s.status.isNotBlank() && (s.statusIsError || s.analyzing)) {
                Text(s.status, color = if (s.statusIsError) p.errFg else p.muted, fontSize = 12.sp)
            }
            if (s.engineError != null) Text("Download engine failed to start: ${s.engineError}", color = p.errFg, fontSize = 12.sp)
            else if (!s.engineReady) Muted("Preparing the download engine…")
        }

    }
    // Recent: pinned near the bottom, like the design
    val ink = if (p.dark) Color.White else Color(0xFF231F20)
    val pillBg = if (p.dark) Color.White else Color(0xFF000000)
    val pillFg = if (p.dark) Color(0xFF000000) else Color.White
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Recent", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box(Modifier.height(36.dp).clickable(role = Role.Button, onClick = onSeeAll), contentAlignment = Alignment.CenterEnd) {
                Text("See all", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        val d = hist.firstOrNull()
        val shape = RoundedCornerShape(16.dp)
        if (d == null) {
            Muted("Nothing yet. Downloads you start will appear here.", Modifier.padding(vertical = 8.dp))
        } else {
            val fmt = remember { java.text.SimpleDateFormat("d MMM. h:mma", java.util.Locale.getDefault()) }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.surface).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Thumb(d.thumb, 68.dp, 60.dp, 16.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(d.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        d.label.ifBlank { d.platform } + " . " + fmt.format(java.util.Date(d.time)),
                        fontSize = 10.sp, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (d.status == "done") Pill("Downloaded", p.okBg, p.okFg) else StatusPill(d)
                        Spacer(Modifier.weight(1f))
                        if (d.status == "done") Box(
                            Modifier.height(26.dp).clip(RoundedCornerShape(13.dp)).background(pillBg)
                                .clickable(role = Role.Button) { openDownloaded(ctx, d) }.padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("Play", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pillFg) }
                        Box(
                            Modifier.height(26.dp).clip(RoundedCornerShape(13.dp)).background(p.soft)
                                .clickable(role = Role.Button, onClick = onSeeAll).padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) { Ico(Ic.More, 16.dp, ink) }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun StatusPill(d: DlItem) {
    val p = LocalPal.current
    when (d.status) {
        "done" -> Pill("Done", p.okBg, p.okFg)
        "active" -> Pill("${d.progress.toInt()}%", p.tint, p.onTint)
        "pending" -> Pill("Pending", p.warnBg, p.warnFg)
        "paused" -> Pill("Paused", p.warnBg, p.warnFg)
        "cancelled" -> Pill("Cancelled", p.errBg, p.errFg)
        else -> Pill("Failed", p.errBg, p.errFg)
    }
}

@Composable
private fun Bar(fraction: Float, modifier: Modifier = Modifier) {
    val p = LocalPal.current
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.border)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.primary))
    }
}

// ====================================================================== Link analysis

private fun qualityName(h: Int) = when (h) {
    2160 -> "2160p · 4K"
    1080 -> "1080p · Full HD"
    720 -> "720p · HD"
    480 -> "480p · SD"
    else -> "${h}p"
}

private val AUDIO_CHOICES = listOf(
    6 to "MP3 · 320 kbps", 5 to "MP3 · 192 kbps", 7 to "M4A · AAC", 8 to "Original audio",
)

@Composable
private fun ResultScreen(pad: PaddingValues, vm: MainViewModel, s: UiState, onDone: (queued: Boolean) -> Unit) {
    val p = LocalPal.current
    val a = s.analysis
    if (a == null) { LaunchedEffect(Unit) { onDone(false) }; return }

    val audio = PRESETS[s.presetIndex].isAudio
    val maxH = a.formats.mapNotNull { it.height }.maxOrNull()
    val videoChoices = QUALITY_PRESETS.filter { (h, _) -> maxH == null || h <= maxH }.ifEmpty { QUALITY_PRESETS }

    // keep the selected quality valid for this video (e.g. no 4K when the video has none)
    LaunchedEffect(a, audio) {
        if (!audio && videoChoices.none { it.second == s.presetIndex }) {
            val want = PRESETS[s.presetIndex].height ?: 0
            vm.setPreset((videoChoices.firstOrNull { it.first <= want } ?: videoChoices.last()).second)
        }
    }

    Column(Modifier.fillMaxSize().padding(pad)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(19.dp)).clickable(role = Role.Button) { onDone(false) },
                contentAlignment = Alignment.Center,
            ) { Ico(Ic.Back, 20.dp) }
            Text("Link analysis", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().height(192.dp)) {
                    Thumb(a.thumb, 1000.dp, 192.dp, 20.dp, Modifier.fillMaxWidth())
                    Text(
                        a.platform, color = p.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(13.dp))
                            .background(Color.White).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    val dur = fmtDuration(a.duration)
                    if (dur.isNotEmpty()) Text(
                        dur, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF14162B)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Column {
                    Text(a.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(
                        a.uploader.takeIf { it.isNotBlank() },
                        if (a.kind == "playlist") "Playlist" + (a.playlistCount?.let { " · $it items" } ?: "") else null,
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, fontSize = 12.sp, color = p.muted, modifier = Modifier.padding(top = 4.dp))
                }
            }

            // playlist choices (only shown for playlist links)
            if (s.options.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label("What to download")
                    s.options.forEach { (key, label) ->
                        val sel = s.selectedOption == key
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (sel) p.tint else p.surface)
                                .border(1.5.dp, if (sel) p.primary else p.border, RoundedCornerShape(12.dp))
                                .clickable(role = Role.RadioButton) { vm.selectOption(key) }.padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = sel, onClick = { vm.selectOption(key) })
                            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        }
                    }
                    if (s.selectedOption == "pick") PickPanel(vm, s)
                }
            }

            // Format: Video / Audio
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Format")
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.segBg).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(false to "Video · MP4", true to "Audio · MP3").forEach { (isAudio, text) ->
                        val on = audio == isAudio
                        Box(
                            Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(11.dp))
                                .background(if (on) p.surface else Color.Transparent)
                                .clickable(role = Role.Tab) { vm.setAudio(isAudio) },
                            contentAlignment = Alignment.Center,
                        ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) p.text else p.muted) }
                    }
                }
            }

            // Quality list
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Quality")
                val rows = if (audio) AUDIO_CHOICES else videoChoices.map { (h, idx) -> idx to qualityName(h) }
                rows.forEach { (idx, name) ->
                    val sel = s.presetIndex == idx
                    val size = estimateSize(a, PRESETS[idx])
                    Row(
                        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (sel) p.tint else p.surface)
                            .border(1.5.dp, if (sel) p.primary else p.border, RoundedCornerShape(12.dp))
                            .clickable(role = Role.RadioButton) { vm.setPreset(idx) }.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = sel, onClick = { vm.setPreset(idx) })
                        Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(
                            size?.let { (if (it.second) "" else "≈") + fmtSize(it.first) } ?: "—",
                            fontSize = 13.sp, color = if (sel) p.onTint else p.muted,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                    }
                }
            }

            // Subtitles (video only)
            if (!audio) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Ico(Ic.Subs, 16.dp, p.muted)
                    Label("Subtitles")
                }
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TintChip("Off", !s.subsOn) { vm.setSubs(null) }
                    val shown = mutableListOf("English" to "en.*", "Bangla" to "bn.*", "Hindi" to "hi.*")
                    if (s.subsOn && shown.none { it.second == s.subLang }) {
                        shown += (SUB_LANGS.firstOrNull { it.second == s.subLang }?.first ?: s.subLang) to s.subLang
                    }
                    shown.forEach { (n, code) -> TintChip(n, s.subsOn && s.subLang == code) { vm.setSubs(code) } }
                }
            }
            if (s.status.isNotBlank() && s.statusIsError) Text(s.status, color = p.errFg, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }

        // bottom bar
        Row(
            Modifier.fillMaxWidth().background(p.surface).border(1.dp, p.border).padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Muted("Selected")
                val size = estimateSize(a, PRESETS[s.presetIndex])
                val text = when (s.selectedOption) {
                    "playlist" -> PRESETS[s.presetIndex].shortLabel + " · whole playlist"
                    "pick" -> PRESETS[s.presetIndex].shortLabel + " · ${s.picked.size} selected"
                    else -> PRESETS[s.presetIndex].shortLabel + (size?.let { " · " + (if (it.second) "" else "≈") + fmtSize(it.first) } ?: "")
                }
                Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            BigButton("Download", { onDone(vm.download()) }, icon = Ic.Download)
        }
    }
}

@Composable
private fun PickPanel(vm: MainViewModel, s: UiState) {
    val p = LocalPal.current
    val a = s.analysis ?: return
    if (s.loadingEntries) { Muted("Loading playlist videos…"); return }
    if (a.entries.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(s.entriesError ?: "The video list has not been loaded.", color = p.errFg, fontSize = 12.sp)
            PillButton("Retry", p.surface, p.onTint, p.primary) { vm.loadEntriesIfNeeded() }
        }
        return
    }
    val list = MainViewModel.filtered(a, s.filter)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = s.filter, onValueChange = vm::setFilter, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search videos in this playlist…") }, singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = p.soft, unfocusedContainerColor = p.soft,
                focusedBorderColor = p.primary, unfocusedBorderColor = p.border,
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton("Select all", p.tint, p.onTint) { vm.pickAllFiltered() }
            PillButton("Clear", p.soft, p.text) { vm.clearPicks() }
            Muted("${s.picked.size} selected", Modifier.weight(1f))
        }
        Box(
            Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(12.dp))
                .background(p.surface).border(1.dp, p.border, RoundedCornerShape(12.dp)),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.index }) { e ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.togglePick(e.index) }.padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = e.index in s.picked, onCheckedChange = { vm.togglePick(e.index) })
                        Text("${e.index}. ${e.title}", fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        val d = fmtDuration(e.duration)
                        if (d.isNotEmpty()) Muted(d)
                    }
                }
            }
        }
    }
}

// ====================================================================== Downloads

@Composable
private fun DownloadsScreen(pad: PaddingValues, all: List<DlItem>, filter: String, onFilter: (String) -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val shown = when (filter) {
        "downloading" -> all.filter { it.status == "active" || it.status == "paused" }
        "pending" -> all.filter { it.status == "pending" }
        "done" -> all.filter { it.status == "done" }
        else -> all
    }
    val fmt = remember { java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.getDefault()) }

    Column(Modifier.fillMaxSize().padding(pad)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Downloads", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(19.dp)).clickable(role = Role.Button) { History.clearFinished() },
                contentAlignment = Alignment.Center,
            ) { Ico(Ic.Trash, 18.dp) }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("all" to "All", "downloading" to "Downloading", "pending" to "Pending", "done" to "Done").forEach { (k, l) ->
                FilterPill(l, filter == k) { onFilter(k) }
            }
        }
        if (shown.isEmpty()) {
            Text(
                if (all.isEmpty()) "Nothing here yet. Downloads you start will appear in this list." else "No downloads in this filter.",
                color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shown, key = { it.id }) { d -> DlCard(d, fmt, ctx) }
        }
    }
}

@Composable
private fun DlCard(d: DlItem, fmt: java.text.SimpleDateFormat, ctx: Context) {
    val p = LocalPal.current
    Card {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(d.thumb, 72.dp, 52.dp)
            Column(Modifier.weight(1f)) {
                Text(d.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when (d.status) {
                    "done" -> "${d.label.ifBlank { d.platform }} · ${fmt.format(java.util.Date(d.time))}"
                    else -> "${d.label.ifBlank { "" }}${if (d.label.isNotBlank()) " · " else ""}${d.platform}"
                }
                Muted(sub, Modifier.padding(top = 2.dp), maxLines = 1)
            }
            if (d.status == "active" || d.status == "paused") Pill("${d.progress.toInt()}%", p.tint, p.onTint)
        }
        when (d.status) {
            "active", "paused" -> {
                Bar(d.progress / 100f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Muted(
                        if (d.status == "paused") "Paused · ${d.progress.toInt()}% done" else d.detail.ifBlank { "Starting…" },
                        Modifier.weight(1f), maxLines = 2,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (d.status == "paused") RoundIconButton(Ic.Play, "Resume", p.tint, p.onTint) { DownloadQueue.resume(ctx, d.id) }
                        else RoundIconButton(Ic.Pause, "Pause", p.soft, p.text) { DownloadQueue.pause(ctx, d.id) }
                        RoundIconButton(Ic.Close, "Cancel download", p.errBg, p.errFg) { DownloadQueue.cancel(ctx, d.id) }
                    }
                }
            }
            "pending" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Pill(d.detail.ifBlank { "Pending · in queue" }, p.warnBg, p.warnFg, Ic.Clock)
                }
                PillButton("Cancel", p.errBg, p.errFg) { DownloadQueue.cancel(ctx, d.id) }
            }
            "done" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Pill("Downloaded", p.okBg, p.okFg, Ic.Check) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillButton(if (d.mime == null || d.mime.startsWith("video/") || d.mime.startsWith("audio/")) "Play" else "Open", p.primary, Color.White) { openDownloaded(ctx, d) }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        RoundIconButton(Ic.More, "More options", p.soft, p.text) { menu = true }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Remove from list") }, onClick = { menu = false; History.remove(d.id) })
                        }
                    }
                }
            }
            else -> {
                if (d.status == "failed") Text(d.error ?: "Download failed", color = p.errFg, fontSize = 12.sp, maxLines = 3)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (d.status == "failed") Pill("Failed", p.errBg, p.errFg, Ic.Close) else Pill("Cancelled", p.errBg, p.errFg, Ic.Close)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        PillButton("Retry", p.surface, p.onTint, p.primary) { DownloadQueue.retry(ctx, d.id) }
                        RoundIconButton(Ic.Trash, "Remove from list", p.soft, p.text) { History.remove(d.id) }
                    }
                }
            }
        }
    }
}

private fun openDownloaded(ctx: Context, d: DlItem) {
    val uri = d.uri?.let { android.net.Uri.parse(it) }
    if (uri == null) {
        Toast.makeText(ctx, "File location unknown", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val media = d.mime == null || d.mime.startsWith("video/") || d.mime.startsWith("audio/")
        if (media && AppPrefs.inAppPlayer(ctx)) {
            ctx.startActivity(PlayerActivity.intent(ctx, d.uri!!, d.title, d.mime))
            return
        }
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, d.mime ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    } catch (e: Exception) {
        Toast.makeText(ctx, "Cannot open it (file moved or no player app)", Toast.LENGTH_SHORT).show()
    }
}

// ====================================================================== Settings

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPal.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(title)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).border(1.dp, p.border, RoundedCornerShape(16.dp)),
        ) { content() }
    }
}

@Composable
private fun RowDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(LocalPal.current.border))

@Composable
private fun SettingRow(title: String, value: String, last: Boolean = false, onClick: () -> Unit) {
    val p = LocalPal.current
    Row(
        Modifier.fillMaxWidth().height(56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(value, fontSize = 14.sp, color = p.muted, textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Ico(Ic.Chevron, 15.dp, p.muted)
    }
    if (!last) RowDivider()
}

@Composable
private fun SettingSwitch(title: String, sub: String, checked: Boolean, last: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Muted(sub, Modifier.padding(top = 2.dp))
        }
        Toggle(checked, onChange)
    }
    if (!last) RowDivider()
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<String, T>>, selected: T?, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPal.current
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = p.surface, title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (label, v) ->
                    Row(
                        Modifier.fillMaxWidth().height(48.dp).clickable { onPick(v); onDismiss() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == v, onClick = { onPick(v); onDismiss() })
                        Text(label, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SettingsScreen(pad: PaddingValues, vm: MainViewModel, s: UiState) {
    val p = LocalPal.current
    var dlg by remember { mutableStateOf<String?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setSaveFolder(uri)
    }
    val cookiePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importCookies(uri)
    }

    Column(Modifier.fillMaxSize().padding(pad)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Group("Defaults") {
                SettingRow("Default quality", qualityName(s.defQuality).substringBefore(" ·")) { dlg = "quality" }
                SettingRow("Default format", if (s.defAudio) "MP3" else "MP4") { dlg = "format" }
                SettingRow(
                    "Subtitle language",
                    if (s.defSub == "off") "Off" else SUB_LANGS.firstOrNull { it.second == s.defSub }?.first ?: s.defSub, last = true,
                ) { dlg = "sub" }
            }
            Group("Downloading") {
                SettingSwitch("Wi-Fi only", "Wait for Wi-Fi, don't use mobile data", s.wifiOnly, onChange = vm::setWifiOnly)
                SettingRow("Simultaneous downloads", s.maxParallel.toString()) { dlg = "parallel" }
                SettingRow("Save location", if (s.saveUri == null) "Downloads/MotionDownloader" else s.saveLabel, last = true) { dlg = "save" }
            }
            Group("Share pop-up") {
                SettingSwitch(
                    "Quick download pop-up", "Show options when a link is shared to Motion Downloader",
                    s.quickPopup, last = true, onChange = vm::setQuickPopup,
                )
            }
            Group("Playback") {
                SettingSwitch(
                    "Play in the app player", "Open finished videos and music in Motion Downloader's own player",
                    s.inAppPlayer, last = true, onChange = vm::setInAppPlayer,
                )
            }
            Group("Appearance") {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to "Auto", "light" to "Light", "dark" to "Dark").forEach { (k, l) ->
                        Box(Modifier.weight(1f)) { Box(Modifier.fillMaxWidth()) { TintChip(l, s.theme == k) { vm.setTheme(k) } } }
                    }
                }
            }
            Group("Sign-in (age-restricted or private videos)") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("cookies.txt", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Muted(if (s.cookiesName != null) "Imported" else "Not imported")
                    }
                    if (s.cookiesName != null) PillButton("Remove", p.errBg, p.errFg) { vm.clearCookies() }
                    PillButton("Import", p.tint, p.onTint) { cookiePicker.launch("*/*") }
                }
            }
            Group("Notifications") {
                val bgCtx = LocalContext.current
                SettingRow("Keep update notifications working", "Open settings", last = true) { openBackgroundSettings(bgCtx) }
            }
            Group("About") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("App version", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Muted("v${BuildConfig.VERSION_NAME}")
                    }
                    PillButton(if (s.updateBusy) "Checking…" else "Check for updates", p.tint, p.onTint) { vm.checkAppUpdate(true) }
                }
                RowDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Download engine (yt-dlp)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Muted(if (s.updating) "Updating…" else s.ytdlpVersion.ifBlank { "?" })
                    }
                    PillButton("Update", p.tint, p.onTint) { vm.updateYtdlp() }
                }
            }
            s.updateMsg?.let { Muted(it) }
            Muted(s.status.takeIf { it.startsWith("yt-dlp") } ?: "")
            Spacer(Modifier.height(8.dp))
        }
    }

    when (dlg) {
        "quality" -> ChoiceDialog("Default quality", QUALITY_PRESETS.map { qualityName(it.first) to it.first }, s.defQuality, vm::setDefQuality) { dlg = null }
        "format" -> ChoiceDialog("Default format", listOf("Video · MP4" to false, "Audio · MP3" to true), s.defAudio, vm::setDefAudio) { dlg = null }
        "sub" -> ChoiceDialog("Subtitle language", listOf("Off" to "off") + SUB_LANGS.map { it.first to it.second }, s.defSub, vm::setDefSub) { dlg = null }
        "parallel" -> ChoiceDialog("Simultaneous downloads", listOf("1" to 1, "2" to 2, "3" to 3), s.maxParallel, vm::setMaxParallel) { dlg = null }
        "save" -> ChoiceDialog(
            "Save location",
            listOf("Choose a folder…" to "pick", "Use default (Downloads/MotionDownloader)" to "default"), null,
            { if (it == "pick") folderPicker.launch(null) else vm.resetSaveFolder() },
        ) { dlg = null }
    }
}

/** Opens the phone's Autostart screen (Xiaomi/Redmi) or the battery settings, so update notifications keep working. */
private fun openBackgroundSettings(ctx: Context) {
    val tries = listOf(
        Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName)),
    )
    for (i in tries) { if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return }
}
