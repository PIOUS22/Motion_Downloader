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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
    val selected: Int = 2,            // index into PRESETS: 720p
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

    fun select(i: Int) = _s.update { it.copy(selected = i) }
}

private data class Opt(val preset: Int, val title: String, val music: Boolean)

private val OPTIONS = listOf(
    Opt(7, "Fast (M4A)", true),
    Opt(5, "Classic MP3", true),
    Opt(3, "Fast (480p)", false),
    Opt(2, "High quality (720p)", false),
)

private val Yellow = Color(0xFFFFCC20)

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
                    onClose = { finish() },
                    onMore = { openFullApp() },
                    onDownload = {
                        val a = st.analysis ?: return@SharePopup
                        DownloadService.startDirect(applicationContext, a.url, st.selected, a.platform, a.title, a.thumb)
                        Toast.makeText(this, "Download started — see the notification", Toast.LENGTH_LONG).show()
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

@Composable
private fun SharePopup(
    st: ShareState,
    onSelect: (Int) -> Unit,
    onClose: () -> Unit,
    onMore: () -> Unit,
    onDownload: () -> Unit,
) {
    val none = remember0()
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
            .clickable(interactionSource = none, indication = null) { onClose() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().clickable(interactionSource = none, indication = null) { },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp)
                        .clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outline)
                )
                Text("Download", fontSize = 20.sp, fontWeight = FontWeight.Bold)

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
                        Text(st.error, color = Color(0xFFE5484D), fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Close") }
                            OutlinedButton(onClick = onMore, modifier = Modifier.weight(1f)) { Text("Open app") }
                        }
                    }

                    a != null -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
                                if (a.thumb != null) {
                                    AsyncImage(a.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(a.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                val meta = listOfNotNull(a.platform, fmtDuration(a.duration).takeIf { it.isNotBlank() }).joinToString("  ·  ")
                                Text(meta, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Section("Music")
                        OPTIONS.filter { it.music }.forEach { OptionRow(it, a, st.selected == it.preset) { onSelect(it.preset) } }
                        Section("Video")
                        OPTIONS.filter { !it.music }.forEach { OptionRow(it, a, st.selected == it.preset) { onSelect(it.preset) } }

                        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
                        Row(
                            Modifier.fillMaxWidth().clickable { onMore() }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("More formats", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(
                                if (a.playlistUrl != null) "All · Playlist  ›" else "All  ›",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
                            )
                        }
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = Color.Black),
                        ) { Text("Download", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun remember0() = androidx.compose.runtime.remember { MutableInteractionSource() }

@Composable
private fun Section(text: String) =
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun OptionRow(o: Opt, a: Analysis, selected: Boolean, onClick: () -> Unit) {
    val size = estimateSize(a, PRESETS[o.preset])
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).border(1.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(if (o.music) "♫" else "▶", fontSize = 14.sp) }
        Spacer(Modifier.width(14.dp))
        Text(o.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (size != null) {
            Text(
                (if (size.second) "" else "≈") + fmtSize(size.first),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}
