package app.mediadownloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** True when the app was opened from the "Update available" notification. */
private fun activityWantsUpdate(ctx: Context): Boolean =
    (ctx as? ComponentActivity)?.intent?.getBooleanExtra("open_update", false) == true

private val Accent = Color(0xFF4F8CFF)
private val ErrorRed = Color(0xFFE5484D)
private val OkGreen = Color(0xFF2FB26B)

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
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
            // If the engine is still starting, the ViewModel queues the analysis.
            vm.analyze()
        }
    }
}

@Composable
internal fun AppTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val scheme = if (dark) darkColorScheme(
        primary = Accent, onPrimary = Color.White,
        background = Color(0xFF1E1F24), onBackground = Color(0xFFE8E9EE),
        surface = Color(0xFF272932), onSurface = Color(0xFFE8E9EE),
        surfaceVariant = Color(0xFF1B1C21), onSurfaceVariant = Color(0xFF9AA0AE),
        outline = Color(0xFF3A3D4A), error = ErrorRed,
    ) else lightColorScheme(
        primary = Color(0xFF2F6DF6), onPrimary = Color.White,
        background = Color(0xFFF4F5F8), onBackground = Color(0xFF1F2430),
        surface = Color.White, onSurface = Color(0xFF1F2430),
        surfaceVariant = Color(0xFFEFF1F5), onSurfaceVariant = Color(0xFF667085),
        outline = Color(0xFFDCDFE6), error = ErrorRed,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun Section(text: String) =
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier = modifier, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun <T> Dropdown(
    label: String,
    items: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val current = items.firstOrNull { it.second == selected }?.first ?: ""
    Column(modifier) {
        Muted(label)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(current, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                items.forEach { (text, value) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); open = false })
                }
            }
        }
    }
}

