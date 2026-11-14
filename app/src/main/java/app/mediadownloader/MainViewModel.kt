package app.mediadownloader

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UiState(
    val engineReady: Boolean = false,
    val engineError: String? = null,
    val url: String = "",
    val status: String = "Paste a link and press Analyze to begin.",
    val statusIsError: Boolean = false,
    val analyzing: Boolean = false,
    val analysis: Analysis? = null,
    val options: List<Pair<String, String>> = emptyList(),
    val selectedOption: String? = null,
    val picked: Set<Int> = emptySet(),
    val filter: String = "",
    val presetIndex: Int = 0,
    val subsOn: Boolean = false,
    val subLang: String = "en.*",
    val subMode: String = "file",
    val subAuto: Boolean = true,
    val wifiOnly: Boolean = false,
    val maxParallel: Int = 2,
    val quickPopup: Boolean = true,
    val defQuality: Int = 720,
    val defAudio: Boolean = false,
    val defSub: String = "off",
    val cookiesName: String? = null,
    val theme: String = "auto",
    val saveLabel: String = "Downloads/MotionDownloader",
    val saveUri: String? = null,
    val updating: Boolean = false,
    val loadingEntries: Boolean = false,
    val entriesError: String? = null,
    val ytdlpVersion: String = "",
    val appUpdate: UpdateInfo? = null,
    val updateBusy: Boolean = false,        // checking or downloading
    val updateProgress: Float = -1f,        // 0..1 while downloading
    val updateReady: Boolean = false,       // apk downloaded, waiting for install
    val updateMsg: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context = app.applicationContext
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val engine = Engine(ctx)
    private val cookiesFile get() = File(ctx.filesDir, "cookies.txt")

    private val _ui = MutableStateFlow(
        UiState(
            presetIndex = AppPrefs.defaultPreset(app),
            subsOn = AppPrefs.subLang(app) != "off",
            subLang = AppPrefs.subLang(app).takeIf { it != "off" } ?: "en.*",
            wifiOnly = AppPrefs.wifiOnly(app),
            maxParallel = AppPrefs.maxParallel(app),
            quickPopup = AppPrefs.popup(app),
            defQuality = AppPrefs.quality(app),
            defAudio = AppPrefs.audioFormat(app),
            defSub = AppPrefs.subLang(app),
            theme = prefs.getString("theme", "auto") ?: "auto",
            saveUri = prefs.getString("save_uri", null),
            saveLabel = labelFor(prefs.getString("save_uri", null)),
            cookiesName = if (File(ctx.filesDir, "cookies.txt").exists()) "cookies.txt" else null,
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var job: Job? = null
    private var updateFile: java.io.File? = null
    private var pendingAnalyze = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                engine.init()
                runCatching { Engine.ensureBaseFolder(ctx) }
                // Keep yt-dlp fresh (YouTube breaks old versions): at most every 12 hours.
                val last = prefs.getLong("last_update", 0L)
                if (System.currentTimeMillis() - last > 12 * 3600 * 1000L) {
                    _ui.update { it.copy(updating = true) }
                    val r = engine.updateYtdlp()
                    if (!r.startsWith("update failed")) prefs.edit().putLong("last_update", System.currentTimeMillis()).apply()
                }
                _ui.update { it.copy(engineReady = true, updating = false, ytdlpVersion = engine.ytdlpVersion()) }
                withContext(Dispatchers.Main) { checkAppUpdate() }
                if (pendingAnalyze) {
                    pendingAnalyze = false
                    withContext(Dispatchers.Main) { analyze() }
                }
            } catch (e: Throwable) {
                _ui.update { it.copy(engineError = cleanError(e)) }
            }
        }
    }

    // ---------------------------------------------------------- simple setters

    fun setSaveFolder(uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        prefs.edit().putString("save_uri", uri.toString()).apply()
        _ui.update { it.copy(saveUri = uri.toString(), saveLabel = labelFor(uri.toString())) }
    }

    fun resetSaveFolder() {
        prefs.edit().remove("save_uri").apply()
        _ui.update { it.copy(saveUri = null, saveLabel = labelFor(null)) }
    }

    private fun labelFor(uri: String?): String =
        if (uri == null) "Downloads/MotionDownloader"
        else Uri.decode(uri).substringAfterLast("tree/").replace(':', '/').ifBlank { "Chosen folder" }

    fun updateYtdlp() {
        if (_ui.value.updating) return
        _ui.update { it.copy(updating = true, status = "Updating yt-dlp\u2026", statusIsError = false) }
        viewModelScope.launch(Dispatchers.IO) {
            val r = engine.updateYtdlp()
            if (!r.startsWith("update failed")) prefs.edit().putLong("last_update", System.currentTimeMillis()).apply()
            _ui.update {
                it.copy(updating = false, ytdlpVersion = engine.ytdlpVersion(),
                    status = "yt-dlp: $r", statusIsError = r.startsWith("update failed"))
            }
        }
    }

    // ---------------------------------------------------------------- app update

    fun checkAppUpdate(manual: Boolean = false) {
        if (_ui.value.updateBusy) return
        _ui.update { it.copy(updateBusy = true, updateMsg = if (manual) "Checking for updates\u2026" else null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = Updater.check()
                if (info != null) {
                    Updater.notify(ctx, info)
                    updateFile = Updater.downloadedFile(ctx, info)
                    _ui.update { it.copy(appUpdate = info, updateBusy = false, updateReady = updateFile != null, updateMsg = null) }
                } else {
                    _ui.update {
                        it.copy(updateBusy = false, appUpdate = null,
                            updateMsg = if (manual) "You have the latest version (${BuildConfig.VERSION_NAME})." else null)
                    }
                }
            } catch (e: Throwable) {
                _ui.update {
                    it.copy(updateBusy = false, updateMsg = if (manual) "Could not check for updates: ${e.message?.take(100)}" else null)
                }
            }
        }
    }

    fun installUpdate() {
        val info = _ui.value.appUpdate ?: return
        if (_ui.value.updateBusy) return
        _ui.update { it.copy(updateBusy = true, updateProgress = 0f, updateMsg = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val f = Updater.download(ctx, info) { p -> _ui.update { it.copy(updateProgress = p) } }
                updateFile = f
                val opened = withContext(Dispatchers.Main) { Updater.install(ctx, f) }
                _ui.update {
                    it.copy(updateBusy = false, updateProgress = -1f, updateReady = true,
                        updateMsg = if (opened) null else "Allow \u201CInstall unknown apps\u201D for Motion Downloader, come back and press Install again.")
                }
            } catch (e: Throwable) {
                _ui.update { it.copy(updateBusy = false, updateProgress = -1f, updateMsg = "Update download failed: ${e.message?.take(100)}") }
            }
        }
    }

    fun dismissUpdate() = _ui.update { it.copy(appUpdate = null, updateMsg = null) }

    fun setUrl(v: String) = _ui.update { it.copy(url = v) }
    fun setFilter(v: String) = _ui.update { it.copy(filter = v) }

    fun setTheme(v: String) {
        prefs.edit().putString("theme", v).apply()
        _ui.update { it.copy(theme = v) }
    }

    // choices for the link being downloaded (not saved)
    fun setPreset(i: Int) = _ui.update { it.copy(presetIndex = i) }

    /** Video / Audio switch on the analysis screen. */
    fun setAudio(audio: Boolean) = _ui.update {
        if (audio == PRESETS[it.presetIndex].isAudio) it
        else it.copy(presetIndex = if (audio) 5 else AppPrefs.defaultPreset(ctx).takeIf { i -> !PRESETS[i].isAudio } ?: 2)
    }

    /** null = subtitles off. */
    fun setSubs(lang: String?) = _ui.update {
        if (lang == null) it.copy(subsOn = false) else it.copy(subsOn = true, subLang = lang)
    }

    // ---- Settings screen (saved)
    fun setDefQuality(v: Int) {
        prefs.edit().putInt("def_quality", v).apply()
        _ui.update { it.copy(defQuality = v) }
    }

    fun setDefAudio(v: Boolean) {
        prefs.edit().putBoolean("def_audio", v).apply()
        _ui.update { it.copy(defAudio = v) }
    }

    fun setDefSub(v: String) {
        prefs.edit().putString("def_sub", v).apply()
        _ui.update { it.copy(defSub = v) }
    }

    fun setWifiOnly(v: Boolean) {
        prefs.edit().putBoolean("wifi_only", v).apply()
        _ui.update { it.copy(wifiOnly = v) }
        DownloadQueue.pump(ctx)
    }

    fun setMaxParallel(v: Int) {
        prefs.edit().putInt("max_parallel", v).apply()
        _ui.update { it.copy(maxParallel = v) }
        DownloadQueue.pump(ctx)
    }

    fun setQuickPopup(v: Boolean) {
        prefs.edit().putBoolean("popup_on", v).apply()
        _ui.update { it.copy(quickPopup = v) }
    }

    fun clearAnalysis() = _ui.update {
        it.copy(analysis = null, options = emptyList(), selectedOption = null, picked = emptySet(), filter = "",
            status = "", statusIsError = false)
    }

    fun selectOption(key: String) {
        _ui.update { it.copy(selectedOption = key) }
        if (key == "pick") loadEntriesIfNeeded()
    }

    fun loadEntriesIfNeeded() {
        val s = _ui.value
        val a = s.analysis ?: return
        val purl = a.playlistUrl ?: return
        if (a.entries.isNotEmpty() || s.loadingEntries) return
        _ui.update { it.copy(loadingEntries = true, entriesError = null) }
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { engine.loadEntries(purl, cookiePath()) }
                _ui.update { st ->
                    val an = st.analysis
                    if (an == null) st.copy(loadingEntries = false)
                    else st.copy(
                        loadingEntries = false,
                        entriesError = if (list.isEmpty()) "No videos found in this playlist." else null,
                        analysis = an.copy(entries = list, playlistCount = an.playlistCount ?: list.size.takeIf { it > 0 }),
                    )
                }
            } catch (e: Throwable) {
                _ui.update { it.copy(loadingEntries = false, entriesError = cleanError(e)) }
            }
        }
    }

    fun togglePick(index: Int) = _ui.update {
        it.copy(picked = if (index in it.picked) it.picked - index else it.picked + index)
    }

    fun pickAllFiltered() = _ui.update { s ->
        val a = s.analysis ?: return@update s
        s.copy(picked = s.picked + filtered(a, s.filter).map { it.index })
    }

    fun clearPicks() = _ui.update { it.copy(picked = emptySet()) }

    fun importCookies(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ctx.contentResolver.openInputStream(uri)!!.use { input ->
                    cookiesFile.outputStream().use { input.copyTo(it) }
                }
                _ui.update { it.copy(cookiesName = "cookies.txt") }
            } catch (e: Throwable) {
                _ui.update { it.copy(status = "Could not import cookies: ${e.message}", statusIsError = true) }
            }
        }
    }

    fun clearCookies() {
        cookiesFile.delete()
        _ui.update { it.copy(cookiesName = null) }
    }

    private fun cookiePath(): String? = cookiesFile.takeIf { it.exists() }?.absolutePath

    // ---------------------------------------------------------------- analyse

    fun analyze() {
        val s = _ui.value
        if (s.analyzing || s.updating) return
        val url = Urls.normalize(s.url)
        if (!url.startsWith("http")) {
            _ui.update { it.copy(status = "Please paste a valid link first.", statusIsError = true) }
            return
        }
        if (!s.engineReady) {
            _ui.update { it.copy(status = "The download engine is still starting… try again in a moment.", statusIsError = true) }
            return
        }
        _ui.update {
            it.copy(
                url = url, analyzing = true, analysis = null, options = emptyList(), selectedOption = null,
                picked = emptySet(), filter = "",
                status = "Analyzing link…", statusIsError = false,
            )
        }
        job = viewModelScope.launch {
            try {
                val a = withContext(Dispatchers.IO) { engine.analyze(url, cookiePath()) }
                val opts = buildOptions(a)
                _ui.update {
                    it.copy(
                        analyzing = false, analysis = a, options = opts, selectedOption = opts.first().first,
                        presetIndex = AppPrefs.defaultPreset(ctx),
                        subsOn = AppPrefs.subLang(ctx) != "off",
                        subLang = AppPrefs.subLang(ctx).takeIf { l -> l != "off" } ?: "en.*",
                        status = "", statusIsError = false,
                    )
                }
                if (opts.first().first == "pick") loadEntriesIfNeeded()
            } catch (e: Throwable) {
                _ui.update { it.copy(analyzing = false, status = cleanError(e), statusIsError = true) }
            }
        }
    }

    // --------------------------------------------------------------- download

    /** Adds the job to the queue (Pending -> Downloading). Returns true when it was added. */
    fun download(): Boolean {
        val s = _ui.value
        val a = s.analysis ?: return false
        val opt = s.selectedOption ?: return false
        if (opt == "pick" && s.picked.isEmpty()) {
            _ui.update { it.copy(status = "Tick at least one video from the playlist.", statusIsError = true) }
            return false
        }
        val isPlaylist = opt != "single"
        val items = if (opt == "pick") s.picked.sorted() else emptyList()
        val job = newJob(
            a, s.presetIndex, if (s.subsOn && !PRESETS[s.presetIndex].isAudio) s.subLang else null, s.subMode, s.subAuto, isPlaylist, items,
        )
        DownloadQueue.enqueue(ctx, job)
        return true
    }

    companion object {
        fun filtered(a: Analysis, filter: String): List<Entry> =
            if (filter.isBlank()) a.entries
            else a.entries.filter { it.title.contains(filter.trim(), ignoreCase = true) }
    }
}
