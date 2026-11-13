package com.evatv

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
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
import java.text.Normalizer
import java.util.concurrent.Executors
import kotlin.concurrent.thread

data class Channel(
    val name: String,
    val group: String,
    val url: String,
    val logo: String?,
    val userAgent: String?,
    val referrer: String?,
    val tvgId: String? = null
) {
    /** Nombre sin tildes ni mayusculas, calculado una sola vez (para buscar rapido). */
    val key: String by lazy {
        Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
    }
}

/** Una seccion del menu: nombre y sus listas (url, filtrar solo peliculas). */
data class Src(val name: String, val urls: List<Pair<String, Boolean>>)

/** Carga logos de canales en segundo plano con cache en memoria. */
object Logos {
    private val cache = LruCache<String, Bitmap>(80)
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun load(iv: ImageView, url: String?) {
        iv.tag = url
        iv.setImageDrawable(null)
        if (url.isNullOrBlank()) return
        val c = cache.get(url)
        if (c != null) {
            iv.setImageBitmap(c)
            return
        }
        pool.execute {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 8000
                conn.instanceFollowRedirects = true
                val bytes = try {
                    conn.inputStream.use { it.readBytes() }
                } finally {
                    conn.disconnect()
                }
                val o = BitmapFactory.Options()
                o.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var s = 1
                while (o.outWidth / (s * 2) >= 160) s *= 2
                val o2 = BitmapFactory.Options()
                o2.inSampleSize = s
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
                if (bmp != null) {
                    cache.put(url, bmp)
                    main.post { if (iv.tag == url) iv.setImageBitmap(bmp) }
                }
            } catch (e: Exception) {
                // sin logo, no pasa nada
            }
        }
    }
}

@OptIn(UnstableApi::class)
class MainActivity : Activity() {

    private val base = "https://iptv-org.github.io/iptv/"

    // Secciones del menu izquierdo (despues van Favoritos y Buscar)
    private val sources: List<Src> by lazy {
        listOf(
            Src("Colombia", listOf(Pair(getString(R.string.playlist_url), false))),
            Src("Canales", listOf(Pair(base + "languages/spa.m3u", false))),
            Src("Deportes", listOf(Pair(base + "categories/sports.m3u", false))),
            Src(
                "Películas",
                listOf(Pair(base + "languages/spa.m3u", true), Pair(base + "categories/movies.m3u", false))
            ),
            Src("Series", listOf(Pair(base + "categories/series.m3u", false))),
            Src("Kids", listOf(Pair(base + "categories/kids.m3u", false))),
            Src("Animación", listOf(Pair(base + "categories/animation.m3u", false))),
            Src("Documentales", listOf(Pair(base + "categories/documentary.m3u", false))),
            Src("Noticias", listOf(Pair(base + "categories/news.m3u", false))),
            Src("Música", listOf(Pair(base + "categories/music.m3u", false)))
        )
    }
    // Menu: 0 = Buscar, 1..n = secciones, n+1 = Favoritos
    private val searchIdx get() = 0
    private val favIdx get() = sources.size + 1

    private lateinit var labels: List<String>
    private lateinit var playerView: PlayerView
    private lateinit var osd: TextView
    private lateinit var spinner: View
    private lateinit var menuPane: View
    private lateinit var listPane: View
    private lateinit var centerPane: LinearLayout
    private lateinit var previewBox: View
    private lateinit var nowTitle: TextView
    private lateinit var nowHint: TextView
    private lateinit var listTitle: TextView
    private lateinit var menu: ListView
    private lateinit var list: ListView
    private lateinit var search: EditText

    private var player: ExoPlayer? = null
    private val data = HashMap<Int, List<Channel>>()
    private var fav: MutableList<Channel> = ArrayList()
    private var shown: List<Channel> = emptyList()
    private var menuIndex = 0
    private var playing: Channel? = null
    private var playingList: List<Channel> = emptyList()
    private var playingIdx = 0
    private var fullscreen = false
    private var pendingMenu: Runnable? = null
    private var pendingSearch: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideOsd = Runnable { osd.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.player)
        osd = findViewById(R.id.osd)
        spinner = findViewById(R.id.spinner)
        menuPane = findViewById(R.id.menuPane)
        listPane = findViewById(R.id.listPane)
        centerPane = findViewById(R.id.centerPane)
        previewBox = findViewById(R.id.previewBox)
        nowTitle = findViewById(R.id.nowTitle)
        nowHint = findViewById(R.id.nowHint)
        listTitle = findViewById(R.id.listTitle)
        menu = findViewById(R.id.menu)
        list = findViewById(R.id.list)
        search = findViewById(R.id.search)