@Composable
private fun App(vm: MainViewModel) {
    val s by vm.ui.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var showPerm by remember { mutableStateOf(!prefs.getBoolean("perm_asked", false)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        prefs.edit().putBoolean("perm_asked", true).apply()
        showPerm = false
    }
    if (showPerm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { },
            title = { Text("Permissions") },
            text = {
                Text(
                    "Media Downloader needs:\n\n" +
                        "\u2022 Notifications \u2013 to show download progress and app updates\n" +
                        "\u2022 Storage \u2013 to save files (only asked on Android 9 and older)\n" +
                        "\u2022 Install unknown apps \u2013 asked later, only when you install an update"
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
    androidx.compose.runtime.LaunchedEffect(Unit) { if (activityWantsUpdate(ctx)) vm.checkAppUpdate(true) }
    val cookiePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importCookies(uri)
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setSaveFolder(uri)
    }

    AppTheme(s.theme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (s.downloading) {
                            OutlinedButton(onClick = vm::cancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                        } else {
                            Button(
                                onClick = vm::download,
                                enabled = s.analysis != null && !s.analyzing && !s.updating,
                                modifier = Modifier.weight(1f).height(48.dp),
                            ) { Text("Download", fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            },
        ) { pad ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Media Downloader", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Muted("Video & audio from YouTube, Vimeo, SoundCloud, X and 1000+ more sites")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Muted("Theme")
                    listOf("Auto" to "auto", "Light" to "light", "Dark" to "dark").forEach { (l, k) ->
                        FilterChip(selected = s.theme == k, onClick = { vm.setTheme(k) }, label = { Text(l) })
                    }
                }

                s.appUpdate?.let { u ->
                    Card {
                        Section("Update available \u2014 v${u.version}")
                        Muted("You have v${BuildConfig.VERSION_NAME}." + if (u.sizeBytes > 0) "  Size: ${fmtSize(u.sizeBytes)}" else "")
                        if (u.notes.isNotBlank()) Muted(u.notes.take(200))
                        if (s.updateProgress >= 0f) {
                            LinearProgressIndicator(
                                progress = { s.updateProgress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(onClick = vm::dismissUpdate, enabled = !s.updateBusy) { Text("Later") }
                            Button(onClick = vm::installUpdate, enabled = !s.updateBusy, modifier = Modifier.weight(1f)) {
                                Text(if (s.updateReady) "Install" else "Download & install")
                            }
                        }
                    }
                }
                s.updateMsg?.let { Muted(it) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Muted("App v${BuildConfig.VERSION_NAME}", Modifier.weight(1f))
                    OutlinedButton(onClick = { vm.checkAppUpdate(true) }, enabled = !s.updateBusy) { Text("Check for updates") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Muted(
                        if (s.updating) "Updating yt-dlp\u2026" else "yt-dlp ${s.ytdlpVersion.ifBlank { "?" }}",
                        Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = vm::updateYtdlp, enabled = !s.updating && !s.downloading) { Text("Update yt-dlp") }
                }

                if (s.engineError != null) {
                    Card { Text("The download engine failed to start:", color = ErrorRed, fontWeight = FontWeight.SemiBold); Text(s.engineError!!) }
                } else if (!s.engineReady) {
                    Card { Muted("Preparing the download engine (first launch can take a few seconds)…") }
                }

                // link card
                Card {
                    Section("Media link")
                    OutlinedTextField(
                        value = s.url,
                        onValueChange = vm::setUrl,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Paste a video, playlist or audio link…") },
                        singleLine = true,
                        enabled = !s.downloading,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                                if (!t.isNullOrBlank()) vm.setUrl(Urls.normalize(t))
                            },
                            enabled = !s.downloading,
                            modifier = Modifier.weight(1f),
                        ) { Text("Paste") }
                        Button(
                            onClick = vm::analyze,
                            enabled = !s.downloading && !s.analyzing && s.url.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) { Text(if (s.analyzing) "Analyzing…" else "Analyze") }
                    }
                    Section("Save to")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(s.saveLabel, modifier = Modifier.weight(1f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (s.saveUri != null) TextButton(onClick = vm::resetSaveFolder, enabled = !s.downloading) { Text("Reset") }
                        OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = !s.downloading) { Text("Browse\u2026") }
                    }
                }

                // result card
                s.analysis?.let { a ->
                    Card {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(
                                Modifier.width(150.dp).height(84.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (a.thumb != null) {
                                    AsyncImage(
                                        model = a.thumb, contentDescription = null,
                                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    a.platform, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(a.platformColor))
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                                Text(a.title, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                val meta = listOfNotNull(
                                    a.uploader.takeIf { it.isNotBlank() },
                                    fmtDuration(a.duration).takeIf { it.isNotBlank() },
                                    if (a.kind == "playlist") "Playlist" + (a.playlistCount?.let { " · $it items" } ?: "") else null,
                                ).joinToString("  ·  ")
                                if (meta.isNotEmpty()) Muted(meta)
                            }
                        }
                    }
                }

                // options card
                Card {
                    Section("Download options")
                    if (s.analysis == null) {
                        Muted("Analyze a link to see what can be downloaded.")
                    } else {
                        s.options.forEach { (key, label) ->
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = !s.downloading) { vm.selectOption(key) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = s.selectedOption == key, onClick = { vm.selectOption(key) }, enabled = !s.downloading)
                                Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
                            }
                        }
                        if (s.selectedOption == "pick") PickPanel(vm, s)
                    }

                    Dropdown(
                        label = "Format & quality",
                        items = PRESETS.mapIndexed { i, p -> p.label to i },
                        selected = s.presetIndex,
                        onSelect = vm::setPreset,
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Download subtitles", modifier = Modifier.weight(1f))
                        Switch(checked = s.subsOn, onCheckedChange = vm::setSubsOn)
                    }
                    if (s.subsOn) {
                        Dropdown("Language", SUB_LANGS, s.subLang, vm::setSubLang)
                        val audio = PRESETS[s.presetIndex].isAudio
                        Dropdown(
                            "Save as",
                            if (audio) SUB_MODES.take(1) else SUB_MODES,
                            if (audio) "file" else s.subMode,
                            vm::setSubMode,
                        )
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.setSubAuto(!s.subAuto) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = s.subAuto, onCheckedChange = vm::setSubAuto)
                            Text("Include auto-generated captions", fontSize = 14.sp)
                        }
                    }

                    // cookies (replacement for "browser cookies" on desktop)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Muted("Sign-in / age-restricted videos")
                            Text(s.cookiesName?.let { "cookies.txt imported" } ?: "No cookies file", fontSize = 13.sp)
                        }
                        if (s.cookiesName != null) TextButton(onClick = vm::clearCookies) { Text("Remove") }
                        OutlinedButton(onClick = { cookiePicker.launch("*/*") }) { Text("Import cookies.txt") }
                    }
                }

                // progress card
                Card {
                    Text(
                        s.status,
                        color = if (s.statusIsError) ErrorRed else MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                    )
                    if (s.downloading || s.progress > 0f) {
                        LinearProgressIndicator(
                            progress = { (s.progress / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = if (!s.downloading && s.progress >= 100f) OkGreen else MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (s.detail.isNotBlank()) Muted(s.detail)
                    if (s.log.isNotEmpty()) {
                        var showLog by remember { mutableStateOf(false) }
                        TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide log" else "Show log") }
                        if (showLog) {
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(
                                    s.log.joinToString("\n"),
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun PickPanel(vm: MainViewModel, s: UiState) {
    val a = s.analysis ?: return
    if (s.loadingEntries) {
        Muted("Loading playlist videos\u2026")
        return
    }
    if (a.entries.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(s.entriesError ?: "The video list has not been loaded.", color = ErrorRed, fontSize = 13.sp)
            OutlinedButton(onClick = vm::loadEntriesIfNeeded) { Text("Retry") }
        }
        return
    }
    val list = MainViewModel.filtered(a, s.filter)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = s.filter, onValueChange = vm::setFilter, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search videos in this playlist…") }, singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = vm::pickAllFiltered) { Text("Select all") }
            OutlinedButton(onClick = vm::clearPicks) { Text("Clear") }
            Muted("${s.picked.size} selected", Modifier.weight(1f))
        }
        Box(
            Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.index }) { e ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !s.downloading) { vm.togglePick(e.index) }.padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = e.index in s.picked, onCheckedChange = { vm.togglePick(e.index) }, enabled = !s.downloading)
                        Column(Modifier.weight(1f)) {
                            Text("${e.index}. ${e.title}", fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        val d = fmtDuration(e.duration)
                        if (d.isNotEmpty()) Muted(d)
                    }
                }
            }
        }
    }
}
