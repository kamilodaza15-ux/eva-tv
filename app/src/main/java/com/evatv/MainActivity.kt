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

data class Section(val name: String, val channels: List<Channel>)

@OptIn(UnstableApi::class)
class MainActivity : Activity() {

    private lateinit var playerView: PlayerView
    private lateinit var osd: TextView
    private lateinit var spinner: View
    private lateinit var panel: View
    private lateinit var list: ListView
    private lateinit var sectionTitle: TextView

    private var player: ExoPlayer? = null
    private var sections: List<Section> = emptyList()
    private var sec = 0      // seccion que se esta reproduciendo
    private var browse = 0   // seccion que se ve en el menu
    private var current = -1
    private val handler = Handler(Looper.getMainLooper())
    private val hideOsd = Runnable { osd.visibility = View.GONE }

    private val channels: List<Channel>
        get() = sections.getOrNull(sec)?.channels ?: emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.player)
        osd = findViewById(R.id.osd)
        spinner = findViewById(R.id.spinner)
        panel = findViewById(R.id.panel)
        list = findViewById(R.id.list)
        sectionTitle = findViewById(R.id.sectionTitle)
        list.setOnItemClickListener { _, _, pos, _ ->
            sec = browse
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
            val extra = resources.openRawResource(R.raw.mis_canales)
                .bufferedReader().use { it.readText() }
            val defs = listOf(
                Triple(getString(R.string.sec_colombia), getString(R.string.playlist_url), false),
                Triple(getString(R.string.sec_movies), getString(R.string.playlist_movies_url), false),
                Triple(getString(R.string.sec_movies_es), getString(R.string.playlist_movies_es_url), true)
            )
            val out = ArrayList<Section>()
            val moviesUrls = HashSet<String>()
            for ((i, d) in defs.withIndex()) {
                val cache = File(filesDir, "playlist_$i.m3u")
                var text: String? = null
                try {
                    text = download(d.second)
                    cache.writeText(text)
                } catch (e: Exception) {
                    if (cache.exists()) text = cache.readText()
                }
                var items: List<Channel> = if (text != null) parseM3u(text) else emptyList()
                if (d.third) {
                    items = items.filter {
                        it.group.contains("movie", true) || it.group.contains("pel", true)
                    }.filter { it.url !in moviesUrls }
                }
                if (i == 1) items.forEach { moviesUrls.add(it.url) }
                if (i == 0) items = parseM3u(extra) + items
                if (items.isNotEmpty()) out.add(Section(d.first, items))
            }
            runOnUiThread { if (!isFinishing) onSections(out) }
        }
    }

    private fun download(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 20000
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

    private fun onSections(all: List<Section>) {
        sections = all
        if (all.isEmpty()) {
            showOsd(getString(R.string.no_channels), 8000)
            return
        }
        val last = getSharedPreferences("eva", MODE_PRIVATE).getString("last", null)
        var s = 0
        var idx = 0
        for ((si, sc) in all.withIndex()) {
            val k = sc.channels.indexOfFirst { it.url == last }
            if (k >= 0) {
                s = si
                idx = k
                break
            }
        }
        sec = s
        browse = s
        refreshList()
        play(idx)
    }

    private fun refreshList() {
        val sc = sections.getOrNull(browse) ?: return
        list.adapter = ArrayAdapter(this, R.layout.item_channel, sc.channels.map { it.name })
        sectionTitle.text = if (sections.size > 1) "‹  ${sc.name}  ›" else sc.name
    }

    // ---------- Reproduccion ----------

    private fun play(i: Int) {
        val list = channels
        if (list.isEmpty()) return
        val idx = ((i % list.size) + list.size) % list.size
        current = idx
        val ch = list[idx]
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
        browse = sec
        refreshList()
        panel.visibility = View.VISIBLE
        list.requestFocus()
        list.post { if (current >= 0) list.setSelection(current) }
    }

    private fun hidePanel() {
        panel.visibility = View.GONE
    }

    private fun switchSection(d: Int) {
        if (sections.size < 2) return
        browse = (browse + d + sections.size) % sections.size
        refreshList()
        val sel = if (browse == sec && current >= 0) current else 0
        list.post { list.setSelection(sel) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (panel.visibility == View.VISIBLE) hidePanel() else super.onBackPressed()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        if (panel.visibility == View.VISIBLE) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    switchSection(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    switchSection(1)
                    return true
                }
            }
            return super.dispatchKeyEvent(e)
        }
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
