package app.mediadownloader

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ShareState(
    val loading: Boolean = true,
    val error: String? = null,
    val analysis: Analysis? = null,
    val selected: Int = 2,            // index into PRESETS
    val subs: String? = null,         // subtitle language code or null
)

class ShareViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val _s = MutableStateFlow(ShareState())
    val s: StateFlow<ShareState> = _s.asStateFlow()
    private var started = false

    fun start(url: String) {
        if (started) return
        started = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Engine.ensureInit(ctx)
                runCatching { Engine.updateIfStale(ctx) }
                val cookies = File(ctx.filesDir, "cookies.txt").takeIf { it.exists() }?.absolutePath
                val a = Engine(ctx).analyze(url, cookies)
                _s.update { it.copy(loading = false, analysis = a) }
            } catch (e: Throwable) {
                _s.update { it.copy(loading = false, error = cleanError(e)) }
            }
        }
    }

    fun init(ctx: android.content.Context) {
        val sub = AppPrefs.subLang(ctx)
        _s.update { it.copy(selected = AppPrefs.defaultPreset(ctx), subs = sub.takeIf { x -> x != "off" }) }
    }

    fun select(i: Int) = _s.update { it.copy(selected = i) }

    fun cycleSubs() = _s.update {
        val order = listOf<String?>(null, "en.*", "bn.*", "hi.*")
        it.copy(subs = order[(order.indexOf(it.subs) + 1).mod(order.size)])
    }
}

class ShareActivity : ComponentActivity() {
    private val vm: ShareViewModel by viewModels()
    private var url: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        url = Urls.normalize(text)
        if (!url.startsWith("http")) {
            Toast.makeText(this, "No link found in the shared text", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (!AppPrefs.popup(this)) { openFullApp(); return }
        vm.init(this)
        vm.start(url)

        setContent {
            val st by vm.s.collectAsState()
            val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
            LaunchedEffect(Unit) {
                val perms = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) perms += Manifest.permission.POST_NOTIFICATIONS
                if (Build.VERSION.SDK_INT < 29 &&
                    checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                ) perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
                if (perms.isNotEmpty()) permLauncher.launch(perms.toTypedArray())
            }
            // A playlist link needs the full app (pick videos etc.).
            LaunchedEffect(st.analysis) {
                if (st.analysis?.kind == "playlist") openFullApp()
            }

            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            AppTheme(prefs.getString("theme", "auto") ?: "auto") {
                SharePopup(
                    st = st,
                    onSelect = vm::select,
                    onSubs = vm::cycleSubs,
                    onClose = { finish() },
                    onMore = { openFullApp() },
                    onDownload = {
                        val a = st.analysis ?: return@SharePopup
                        DownloadQueue.enqueue(
                            applicationContext,
                            newJob(a, st.selected, if (PRESETS[st.selected].isAudio) null else st.subs, "file", true),
                        )
                        Toast.makeText(this, "Added to Downloads", Toast.LENGTH_LONG).show()
                        finish()
                    },
                )
            }
        }
    }

    private fun openFullApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }
}

private val CHIPS = listOf("480p" to 3, "720p" to 2, "1080p" to 1, "MP3" to 5)

@Composable
private fun SharePopup(
    st: ShareState,
    onSelect: (Int) -> Unit,
    onSubs: () -> Unit,
    onClose: () -> Unit,
    onMore: () -> Unit,
    onDownload: () -> Unit,
) {
    val p = LocalPal.current
    val none = androidx.compose.runtime.remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().background(p.scrim).clickable(interactionSource = none, indication = null) { onClose() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(p.surface)
                .clickable(interactionSource = none, indication = null) { }
                .navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(p.border)
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(p.primary), contentAlignment = Alignment.Center) {
                    Ico(Ic.Download, 16.dp, Color.White)
                }
                Text("Motion Downloader", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (st.analysis != null) Pill("Link analyzed", p.okBg, p.okFg, Ic.Check)
            }

            val a = st.analysis
            when {
                st.loading -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(14.dp))
                    Text("Analyzing link…")
                }

                st.error != null -> {
                    Text(st.error, color = p.errFg, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Close", p.soft, p.text) { onClose() }
                        PillButton("Open app", p.tint, p.onTint) { onMore() }
                    }
                }

                a != null -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            Thumb(a.thumb, 96.dp, 64.dp, 12.dp)
                            val d = fmtDuration(a.duration)
                            if (d.isNotEmpty()) Text(
                                d, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF14162B)).padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (PRESETS[st.selected].isAudio) "Download this audio?" else "Download this video?",
                                fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            )
                            Text(a.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = p.muted)
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Label("Quality")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CHIPS.forEach { (name, idx) ->
                                val on = st.selected == idx
                                Box(
                                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp))
                                        .background(if (on) p.tint else p.surface)
                                        .border(1.5.dp, if (on) p.primary else p.border, RoundedCornerShape(22.dp))
                                        .clickable { onSelect(idx) },
                                    contentAlignment = Alignment.Center,
                                ) { Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) p.onTint else p.text) }
                            }
                        }
                        val size = estimateSize(a, PRESETS[st.selected])
                        Muted(PRESETS[st.selected].shortLabel + (size?.let { " · about " + fmtSize(it.first) } ?: ""))
                    }

                    if (!PRESETS[st.selected].isAudio) Row(
                        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(p.soft)
                            .clickable(onClick = onSubs).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Subtitles", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(
                            when (st.subs) { null -> "Off"; "en.*" -> "English"; "bn.*" -> "Bangla"; "hi.*" -> "Hindi"; else -> st.subs },
                            fontSize = 14.sp, color = p.muted,
                        )
                    }

                    BigButton("Download now", onDownload, Modifier.fillMaxWidth(), icon = Ic.Download)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = onMore) { Text("More options", fontWeight = FontWeight.SemiBold) }
                        TextButton(onClick = onClose) { Text("Cancel", fontWeight = FontWeight.SemiBold, color = p.muted) }
                    }
                }
            }
        }
    }
}
