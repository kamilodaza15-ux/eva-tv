package com.evatv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class Channel(
    val name: String,
    val group: String,
    val url: String,
    val userAgent: String?,
    val referrer: String?
)

@OptIn(UnstableApi::class)
class MainActivity : Activity() {

    private lateinit var playerView: PlayerView
    private lateinit var osd: TextView
    private lateinit var spinner: View
    private lateinit var panel: View
    private lateinit var list: ListView

    private var player: ExoPlayer? = null
    private var channels: List<Channel> = emptyList()
    private var current = -1
    private val handler = Handler(Looper.getMainLooper())
    private val hideOsd = Runnable { osd.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.player)
        osd = findViewById(R.id.osd)
        spinner = findViewById(R.id.spinner)
        panel = findViewById(R.id.panel)
        list = findViewById(R.id.list)
        list.setOnItemClickListener { _, _, pos, _ ->
            play(pos)
            hidePanel()
        }
        loadChannels()
    }

    override fun onStart() {
        super.onStart()
        val p = ExoPlayer.Builder(this).build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                spinner.visibility =
                    if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            }

            override fun onPlayerError(error: PlaybackException) {
                spinner.visibility = View.GONE
                val n = channels.getOrNull(current)?.name ?: ""
                showOsd("Canal no disponible: $n", 5000)
            }
        })
        playerView.player = p
        player = p
        if (current >= 0) play(current)
    }

    override fun onStop() {
        super.onStop()
        playerView.player = null
        player?.release()
        player = null
    }

    override fun onResume() {
        super.onResume()
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    // ---------- Canales ----------

    private fun loadChannels() {
        thread {
            val cache = File(filesDir, "playlist.m3u")
            var text: String? = null
            try {
                text = download(getString(R.string.playlist_url))
                cache.writeText(text)
            } catch (e: Exception) {
                if (cache.exists()) text = cache.readText()
            }
            val extra = resources.openRawResource(R.raw.mis_canales)
                .bufferedReader().use { it.readText() }
            val all = parseM3u(extra) + (if (text != null) parseM3u(text) else emptyList())
            runOnUiThread { if (!isFinishing) onChannels(all) }
        }
    }

    private fun download(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun parseM3u(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        val groupRe = Regex("group-title=\"([^\"]*)\"")
        var name: String? = null
        var group = ""
        var ua: String? = null
        var ref: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF")) {
                name = line.substringAfterLast(",").trim()
                group = groupRe.find(line)?.groupValues?.get(1) ?: ""
                ua = null
                ref = null
            } else if (line.startsWith("#EXTVLCOPT:http-user-agent=")) {
                ua = line.substringAfter("=")
            } else if (line.startsWith("#EXTVLCOPT:http-referrer=")) {
                ref = line.substringAfter("=")
            } else if (line.startsWith("#")) {
                continue
            } else {
                val n = name
                if (n != null) out.add(Channel(n, group, line, ua, ref))
                name = null
                ua = null
                ref = null
            }
        }
        return out
    }

    private fun onChannels(all: List<Channel>) {
        channels = all
        if (all.isEmpty()) {
            showOsd(getString(R.string.no_channels), 8000)
            return
        }
        list.adapter = ArrayAdapter(
            this, R.layout.item_channel, all.map { it.name }
        )
        val last = getSharedPreferences("eva", MODE_PRIVATE).getString("last", null)
        val idx = all.indexOfFirst { it.url == last }
        play(if (idx >= 0) idx else 0)
    }

    // ---------- Reproduccion ----------

    private fun play(i: Int) {
        if (channels.isEmpty()) return
        val idx = ((i % channels.size) + channels.size) % channels.size
        current = idx
        val ch = channels[idx]
        getSharedPreferences("eva", MODE_PRIVATE).edit().putString("last", ch.url).apply()
        showOsd("${idx + 1}  ${ch.name}", 3000)

        val p = player ?: return
        val ds = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        ch.userAgent?.let { ds.setUserAgent(it) }
        ch.referrer?.let { ds.setDefaultRequestProperties(mapOf("Referer" to it)) }
        val source = DefaultMediaSourceFactory(ds).createMediaSource(MediaItem.fromUri(ch.url))
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
    }

    private fun showOsd(msg: String, ms: Long) {
        osd.text = msg
        osd.visibility = View.VISIBLE
        handler.removeCallbacks(hideOsd)
        handler.postDelayed(hideOsd, ms)
    }

    // ---------- Control remoto ----------

    private fun showPanel() {
        panel.visibility = View.VISIBLE
        list.requestFocus()
        list.post { if (current >= 0) list.setSelection(current) }
    }

    private fun hidePanel() {
        panel.visibility = View.GONE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (panel.visibility == View.VISIBLE) hidePanel() else super.onBackPressed()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        if (panel.visibility == View.VISIBLE) return super.dispatchKeyEvent(e)
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MENU -> {
                showPanel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                play(current + 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                play(current - 1)
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }
}
