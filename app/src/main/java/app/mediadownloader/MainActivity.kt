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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.ui.window.DialogProperties

/** True when the app was opened from the "Update available" notification. */
private fun activityWantsUpdate(ctx: Context): Boolean =
    (ctx as? ComponentActivity)?.intent?.getBooleanExtra("open_update", false) == true

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) { super.attachBaseContext(Lang.wrap(base)) }

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
    var screen by rememberSaveable { mutableStateOf("downloads") }
    var dlFilter by rememberSaveable { mutableStateOf("all") }
    var dlTab by rememberSaveable { mutableStateOf("all") }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var lastScreen by rememberSaveable { mutableStateOf("downloads") }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
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
    var showLang by remember { mutableStateOf(!prefs.getBoolean("lang_asked", false)) }
    var showPerm by remember { mutableStateOf(!prefs.getBoolean("perm_asked", false)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        prefs.edit().putBoolean("perm_asked", true).apply()
        showPerm = false
    }
    LaunchedEffect(Unit) { Updater.loadState(ctx) }
    val us by Updater.state.collectAsState()
    var readyLater by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { if (activityWantsUpdate(ctx)) { vm.checkAppUpdate(true); screen = "settings" } }
    LaunchedEffect(s.analysis) { if (s.analysis != null) { showAdd = false; screen = "result" } }
    val browserOn = s.remote.features["browser"] != false
    LaunchedEffect(browserOn, screen) { if (!browserOn && screen == "browser") screen = "downloads" }
    BackHandler(enabled = screen != "downloads" || drawer.isOpen) {
        when {
            drawer.isOpen -> scope.launch { drawer.close() }
            screen == "result" -> { vm.clearAnalysis(); screen = lastScreen }
            screen == "browser" && browser.showTabs -> browser.showTabs = false
            screen == "browser" && browser.showHistory -> browser.showHistory = false
            screen == "browser" && browser.started && browser.web.canGoBack() -> browser.web.goBack()
            screen == "browser" && browser.tabs.size > 1 -> browser.closeTab(browser.index)
            else -> screen = "downloads"
        }
    }

    AppTheme(s.theme) {
        val p = LocalPal.current
        if (showLang) {
            ChoiceDialog(
                "Language", Lang.LIST, Lang.get(ctx),
                { code ->
                    prefs.edit().putBoolean("lang_asked", true).apply()
                    if (code != Lang.get(ctx)) { Lang.set(ctx, code); (ctx as? android.app.Activity)?.recreate() }
                },
            ) { prefs.edit().putBoolean("lang_asked", true).apply(); showLang = false }
        }
        if (showPerm && !showLang) {
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
        fun go(target: String, filter: String = "all") {
            dlFilter = filter
            screen = target
            scope.launch { drawer.close() }
        }
        var showBattery by remember {
            mutableStateOf(
                !prefs.getBoolean("battery_asked", false) && runCatching {
                    !(ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
                }.getOrDefault(false)
            )
        }
        if (!showPerm && !showLang && showBattery) {
            AlertDialog(
                onDismissRequest = { }, containerColor = p.surface,
                title = { Text("Warning") },
                text = { Text("Battery optimization can interrupt running downloads.\n\nOpen the settings and choose: All apps \u2192 Motion Downloader \u2192 Don't optimize.") },
                confirmButton = {
                    TextButton(onClick = {
                        prefs.edit().putBoolean("battery_asked", true).apply(); showBattery = false
                        runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }) { Text("OK", fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = { prefs.edit().putBoolean("battery_asked", true).apply(); showBattery = false }) { Text("Cancel") } },
            )
        }
        if (showAdd) AddLinkDialog(vm, s) { showAdd = false }
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = screen == "downloads" || drawer.isOpen,
            drawerContent = {
                DrawerContent(screen, dlFilter, hist, browserOn, { go("downloads", "all") }, { go("browser") }, { f -> dlTab = "finished"; go("downloads", f) }, { go("settings") },
                    {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Motion Downloader: https://github.com/PIOUS22/Motion_Downloader/releases/latest")
                        runCatching { ctx.startActivity(Intent.createChooser(send, "Share the app")) }
                    },
                    { (ctx as? android.app.Activity)?.finishAffinity() })
            },
        ) {
        Scaffold(containerColor = p.bg) { pad ->
            when (screen) {
                "result" -> ResultScreen(pad, vm, s) { queued ->
                    vm.clearAnalysis()
                    if (queued) {
                        Toast.makeText(ctx, "Added to Downloads", Toast.LENGTH_SHORT).show()
                        dlFilter = "all"
                        dlTab = "downloading"
                        screen = "downloads"
                    } else screen = lastScreen
                }
                "browser" -> BrowserScreen(pad, browser, onExit = { screen = "downloads" }) { link ->
                    Toast.makeText(ctx, "Analyzing link\u2026", Toast.LENGTH_SHORT).show()
                    vm.setUrl(Urls.normalize(link))
                    vm.analyze()
                }
                "settings" -> SettingsScreen(pad, vm, s) { screen = "downloads" }
                else -> DownloadsScreen(
                    pad, hist, dlTab, { dlTab = it }, dlFilter,
                    onMenu = { scope.launch { drawer.open() } },
                    onBrowser = if (browserOn) ({ screen = "browser" }) else null,
                    onAdd = { showAdd = true },
                    onSettings = { screen = "settings" },
                )
            }
        }
        }
    }
}

@Composable
private fun NavBar(cur: String, running: Int, browserOn: Boolean, onNav: (String) -> Unit) {
    val p = LocalPal.current
    Column(Modifier.background(p.surface).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 12.dp, vertical = 6.dp)) {
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
    val p = LocalPal.current
    val c = if (selected) p.primary else p.muted
    Column(
        Modifier.weight(1f).fillMaxSize().clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Ico(icon, 24.dp, c)
            if (badge != null) {
                Text(
                    badge, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(start = 18.dp)
                        .clip(RoundedCornerShape(8.dp)).background(p.primary).padding(horizontal = 5.dp),
                )
            }
        }
        Text(label, color = c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ====================================================================== Home

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen(pad: PaddingValues, vm: MainViewModel, s: UiState, hist: List<DlItem>, onSeeAll: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(p.primary), contentAlignment = Alignment.Center) {
                Ico(Ic.Download, 20.dp, Color.White)
            }
            Text("Motion Downloader", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Paste a video link", fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
            Text("Copy a link from Facebook, YouTube or any video site, then analyze it.", fontSize = 15.sp, color = p.muted)
        }

        s.appUpdate?.let { u ->
            Card(pad = 16.dp) {
                Text("Update available — v${u.version}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
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

        Card(pad = 16.dp, gap = 12.dp) {
            Label("Video link")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = s.url, onValueChange = vm::setUrl, modifier = Modifier.weight(1f),
                    placeholder = { Text("https://…") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = p.soft, unfocusedContainerColor = p.soft,
                        focusedBorderColor = p.primary, unfocusedBorderColor = p.border,
                    ),
                )
                Row(
                    Modifier.height(52.dp).clip(RoundedCornerShape(14.dp)).background(p.tint)
                        .clickable(role = Role.Button) {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                            if (!t.isNullOrBlank()) vm.setUrl(Urls.normalize(t))
                        }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Ico(Ic.Paste, 18.dp, p.onTint)
                    Text("Paste", color = p.onTint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            BigButton(
                if (s.analyzing) "Analyzing…" else "Analyze link", vm::analyze, Modifier.fillMaxWidth(),
                icon = Ic.Link, enabled = !s.analyzing && s.url.isNotBlank(),
            )
            if (s.status.isNotBlank() && (s.statusIsError || s.analyzing)) {
                Text(s.status, color = if (s.statusIsError) p.errFg else p.muted, fontSize = 13.sp)
            }
            if (s.engineError != null) Text("Download engine failed to start: ${s.engineError}", color = p.errFg, fontSize = 13.sp)
            else if (!s.engineReady) Muted("Preparing the download engine (first launch can take a few seconds)…")
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("Works with")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Facebook", "YouTube", "Instagram", "TikTok", "X", "Vimeo").forEach { n ->
                    Box(
                        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(p.surface)
                            .border(1.dp, p.border, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(n, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                }
                Box(
                    Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(p.tint).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Any online video", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.onTint) }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recent", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(Modifier.height(44.dp).clickable(role = Role.Button, onClick = onSeeAll), contentAlignment = Alignment.Center) {
                    Text("See all", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = p.onTint)
                }
            }
            Card(pad = 12.dp, gap = 0.dp) {
                val recent = hist.take(2)
                if (recent.isEmpty()) Muted("Nothing yet. Downloads you start will appear here.", Modifier.padding(vertical = 8.dp))
                recent.forEachIndexed { i, d ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Thumb(d.thumb, 64.dp, 44.dp)
                        Column(Modifier.weight(1f)) {
                            Text(d.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Muted(d.label.ifBlank { d.platform }, maxLines = 1)
                        }
                        StatusPill(d)
                    }
                    if (i < recent.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
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
                Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable(role = Role.Button) { onDone(false) },
                contentAlignment = Alignment.Center,
            ) { Ico(Ic.Back, 24.dp) }
            Text("Link analysis", fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
                    Text(a.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(
                        a.uploader.takeIf { it.isNotBlank() },
                        if (a.kind == "playlist") "Playlist" + (a.playlistCount?.let { " · $it items" } ?: "") else null,
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, fontSize = 13.sp, color = p.muted, modifier = Modifier.padding(top = 4.dp))
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
                            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
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
                        ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (on) p.text else p.muted) }
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
                        Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(
                            size?.let { (if (it.second) "" else "≈") + fmtSize(it.first) } ?: "—",
                            fontSize = 14.sp, color = if (sel) p.onTint else p.muted,
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
            if (s.status.isNotBlank() && s.statusIsError) Text(s.status, color = p.errFg, fontSize = 13.sp)
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
                Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
            Text(s.entriesError ?: "The video list has not been loaded.", color = p.errFg, fontSize = 13.sp)
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
                        Text("${e.index}. ${e.title}", fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        val d = fmtDuration(e.duration)
                        if (d.isNotEmpty()) Muted(d)
                    }
                }
            }
        }
    }
}

// ====================================================================== Downloads

private fun categoryOf(d: DlItem): String {
    val ext = d.title.substringAfterLast('.', "").lowercase()
    val mime = d.mime ?: ""
    return when {
        d.fileUrl == null -> if (d.audio) "Music" else "Video"
        mime.startsWith("video/") || ext in setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "flv") -> "Video"
        mime.startsWith("audio/") || ext in setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus") -> "Music"
        mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic") -> "Photos"
        ext in setOf("srt", "vtt", "ass", "ssa", "sub") -> "Subtitles"
        ext == "apk" -> "Programs"
        ext in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz") -> "Compressed"
        ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub", "csv") || mime.startsWith("text/") -> "Documents"
        else -> "Other"
    }
}

private val CATEGORIES = listOf("Video", "Music", "Photos", "Documents", "Subtitles", "Programs", "Compressed", "Other")

@Composable
private fun DrawerContent(
    screen: String, filter: String, hist: List<DlItem>, browserOn: Boolean,
    onDownloads: () -> Unit, onBrowser: () -> Unit, onCategory: (String) -> Unit, onSettings: () -> Unit,
    onShare: () -> Unit = {}, onExit: () -> Unit = {},
) {
    val p = LocalPal.current
    ModalDrawerSheet(drawerContainerColor = p.surface, modifier = Modifier.width(300.dp)) {
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(p.primary), contentAlignment = Alignment.Center) {
                    Ico(Ic.Download, 24.dp, Color.White)
                }
                Column {
                    Text("Motion Downloader", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Muted("Version ${BuildConfig.VERSION_NAME}")
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
            DrawerRow(Ic.Download, "Downloads", null, screen == "downloads" && filter == "all", onDownloads)
            if (browserOn) DrawerRow(Ic.Globe, "Browser", null, screen == "browser", onBrowser)
            Text("CATEGORIES", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.muted, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
            CATEGORIES.forEach { c ->
                val n = hist.count { it.status == "done" && categoryOf(it) == c }
                DrawerRow(Ic.Chevron, c, n.toString(), screen == "downloads" && filter == c) { onCategory(c) }
            }
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(1.dp).background(p.border))
            DrawerRow(Ic.Settings, "Settings", null, screen == "settings", onSettings)
            DrawerRow(Ic.Link, "Share the app", null, false, onShare)
            DrawerRow(Ic.Close, "Exit", null, false, onExit)
        }
    }
}

@Composable
private fun DrawerRow(icon: ImageVector, label: String, count: String?, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPal.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).height(48.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) p.tint else Color.Transparent).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Ico(icon, 20.dp, if (selected) p.onTint else p.text)
        Text(label, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = if (selected) p.onTint else p.text, modifier = Modifier.weight(1f))
        if (count != null) Text(count, fontSize = 13.sp, color = p.muted)
    }
}

@Composable
private fun AddLinkDialog(vm: MainViewModel, s: UiState, onClose: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        if (s.url.isBlank()) {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()
            if (!t.isNullOrBlank() && (t.startsWith("http://") || t.startsWith("https://"))) {
                vm.setUrl(Urls.normalize(t))
                Toast.makeText(ctx, "Pasted from your clipboard", Toast.LENGTH_SHORT).show()
            }
        }
    }
    AlertDialog(
        onDismissRequest = onClose, containerColor = p.surface,
        title = { Text("Add link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = s.url, onValueChange = vm::setUrl, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://\u2026") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = p.soft, unfocusedContainerColor = p.soft,
                        focusedBorderColor = p.primary, unfocusedBorderColor = p.border,
                    ),
                )
                PillButton("Paste from clipboard", p.tint, p.onTint) {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                    if (!t.isNullOrBlank()) vm.setUrl(Urls.normalize(t))
                }
                if (s.status.isNotBlank() && (s.statusIsError || s.analyzing)) {
                    Text(s.status, color = if (s.statusIsError) p.errFg else p.muted, fontSize = 13.sp)
                }
                if (s.engineError != null) Text("Download engine failed to start: ${s.engineError}", color = p.errFg, fontSize = 13.sp)
                else if (!s.engineReady) Muted("Preparing the download engine\u2026")
            }
        },
        confirmButton = {
            TextButton(onClick = vm::analyze, enabled = !s.analyzing && s.url.isNotBlank()) {
                Text(if (s.analyzing) "Analyzing\u2026" else "Analyze", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun IdmRow(d: DlItem, ctx: Context, fmt: java.text.SimpleDateFormat) {
    var menu by remember { mutableStateOf(false) }
    val running = d.status == "active" || d.status == "paused" || d.status == "pending"
    val icon = when (categoryOf(d)) { "Video", "Music" -> Ic.DReel; "Programs" -> Ic.DPhone; "Photos" -> Ic.Image; else -> Ic.FileIc }
    Box(
        Modifier.fillMaxWidth().background(if (running) Color.White else Color(0xFFE4E4E4))
            .clickable {
                when (d.status) {
                    "active" -> DownloadQueue.pause(ctx, d.id)
                    "paused" -> DownloadQueue.resume(ctx, d.id)
                    "done" -> openDownloaded(ctx, d)
                    "failed", "cancelled" -> DownloadQueue.retry(ctx, d.id)
                }
            },
    ) {
        if (d.status == "active" || d.status == "paused") {
            Box(Modifier.matchParentSize()) {
                Box(Modifier.fillMaxWidth((d.progress / 100f).coerceIn(0f, 1f)).fillMaxHeight().background(Idm.Tint))
            }
        }
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Ico(icon, 28.dp, Idm.Grey)
            Column(Modifier.weight(1f)) {
                Row {
                    Text(d.title, fontSize = 14.sp, color = Idm.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (d.fileUrl != null) {
                        Text(" (Resume: ", fontSize = 14.sp, color = Idm.Text, maxLines = 1)
                        Text("Yes", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Idm.Olive, maxLines = 1)
                        Text(")", fontSize = 14.sp, color = Idm.Text, maxLines = 1)
                    }
                }
                val left = when (d.status) {
                    "done" -> "100% | ${d.label.ifBlank { d.platform }}"
                    "failed" -> d.error ?: "Download failed"
                    "cancelled" -> "Cancelled"
                    "pending" -> d.detail.ifBlank { "Pending \u00B7 in queue" }
                    "paused" -> "${d.progress.toInt()}% | Paused"
                    else -> "${d.progress.toInt()}% | ${d.detail.ifBlank { "Starting\u2026" }}"
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(left, fontSize = 12.sp, color = if (d.status == "failed") Color(0xFFBF2433) else Idm.Grey, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (d.status == "done") Text("Completed  ${fmt.format(java.util.Date(d.time))}", fontSize = 12.sp, color = Idm.Grey, maxLines = 1)
                }
            }
            when (d.status) {
                "active" -> Box(Modifier.size(30.dp).clickable { DownloadQueue.pause(ctx, d.id) }, contentAlignment = Alignment.Center) { Ico(Ic.DPauseCircle, 30.dp, Idm.Grey) }
                "paused", "pending" -> Box(Modifier.size(30.dp).clickable { if (d.status == "paused") DownloadQueue.resume(ctx, d.id) else DownloadQueue.cancel(ctx, d.id) }, contentAlignment = Alignment.Center) {
                    Ico(if (d.status == "paused") Ic.DPlayCircle else Ic.Close, 30.dp, Idm.Grey)
                }
                else -> Box {
                    Box(Modifier.size(30.dp).clickable { menu = true }, contentAlignment = Alignment.Center) { Ico(Ic.DDots, 22.dp, Idm.Grey) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(Color.White)) {
                        if (d.status == "done") DropdownMenuItem(text = { Text("Open", color = Idm.Text) }, onClick = { menu = false; openDownloaded(ctx, d) })
                        else DropdownMenuItem(text = { Text("Retry", color = Idm.Text) }, onClick = { menu = false; DownloadQueue.retry(ctx, d.id) })
                        DropdownMenuItem(text = { Text("Remove from list", color = Idm.Text) }, onClick = { menu = false; History.remove(d.id) })
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(Color(0xFFD0D0D0)))
    }
}

@Composable
private fun BatchDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var link by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf("") }
    var letters by remember { mutableStateOf(false) }
    var from by remember { mutableStateOf("0") }
    var to by remember { mutableStateOf("100") }
    var digits by remember { mutableStateOf("1") }
    var lFrom by remember { mutableStateOf("a") }
    var lTo by remember { mutableStateOf("z") }
    var wifi by remember { mutableStateOf(AppPrefs.wifiOnly(ctx)) }
    var retry by remember { mutableStateOf(true) }
    var dup by remember { mutableStateOf(false) }
    var adv by remember { mutableStateOf(false) }
    var speedKb by remember { mutableStateOf(0) }
    var parts by remember { mutableStateOf(8) }
    val where = if (AppPrefs.p(ctx).getString("save_uri", null) != null) "Your chosen folder / Files" else "Downloads/MotionDownloader/Files"
    fun values(): List<String> =
        if (letters) {
            val a = lFrom.firstOrNull() ?: 'a'
            val z = lTo.firstOrNull() ?: a
            (a..z).map { it.toString() }.take(500)
        } else {
            val a = from.toIntOrNull() ?: 0
            val z = (to.toIntOrNull() ?: a).coerceAtMost(a + 499)
            val dg = digits.toIntOrNull()?.coerceIn(1, 8) ?: 1
            (a..z).map { it.toString().padStart(dg, '0') }
        }
    fun go(start: Boolean) {
        if (!link.contains('*')) { Toast.makeText(ctx, "Add a * in the link where the number goes", Toast.LENGTH_LONG).show(); return }
        val existing = History.items.value.mapNotNull { it.fileUrl }.toSet()
        var n = 0
        values().forEach { v ->
            val url = link.trim().replace("*", v)
            if (dup && url in existing) return@forEach
            val name = url.substringBefore('?').substringAfterLast('/').ifBlank { "file$v" }
            DownloadQueue.enqueue(ctx, newFileJob(url, name, -1L, null, null, wifi, retry, if (adv) speedKb else 0, if (adv) parts else 1, ref.trim().ifBlank { null }), start)
            n++
        }
        Toast.makeText(ctx, "$n links added", Toast.LENGTH_SHORT).show()
        onClose()
    }
    IdmDialog(top = 60.dp, onDismiss = onClose) {
        IdmTitle("Batch download!")
        Text("Use the asterisk(*) wildcard for the file name pattern. For example: http://www.domain.com/images/img*.jpg", fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.padding(top = 12.dp))
        Box(Modifier.padding(top = 14.dp)) { IdmField(link, { link = it }, "Download link") }
        IdmField(ref, { ref = it }, "Download/Referrer page link\nleave empty if not sure", Modifier.padding(top = 8.dp), 13)
        Row(Modifier.fillMaxWidth().padding(top = 16.dp).clickable { letters = false }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (!letters) "\u25C9" else "\u25CB", fontSize = 14.sp, color = Color(0xFF666666))
            Text("From:", fontSize = 13.sp, color = Color(0xFF666666))
            Box(Modifier.width(48.dp)) { IdmField(from, { from = it.filter(Char::isDigit) }, "0", size = 13) }
            Text("To:", fontSize = 13.sp, color = Color(0xFF666666))
            Box(Modifier.width(48.dp)) { IdmField(to, { to = it.filter(Char::isDigit) }, "100", size = 13) }
            Text("Digits:", fontSize = 13.sp, color = Color(0xFF666666))
            Box(Modifier.width(32.dp)) { IdmField(digits, { digits = it.filter(Char::isDigit).take(1) }, "1", size = 13) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp).clickable { letters = true }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (letters) "\u25C9" else "\u25CB", fontSize = 14.sp, color = Color(0xFF666666))
            Text("From:", fontSize = 13.sp, color = Color(0xFF666666))
            Box(Modifier.width(32.dp)) { IdmField(lFrom, { lFrom = it.take(1) }, "a", size = 13) }
            Text("To:", fontSize = 13.sp, color = Color(0xFF666666))
            Box(Modifier.width(32.dp)) { IdmField(lTo, { lTo = it.take(1) }, "z", size = 13) }
            Text("case sensitive", fontSize = 13.sp, color = Color(0xFF999999))
        }
        Box(Modifier.padding(top = 8.dp)) { StorageText() }
        Text(where, fontSize = 14.sp, color = Idm.Text, modifier = Modifier.padding(top = 8.dp))
        Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(1.dp).background(Color(0xFFCCCCCC)))
        Box(Modifier.fillMaxWidth().padding(top = 12.dp).border(1.dp, Color(0xFFDDDDDD)).padding(10.dp)) {
            val preview = if (link.contains('*')) values().firstOrNull()?.let { link.replace("*", it) } ?: "" else ""
            Text(if (link.contains('*')) "${values().size} links  \u2192  $preview" else "No wildcard(*) character in download link", fontSize = 13.sp, color = Color(0xFF777777))
        }
        Column(Modifier.padding(top = 4.dp)) {
            IdmCheck("Discard duplicate links", dup) { dup = it }
            IdmCheck("Wifi only", wifi, Idm.Orange) { wifi = it }
            IdmCheck("Retry", retry) { retry = it }
            IdmCheck("Advance option", adv) { adv = it }
        }
        if (adv) {
            Text("Number of parts: $parts", fontSize = 13.sp, color = Idm.Grey)
            IdmSlider(parts.toFloat(), { parts = it.toInt().coerceIn(1, 32) }, 1f..32f, 30)
            Text("Download speed limit: " + (if (speedKb == 0) "MAX" else "$speedKb KB/s"), fontSize = 13.sp, color = Idm.Grey)
            IdmSlider(if (speedKb == 0) 2048f else speedKb.toFloat(), { speedKb = if (it >= 2040f) 0 else (it / 64).toInt().coerceAtLeast(1) * 64 }, 64f..2048f, 0)
        }
        IdmButtons("ADD", { go(false) }, onClose) { go(true) }
    }
}

@Composable
private fun DownloadsScreen(
    pad: PaddingValues, all: List<DlItem>, tab: String, onTab: (String) -> Unit, category: String,
    onMenu: () -> Unit, onBrowser: (() -> Unit)?, onAdd: () -> Unit, onSettings: () -> Unit = {},
) {
    val ctx = LocalContext.current
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("new") }
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf(false) }
    fun match(d: DlItem) = (query.isBlank() || d.title.contains(query, ignoreCase = true)) && (category == "all" || categoryOf(d) == category)
    val running = all.filter { (it.status == "active" || it.status == "pending" || it.status == "paused") && match(it) }
    val finished = all.filter { it.status == "done" && match(it) }
    val errors = all.filter { (it.status == "failed" || it.status == "cancelled") && match(it) }
    val everything = running + finished + errors
    val shownRaw = when (tab) { "downloading" -> running; "finished" -> finished; "error" -> errors; else -> everything }
    val shown = when (sort) {
        "old" -> shownRaw.sortedBy { it.time }
        "name" -> shownRaw.sortedBy { it.title.lowercase() }
        else -> shownRaw.sortedByDescending { it.time }
    }
    val fmt = remember { java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.getDefault()) }
    if (batch) BatchDialog { batch = false }

    Box(Modifier.fillMaxSize().background(Color.White).padding(bottom = pad.calculateBottomPadding())) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().background(Idm.Purple).statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.clickable(role = Role.Button, onClick = onMenu)) { Ico(Ic.DMenu, 24.dp, Color.White) }
                    if (searching) {
                        BasicTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, color = Color.White),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                            decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search downloads", color = Idm.Tab, fontSize = 17.sp); inner() } },
                        )
                    } else {
                        Text(
                            if (category == "all") "Motion Downloader" else category, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                            color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                    }
                    Box(Modifier.clickable(role = Role.Button) { searching = !searching; if (!searching) query = "" }) { Ico(if (searching) Ic.Close else Ic.DSearch, 22.dp, Color.White) }
                    Box {
                        Box(Modifier.clickable(role = Role.Button) { sortMenu = true }) { Ico(Ic.DSort, 22.dp, Color.White) }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }, modifier = Modifier.background(Color.White)) {
                            listOf("new" to "Newest first", "old" to "Oldest first", "name" to "Name (A\u2013Z)").forEach { (k, l) ->
                                DropdownMenuItem(text = { Text(l, color = Idm.Text, fontWeight = if (sort == k) FontWeight.Bold else FontWeight.Normal) }, onClick = { sort = k; sortMenu = false })
                            }
                        }
                    }
                    if (onBrowser != null) Box(Modifier.clickable(role = Role.Button, onClick = onBrowser)) { Ico(Ic.DGlobe, 22.dp, Color.White) }
                    Box(Modifier.clickable(role = Role.Button) { batch = true }) { Ico(Ic.DMagnet, 22.dp, Color.White) }
                    Box {
                        Box(Modifier.clickable(role = Role.Button) { moreMenu = true }) { Ico(Ic.DDots, 22.dp, Color.White) }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }, modifier = Modifier.background(Color.White)) {
                            DropdownMenuItem(text = { Text("Batch download", color = Idm.Text) }, onClick = { moreMenu = false; batch = true })
                            DropdownMenuItem(text = { Text("Clear finished", color = Idm.Text) }, onClick = { moreMenu = false; History.clearFinished() })
                            DropdownMenuItem(text = { Text("Settings", color = Idm.Text) }, onClick = { moreMenu = false; onSettings() })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    listOf(
                        Triple("all", "ALL", everything.size), Triple("downloading", "DOWNLOADING", running.size),
                        Triple("finished", "FINISHED", finished.size), Triple("error", "ERROR", errors.size),
                    ).forEach { (k, l, n) ->
                        val sel = tab == k
                        Column(Modifier.clickable(role = Role.Tab) { onTab(k) }) {
                            Text(
                                "${tr(l.lowercase().replaceFirstChar { it.uppercase() }).uppercase()} ($n)", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                color = if (sel) Color.White else Idm.Tab, maxLines = 1, modifier = Modifier.padding(vertical = 12.dp),
                            )
                            Box(Modifier.fillMaxWidth().height(3.dp).background(if (sel) Idm.Accent else Color.Transparent))
                        }
                    }
                }
            }
            if (shown.isEmpty()) {
                Text(
                    if (query.isNotBlank()) "No matches." else "Nothing here yet. Tap + to add a link.",
                    color = Idm.Grey, fontSize = 14.sp, modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(shown, key = { it.id }) { d -> IdmRow(d, ctx, fmt) }
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(20.dp).size(56.dp).shadow(4.dp, CircleShape).clip(CircleShape)
                .background(Idm.Purple2).clickable(role = Role.Button, onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) { Ico(Ic.DPlus, 28.dp, Color.White) }
    }
}