        labels = listOf("Buscar") + sources.map { it.name } + listOf("Favoritos")
        menu.adapter = ArrayAdapter(this, R.layout.item_menu, labels)
        menu.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!menu.hasFocus()) return
                menu.setItemChecked(pos, true)
                pendingMenu?.let { handler.removeCallbacks(it) }
                val r = Runnable { showSection(pos) }
                pendingMenu = r
                handler.postDelayed(r, 250)
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        menu.setOnItemClickListener { _, _, pos, _ ->
            pendingMenu?.let { handler.removeCallbacks(it) }
            showSection(pos)
            if (pos == searchIdx) search.requestFocus() else list.requestFocus()
        }

        list.setOnItemClickListener { _, _, pos, _ ->
            val ch = shown.getOrNull(pos) ?: return@setOnItemClickListener
            if (ch.url.isEmpty()) return@setOnItemClickListener
            if (playing?.url == ch.url && player != null) {
                enterFullscreen()
            } else {
                val pl = shown.filter { it.url.isNotEmpty() }
                play(pl, pl.indexOfFirst { it.url == ch.url })
            }
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            shown.getOrNull(pos)?.let { if (it.url.isNotEmpty()) toggleFav(it) }
            true
        }
        previewBox.setOnClickListener { if (playing != null) enterFullscreen() }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                pendingSearch?.let { handler.removeCallbacks(it) }
                val r = Runnable { applyQuery(q) }
                pendingSearch = r
                handler.postDelayed(r, 250)
            }
        })
        search.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            list.requestFocus()
            true
        }

        fav = loadFav()
        showSection(1)
        loadAll()
        list.requestFocus()
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
                showOsd("Canal no disponible: ${playing?.name ?: ""}", 5000)
            }
        })
        playerView.player = p
        player = p
        applyQuality()
        if (playing != null) play(playingList, playingIdx)
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

    // ---------- Carga de listas ----------

    // Paises de habla hispana (codigo al final del tvg-id, ej. "Caracol.co@SD")
    private val spanishCountries = setOf(
        "co", "mx", "ar", "es", "cl", "pe", "ve", "ec", "uy", "bo", "py",
        "cr", "pa", "do", "gt", "sv", "hn", "ni", "cu", "pr"
    )

    /** Fila de titulo dentro de una lista (sin url, no se puede seleccionar). */
    private fun header(t: String) = Channel("—  $t  —", "", "", null, null, null)

    private fun isSpanishId(id: String?): Boolean {
        if (id == null) return false
        val code = id.substringBefore("@").substringAfterLast(".", "").lowercase()
        return code in spanishCountries
    }

    private fun loadAll() {
        thread {
            val spaUrls = HashSet<String>()
            val extra = resources.openRawResource(R.raw.mis_canales)
                .bufferedReader().use { it.readText() }
            for ((i, src) in sources.withIndex()) {
                val all = ArrayList<Channel>()
                if (i == 0) all.addAll(parseM3u(extra))
                for ((j, u) in src.urls.withIndex()) {
                    val cache = File(filesDir, "pl_${i}_$j.m3u")
                    var text: String? = null
                    try {
                        text = download(u.first)
                        cache.writeText(text)
                    } catch (e: Exception) {
                        if (cache.exists()) text = cache.readText()
                    }
                    var items: List<Channel> = if (text != null) parseM3u(text) else emptyList()
                    if (u.second) {
                        items = items.filter {
                            it.group.contains("movie", true) || it.group.contains("pel", true)
                        }
                    }
                    all.addAll(items)
                }
                var res = all.distinctBy { it.url }
                if (i == 1) res.forEach { spaUrls.add(it.url) }
                if (i >= 2) {
                    // solo canales en espanol: de la lista de idioma espanol o de paises hispanos
                    val es = res.filter { it.url in spaUrls || isSpanishId(it.tvgId) }
                    val otros = res.filter { !(it.url in spaUrls || isSpanishId(it.tvgId)) }
                    if (es.isNotEmpty() && otros.isNotEmpty()) {
                        res = listOf(header("En español")) + es + listOf(header("Otros idiomas")) + otros
                    }
                }
                runOnUiThread { if (!isFinishing) onLoaded(i, res) }
            }
        }
    }

    private fun onLoaded(i: Int, res: List<Channel>) {
        data[i] = res
        if (menuIndex == i + 1) {
            showSection(i + 1)
        } else if (menuIndex == searchIdx) {
            applyQuery(search.text.toString())
        }
        if (i == 0) {
            if (res.isEmpty()) {
                nowTitle.text = getString(R.string.no_channels)
            } else if (playing == null) {
                val last = getSharedPreferences("eva", MODE_PRIVATE).getString("last", null)
                val k = res.indexOfFirst { it.url == last }
                play(res, if (k >= 0) k else 0)
                if (menuIndex == 1) list.setSelection(if (k >= 0) k else 0)
            }
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
        val logoRe = Regex("tvg-logo=\"([^\"]*)\"")
        val idRe = Regex("tvg-id=\"([^\"]*)\"")
        var name: String? = null
        var group = ""
        var logo: String? = null
        var tid: String? = null
        var ua: String? = null
        var ref: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF")) {
                name = line.substringAfterLast(",").trim()
                group = groupRe.find(line)?.groupValues?.get(1) ?: ""
                logo = logoRe.find(line)?.groupValues?.get(1)?.ifEmpty { null }
                tid = idRe.find(line)?.groupValues?.get(1)?.ifEmpty { null }
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
                if (n != null) out.add(Channel(n, group, line, logo, ua, ref, tid))
                name = null
                logo = null
                tid = null
                ua = null
                ref = null
            }
        }
        return out
    }

    // ---------- Favoritos ----------

    private fun loadFav(): MutableList<Channel> {
        val raw = getSharedPreferences("eva", MODE_PRIVATE).getString("fav", "") ?: ""
        val out = ArrayList<Channel>()
        for (line in raw.lines()) {
            if (line.isBlank()) continue
            val p = line.split("\t")
            if (p.size >= 6) {
                out.add(
                    Channel(
                        p[0], p[1], p[2],
                        p[3].ifEmpty { null }, p[4].ifEmpty { null }, p[5].ifEmpty { null }
                    )
                )
            }
        }
        return out
    }

    private fun saveFav() {
        val s = fav.joinToString("\n") {
            listOf(it.name, it.group, it.url, it.logo ?: "", it.userAgent ?: "", it.referrer ?: "")
                .joinToString("\t")
        }
        getSharedPreferences("eva", MODE_PRIVATE).edit().putString("fav", s).apply()
    }

    private fun toggleFav(ch: Channel) {
        val k = fav.indexOfFirst { it.url == ch.url }
        if (k >= 0) {
            fav.removeAt(k)
            showOsd("Quitado de favoritos", 2000)
        } else {
            fav.add(ch)
            showOsd("Agregado a favoritos", 2000)
        }
        saveFav()
        if (menuIndex == favIdx) {
            val pos = list.selectedItemPosition
            showSection(favIdx)
            list.setSelection(if (pos >= 0) minOf(pos, maxOf(shown.size - 1, 0)) else 0)
        } else {
            refreshRows()
        }
    }

    // ---------- Menu y lista ----------

    private fun showSection(idx: Int) {
        menuIndex = idx
        menu.setItemChecked(idx, true)
        val searching = idx == searchIdx
        search.visibility = if (searching) View.VISIBLE else View.GONE
        if (!searching && search.text.isNotEmpty()) search.setText("")
        when {
            searching -> {
                applyQuery(search.text.toString())
                return
            }
            idx == favIdx -> {
                shown = fav.toList()
                listTitle.text =
                    if (fav.isEmpty()) "Favoritos (vacío)\nUsa Menú u OK largo en un canal" else "Favoritos"
            }
            else -> {
                val src = idx - 1
                shown = data[src] ?: emptyList()
                listTitle.text =
                    if (data.containsKey(src)) labels[idx] else labels[idx] + "  (cargando…)"
            }
        }
        list.adapter = ChannelAdapter(shown)
    }

    private fun focusMenu() {
        menu.requestFocus()
        menu.setSelection(menuIndex)
    }

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    private fun applyQuery(q: String) {
        if (menuIndex != searchIdx) return
        val t = norm(q.trim())
        if (t.isEmpty()) {
            shown = emptyList()
            listTitle.text = "Buscar en todo"
        } else {
            val out = ArrayList<Channel>()
            val seen = HashSet<String>()
            for (k in data.keys.sorted()) {
                for (ch in data[k] ?: emptyList()) {
                    if (ch.url.isEmpty()) continue
                    if (ch.key.contains(t) && seen.add(ch.url)) out.add(ch)
                }
            }
            shown = out
            listTitle.text = "Resultados (${out.size})"
        }
        list.adapter = ChannelAdapter(shown)
    }

    private fun refreshRows() {
        (list.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(search.windowToken, 0)
    }

    inner class ChannelAdapter(private val items: List<Channel>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = items[position].url.isNotEmpty()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_channel, parent, false)
            val ch = items[position]
            val nameTv = v.findViewById<TextView>(R.id.name)
            val logoIv = v.findViewById<ImageView>(R.id.logo)
            if (ch.url.isEmpty()) {
                logoIv.visibility = View.GONE
                logoIv.tag = null
                nameTv.text = ch.name
                nameTv.setTextColor(0xFFA8DE78.toInt())
                nameTv.textSize = 14f
            } else {
                val star = if (fav.any { it.url == ch.url }) "★ " else ""
                val mark = if (playing?.url == ch.url) "► " else ""
                logoIv.visibility = View.VISIBLE
                nameTv.setTextColor(0xFFFFFFFF.toInt())
                nameTv.textSize = 16f
                nameTv.text = mark + star + ch.name
                Logos.load(logoIv, ch.logo)
            }
            return v
        }
    }

    // ---------- Reproduccion ----------

    private fun play(l: List<Channel>, idx: Int) {
        if (l.isEmpty()) return
        val i = ((idx % l.size) + l.size) % l.size
        val ch = l[i]
        playingList = l
        playingIdx = i
        playing = ch
        getSharedPreferences("eva", MODE_PRIVATE).edit().putString("last", ch.url).apply()
        nowTitle.text = ch.name
        showOsd(ch.name, 3000)
        refreshRows()

        val p = player ?: return
        val ds = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        ch.userAgent?.let { ds.setUserAgent(it) }
        ch.referrer?.let { ds.setDefaultRequestProperties(mapOf("Referer" to it)) }
        val source = DefaultMediaSourceFactory(ds).createMediaSource(MediaItem.fromUri(ch.url))
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
    }

    /** Ventana chica: 540p max (mas fluido). Pantalla completa: sin limite. */
    private fun applyQuality() {
        val p = player ?: return
        val b = p.trackSelectionParameters.buildUpon()
        if (fullscreen) b.clearVideoSizeConstraints() else b.setMaxVideoSize(960, 540)
        p.trackSelectionParameters = b.build()
    }

    private fun showOsd(msg: String, ms: Long) {
        osd.text = msg
        osd.visibility = View.VISIBLE
        handler.removeCallbacks(hideOsd)
        handler.postDelayed(hideOsd, ms)
    }

    private fun enterFullscreen() {
        fullscreen = true
        applyQuality()
        menuPane.visibility = View.GONE
        listPane.visibility = View.GONE
        nowTitle.visibility = View.GONE
        nowHint.visibility = View.GONE
        centerPane.setPadding(0, 0, 0, 0)
        hideKeyboard()
        showOsd(playing?.name ?: "", 3000)
    }

    private fun exitFullscreen() {
        fullscreen = false
        applyQuality()
        val pad = (12 * resources.displayMetrics.density).toInt()
        centerPane.setPadding(pad, pad, pad, pad)
        menuPane.visibility = View.VISIBLE
        listPane.visibility = View.VISIBLE
        nowTitle.visibility = View.VISIBLE
        nowHint.visibility = View.VISIBLE
        list.requestFocus()
        val k = shown.indexOfFirst { it.url == playing?.url }
        if (k >= 0) list.setSelection(k)
    }

    // ---------- Control remoto ----------

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            fullscreen -> exitFullscreen()
            !menu.hasFocus() -> focusMenu()
            else -> super.onBackPressed()
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        if (fullscreen) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_MENU -> {
                    exitFullscreen()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_CHANNEL_UP -> {
                    play(playingList, playingIdx + 1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    play(playingList, playingIdx - 1)
                    return true
                }
            }
            return super.dispatchKeyEvent(e)
        }
        if (e.keyCode == KeyEvent.KEYCODE_SEARCH) {
            showSection(searchIdx)
            search.requestFocus()
            return true
        }
        if (menu.hasFocus() && e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            pendingMenu?.let { handler.removeCallbacks(it) }
            val sel = menu.selectedItemPosition
            if (sel >= 0 && sel != menuIndex) showSection(sel)
            if (menuIndex == searchIdx) search.requestFocus() else list.requestFocus()
            return true
        }
        if (list.hasFocus() && e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            focusMenu()
            return true
        }
        if (list.hasFocus() && e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (playing != null) enterFullscreen()
            return true
        }
        if (e.keyCode == KeyEvent.KEYCODE_MENU && list.hasFocus()) {
            val pos = list.selectedItemPosition
            shown.getOrNull(pos)?.let { if (it.url.isNotEmpty()) toggleFav(it) }
            return true
        }
        return super.dispatchKeyEvent(e)
    }
}
