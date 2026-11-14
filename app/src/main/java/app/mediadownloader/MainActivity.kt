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

/** True when the app was opened from the "Update available" notification. */
private fun activityWantsUpdate(ctx: Context): Boolean =
    (ctx as? ComponentActivity)?.intent?.getBooleanExtra("open_update", false) == true

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        DownloadQueue.watchNetwork(this)
        setContent { App(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
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

    // first-run permissions
    val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var showPerm by remember { mutableStateOf(!prefs.getBoolean("perm_asked", false)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        prefs.edit().putBoolean("perm_asked", true).apply()
        showPerm = false
    }
    LaunchedEffect(Unit) { if (activityWantsUpdate(ctx)) { vm.checkAppUpdate(true); screen = "settings" } }
    LaunchedEffect(s.analysis) { if (s.analysis != null) screen = "result" }
    BackHandler(enabled = screen != "home") {
        if (screen == "result") vm.clearAnalysis()
        screen = "home"
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
        Scaffold(
            containerColor = p.bg,
            bottomBar = {
                if (screen != "result") NavBar(screen, hist.count { it.status == "active" || it.status == "pending" }) { screen = it }
            },
        ) { pad ->
            when (screen) {
                "result" -> ResultScreen(pad, vm, s) { queued ->
                    vm.clearAnalysis()
                    if (queued) {
                        Toast.makeText(ctx, "Added to Downloads", Toast.LENGTH_SHORT).show()
                        dlFilter = "all"
                        screen = "downloads"
                    } else screen = "home"
                }
                "downloads" -> DownloadsScreen(pad, hist, dlFilter) { dlFilter = it }
                "settings" -> SettingsScreen(pad, vm, s)
                else -> HomeScreen(pad, vm, s, hist) { screen = "downloads" }
            }
        }
    }
}

@Composable
private fun NavBar(cur: String, running: Int, onNav: (String) -> Unit) {
    val p = LocalPal.current
    Column(Modifier.background(p.surface).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 12.dp, vertical = 6.dp)) {
            NavItem("Home", Ic.Home, cur == "home") { onNav("home") }
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
            Text("Downloads", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable(role = Role.Button) { History.clearFinished() },
                contentAlignment = Alignment.Center,
            ) { Ico(Ic.Trash, 22.dp) }
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
                color = p.muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 24.dp),
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
                    PillButton("Play", p.primary, Color.White) { openDownloaded(ctx, d) }
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
            Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
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
        "parallel" -> ChoiceDialog("Simultaneous downloads", listOf("1" to 1, "2" to 2, "3" to 3), s.maxParallel, vm::setMaxParallel) { dlg = null }
        "save" -> ChoiceDialog(
            "Save location",
            listOf("Choose a folder…" to "pick", "Use default (Downloads/MotionDownloader)" to "default"), null,
            { if (it == "pick") folderPicker.launch(null) else vm.resetSaveFolder() },
        ) { dlg = null }
    }
}