@Composable
private fun DlCard(d: DlItem, fmt: java.text.SimpleDateFormat, ctx: Context) {
    val p = LocalPal.current
    Card {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(d.thumb, 72.dp, 52.dp)
            Column(Modifier.weight(1f)) {
                Text(d.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(value, fontSize = 15.sp, color = p.muted, textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Ico(Ic.Chevron, 18.dp, p.muted)
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
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
                        Text(label, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SettingsScreen(pad: PaddingValues, vm: MainViewModel, s: UiState, onBack: () -> Unit = {}) {
    val p = LocalPal.current
    var dlg by remember { mutableStateOf<String?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setSaveFolder(uri)
    }
    val cookiePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importCookies(uri)
    }
    val sctx = LocalContext.current
    var rev by remember { mutableIntStateOf(0) }
    val speed = remember(rev) { AppPrefs.speedLimit(sctx) }
    val battery = remember(rev) { AppPrefs.batteryStop(sctx) }
    val removeFin = remember(rev) { AppPrefs.removeFinished(sctx) }
    val notifyOn = remember(rev) { AppPrefs.notifications(sctx) }
    fun putInt(k: String, v: Int) { AppPrefs.p(sctx).edit().putInt(k, v).apply(); rev++ }
    fun putBool(k: String, v: Boolean) { AppPrefs.p(sctx).edit().putBoolean(k, v).apply(); rev++ }

    Column(Modifier.fillMaxSize().padding(pad)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable(role = Role.Button, onClick = onBack), contentAlignment = Alignment.Center) { Ico(Ic.Back, 22.dp) }
            Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
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
                SettingRow("Speed limit", if (speed == 0) "Off" else if (speed >= 1024) "${speed / 1024} MB/s" else "$speed KB/s") { dlg = "speed" }
                SettingRow("Stop downloads if battery below", if (battery == 0) "Off" else "$battery%") { dlg = "battery" }
                SettingSwitch("Remove finished from list", "Files are not deleted", removeFin, onChange = { putBool("remove_finished", it) })
                SettingRow("Save location", if (s.saveUri == null) "Downloads/MotionDownloader" else s.saveLabel, last = true) { dlg = "save" }
            }
            Group("Notifications") {
                SettingSwitch("Finished / failed notifications", "Show a notification when a download ends", notifyOn, last = true, onChange = { putBool("notify_done", it) })
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
                SettingRow("Language", Lang.LIST.firstOrNull { it.second == Lang.get(sctx) }?.first ?: "English", last = false) { dlg = "lang" }
                RowDivider()
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
                        Text("cookies.txt", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Muted(if (s.cookiesName != null) "Imported" else "Not imported")
                    }
                    if (s.cookiesName != null) PillButton("Remove", p.errBg, p.errFg) { vm.clearCookies() }
                    PillButton("Import", p.tint, p.onTint) { cookiePicker.launch("*/*") }
                }
            }
            Group("About") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("App version", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
                        Text("Download engine (yt-dlp)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
        "lang" -> ChoiceDialog("Language", Lang.LIST, Lang.get(sctx), { c -> Lang.set(sctx, c); (sctx as? android.app.Activity)?.recreate() }) { dlg = null }
        "speed" -> ChoiceDialog(
            "Speed limit",
            listOf("Off (maximum)" to 0, "256 KB/s" to 256, "512 KB/s" to 512, "1 MB/s" to 1024, "2 MB/s" to 2048, "5 MB/s" to 5120),
            speed, { putInt("speed_kb", it) }
        ) { dlg = null }
        "battery" -> ChoiceDialog(
            "Stop downloads if battery below",
            listOf("Off" to 0, "10%" to 10, "15%" to 15, "20%" to 20, "30%" to 30, "50%" to 50),
            battery, { putInt("battery_stop", it); DownloadQueue.pump(sctx) }
        ) { dlg = null }
        "parallel" -> ChoiceDialog("Simultaneous downloads", listOf("1" to 1, "2" to 2, "3" to 3), s.maxParallel, vm::setMaxParallel) { dlg = null }
        "save" -> ChoiceDialog(
            "Save location",
            listOf("Choose a folder…" to "pick", "Use default (Downloads/MotionDownloader)" to "default"), null,
            { if (it == "pick") folderPicker.launch(null) else vm.resetSaveFolder() },
        ) { dlg = null }
    }
}
