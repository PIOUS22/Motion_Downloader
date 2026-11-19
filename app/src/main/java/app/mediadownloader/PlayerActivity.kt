package app.mediadownloader

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Built-in player for finished downloads (video and audio). Rotate the phone for full screen. */
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var uriText = ""
    private var mime: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uriText = intent.getStringExtra("uri") ?: run { finish(); return }
        mime = intent.getStringExtra("mime")
        val title = intent.getStringExtra("title") ?: ""

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val view = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            if (mime?.startsWith("audio/") == true) controllerShowTimeoutMs = 0   // keep controls visible for music
        }
        val label = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            setPadding(48, 64, 48, 16)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        }
        root.addView(view)
        root.addView(label)
        setContentView(root)

        val p = ExoPlayer.Builder(this).build()
        player = p
        view.player = p
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            label.visibility = if (v == android.view.View.VISIBLE) android.view.View.VISIBLE else android.view.View.GONE
        })
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // The built-in player cannot play this file: hand it to another app.
                Toast.makeText(this@PlayerActivity, "Cannot play it here, opening another player\u2026", Toast.LENGTH_SHORT).show()
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uriText), mime ?: "*/*")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                }
                finish()
            }
        })
        p.setMediaItem(MediaItem.fromUri(Uri.parse(uriText)))
        p.prepare()
        p.playWhenReady = true

        // landscape = full screen
        updateFullscreen()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateFullscreen()
    }

    private fun updateFullscreen() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else c.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        fun intent(ctx: Context, uri: String, title: String, mime: String?) =
            Intent(ctx, PlayerActivity::class.java).putExtra("uri", uri).putExtra("title", title).putExtra("mime", mime)
    }
}
