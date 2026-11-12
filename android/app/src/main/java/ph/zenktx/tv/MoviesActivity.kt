package ph.zenktx.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import ph.zenktx.tv.data.MovieCatalog
import ph.zenktx.tv.data.MovieTitle
import ph.zenktx.tv.data.PlayerServer
import ph.zenktx.tv.net.Http
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Movies & Series.
 *
 * Deliberately its own screen: the guide's D-pad model is built for zapping and
 * a poster wall needs a different one. Playback is the publisher's embedded
 * player in a WebView -- X-Frame-Options does not apply to a WebView, which is
 * why this works where an iframe would be refused -- and a server that paints
 * nothing is walked past instead of shown as a black rectangle.
 *
 * One layout serves a 55" television and a 6" phone: the width decides whether
 * the tabs share the header row or get their own, how many posters fit, and
 * whether the details sit beside the wall or under it.
 */
class MoviesActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private var catalog: MovieCatalog? = null
    private var kind = "movies"
    private var selected: MovieTitle? = null
    private var serverIndex = 0
    private var season = 1
    private var episode = 1

    /** A phone is narrow enough that a poster is finger-sized, not D-pad-sized. */
    private val compact: Boolean by lazy {
        resources.displayMetrics.widthPixels / resources.displayMetrics.density < 600f
    }

    private lateinit var grid: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var progress: ProgressBar
    private lateinit var empty: TextView
    private lateinit var tabMovies: TextView
    private lateinit var tabSeries: TextView
    private lateinit var detailTitle: TextView
    private lateinit var detailMeta: TextView
    private lateinit var detailServerRow: LinearLayout
    private lateinit var seasonRow: LinearLayout
    private lateinit var seasonValue: TextView
    private lateinit var episodeValue: TextView

    private var titlesNow: List<MovieTitle> = emptyList()
    private var rendered = 0
    private var appending = false

    private var playerLayer: FrameLayout? = null
    private var web: WebView? = null
    private var playerChips: LinearLayout? = null
    private var tries = 0
    private val watchdog = Runnable { probePlayback() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        load()
    }

    /* ------------------------------------------------------------------ ui */

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private val padH: Int get() = if (compact) dp(16) else dp(40)
    private val gap: Int get() = if (compact) dp(8) else dp(10)

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompatColor.bg)
            setPadding(padH, if (compact) dp(10) else dp(18), padH, if (compact) dp(8) else dp(14))
        }

        val top = LinearLayout(this).apply {
            orientation = if (compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = pill("←  Live TV", primary = false)
        back.setOnClickListener { finish() }

        val title = TextView(this).apply {
            text = "Movies & Series"
            setTextColor(ContextCompatColor.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 17f else 18f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(if (compact) dp(2) else dp(16), if (compact) dp(10) else 0, dp(16), 0)
        }

        tabMovies = if (compact) segment("Movies") else pill("Movies", primary = true)
        tabMovies.setOnClickListener { switchKind("movies") }
        tabSeries = if (compact) segment("Series") else pill("Series", primary = false)
        tabSeries.setOnClickListener { switchKind("series") }

        if (compact) {
            /* the phone puts the tabs on their own full-width row */
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            bar.addView(back)
            bar.addView(title)
            top.addView(bar)
            val tabs = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            tabs.addView(tabMovies, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(8) })
            tabs.addView(tabSeries, LinearLayout.LayoutParams(0, dp(38), 1f))
            top.addView(tabs)
        } else {
            top.addView(back)
            top.addView(title)
            top.addView(tabMovies)
            top.addView(tabSeries)
            top.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        }
        root.addView(top)

        /* grid */
        progress = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { gravity = Gravity.CENTER }
        }
        empty = TextView(this).apply {
            text = ""
            setTextColor(ContextCompatColor.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(24), 0, 0)
            gravity = Gravity.CENTER
        }
        grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(10))
        }
        val gridWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(progress)
            addView(empty)
            addView(grid)
        }
        scroller = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(gridWrap)
            setOnScrollChangeListener { _, _, _, _, _ -> maybeAppend() }
        }
        root.addView(scroller)

        /* details: beside the wall on a TV, under it on a phone */
        val footer = LinearLayout(this).apply {
            orientation = if (compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (compact) Gravity.START else Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        detailTitle = TextView(this).apply {
            text = "Pick a title"
            setTextColor(ContextCompatColor.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 17f else 15f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        detailMeta = TextView(this).apply {
            text = ""
            setTextColor(ContextCompatColor.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        textCol.addView(detailTitle)
        textCol.addView(detailMeta)
        textCol.layoutParams = LinearLayout.LayoutParams(
            if (compact) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        footer.addView(textCol)

        seasonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        val seasonMinus = pill("S−", primary = false, small = true)
        seasonValue = pill("S1", primary = false, small = true)
        val seasonPlus = pill("S+", primary = false, small = true)
        val episodeMinus = pill("E−", primary = false, small = true)
        episodeValue = pill("E1", primary = false, small = true)
        val episodePlus = pill("E+", primary = false, small = true)
        seasonMinus.setOnClickListener { season = max(1, season - 1); paintSteppers() }
        seasonPlus.setOnClickListener { season += 1; paintSteppers() }
        episodeMinus.setOnClickListener { episode = max(1, episode - 1); paintSteppers() }
        episodePlus.setOnClickListener { episode += 1; paintSteppers() }
        listOf(seasonMinus, seasonValue, seasonPlus, episodeMinus, episodeValue, episodePlus)
            .forEach { seasonRow.addView(it) }
        footer.addView(seasonRow)

        detailServerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (compact) {
            /* four servers plus Play overflow a phone's width, so they scroll */
            val chipsScroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, dp(8), 0, 0)
                clipToPadding = false
                addView(detailServerRow)
            }
            footer.addView(chipsScroll)
        } else {
            footer.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
            footer.addView(detailServerRow)
        }
        root.addView(footer)

        setContentView(root)
    }

    private fun pill(label: String, primary: Boolean, small: Boolean = false): TextView =
        TextView(this).apply {
            text = label
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 9f else 10f)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.06f
            setTextColor(if (primary) ContextCompatColor.pillText else ContextCompatColor.chipText)
            setBackgroundResource(if (primary) R.drawable.bg_btn_primary else R.drawable.bg_chip)
            val padH = if (small) dp(9) else dp(16)
            setPadding(padH, dp(7), padH, dp(7))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
            setOnFocusChangeListener { view, hasFocus ->
                view.animate().scaleX(if (hasFocus) 1.05f else 1f).scaleY(if (hasFocus) 1.05f else 1f)
                    .setDuration(120).start()
            }
        }

    /** The phone's two-up switch: a filled half when on, an outlined half when off. */
    private fun segment(label: String): TextView =
        pill(label, primary = false).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompatColor.mint)
            setBackgroundResource(R.drawable.bg_chip_active)
        }

    /* -------------------------------------------------------------- loading */

    private fun load() {
        progress.visibility = View.VISIBLE
        empty.text = "Loading the catalogue…"
        Thread {
            val loaded = try {
                MovieCatalog.load()
            } catch (error: Exception) {
                null
            }
            main.post {
                progress.visibility = View.GONE
                if (loaded == null) {
                    empty.text = "The Movies catalogue could not be loaded."
                    return@post
                }
                catalog = loaded
                empty.text = ""
                render()
            }
        }.start()
    }

    private fun switchKind(next: String) {
        if (kind == next) return
        kind = next
        selected = null
        season = 1
        episode = 1
        scroller.scrollTo(0, 0)
        render()
    }

    private fun paintTabs() {
        val on = ContextCompatColor.mint
        val off = ContextCompatColor.chipText
        tabMovies.setTextColor(if (kind == "movies") on else off)
        tabSeries.setTextColor(if (kind == "series") on else off)
        tabMovies.setBackgroundResource(if (kind == "movies") R.drawable.bg_chip_active else R.drawable.bg_chip)
        tabSeries.setBackgroundResource(if (kind == "series") R.drawable.bg_chip_active else R.drawable.bg_chip)
    }

    private fun render() {
        val data = catalog ?: return
        titlesNow = data.titles(kind)
        grid.removeAllViews()
        rendered = 0
        if (titlesNow.isEmpty()) {
            empty.text = "Nothing to show yet."
            return
        }
        empty.text = ""
        paintTabs()
        val keep = selected?.let { current -> titlesNow.firstOrNull { it.id == current.id } }
        selected = keep ?: titlesNow.first()
        appendRows()
        paintDetail()
    }

    /**
     * Two hundred posters is two hundred decodes; the wall is filled a screenful
     * at a time as the reader scrolls instead of all at once on the main thread.
     */
    private fun appendRows() {
        if (appending || rendered >= titlesNow.size) return
        appending = true
        try {
            val width = resources.displayMetrics.widthPixels - padH * 2
            val columns = if (compact) 3 else max(4, width / dp(165))
            val cardW = (width - gap * (columns - 1)) / columns
            val posterH = (cardW * 1.5f).toInt()
            val limit = min(titlesNow.size, rendered + PAGE)
            while (rendered < limit) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, gap)
                }
                var column = 0
                while (column < columns && rendered < titlesNow.size) {
                    val item = titlesNow[rendered]
                    row.addView(card(item, cardW, posterH, column == columns - 1))
                    rendered += 1
                    column += 1
                }
                grid.addView(row)
            }
        } finally {
            appending = false
        }
    }

    private fun maybeAppend() {
        if (rendered >= titlesNow.size) return
        val content = scroller.getChildAt(0) ?: return
        if (scroller.scrollY + scroller.height >= content.height - dp(400)) {
            grid.post { appendRows() }
        }
    }

    private fun card(item: MovieTitle, width: Int, posterH: Int, lastColumn: Boolean): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            tag = item
            layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { if (!lastColumn) marginEnd = gap }
        }
        val poster = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, posterH)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.bg_tile)
            contentDescription = item.title
        }
        val name = TextView(this).apply {
            text = item.title
            setTextColor(ContextCompatColor.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(6), dp(2), 0)
        }
        wrap.addView(poster)
        wrap.addView(name)
        PosterLoader.load(item.poster, poster)
        paintCard(wrap, name)

        if (!compact) {
            /* a television is driven by the D-pad, so the focused poster grows */
            wrap.setOnFocusChangeListener { view, hasFocus ->
                view.animate().scaleX(if (hasFocus) 1.06f else 1f).scaleY(if (hasFocus) 1.06f else 1f)
                    .setDuration(120).start()
                name.setTextColor(if (hasFocus) ContextCompatColor.text else ContextCompatColor.muted)
                view.setBackgroundResource(if (hasFocus) R.drawable.bg_tile_glow else 0)
            }
        }
        wrap.setOnClickListener {
            selected = item
            paintDetail()
            paintSelection()
        }
        return wrap
    }

    /** Marks the picked poster so the wall shows where the details came from. */
    private fun paintCard(wrap: View, name: TextView) {
        val item = wrap.tag as? MovieTitle ?: return
        val on = item.id == selected?.id
        name.setTextColor(if (on) ContextCompatColor.mint else ContextCompatColor.muted)
        name.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun paintSelection() {
        for (row in 0 until grid.childCount) {
            val line = grid.getChildAt(row) as? LinearLayout ?: continue
            for (column in 0 until line.childCount) {
                val card = line.getChildAt(column) as? LinearLayout ?: continue
                val name = card.getChildAt(1) as? TextView ?: continue
                paintCard(card, name)
            }
        }
    }

    private fun paintDetail() {
        val item = selected ?: return
        detailTitle.text = item.title
        detailMeta.text = listOfNotNull(
            item.year.ifBlank { null },
            item.genres.take(2).joinToString(" · ").ifBlank { null },
            if (item.rating > 0) "★ ${item.rating}" else null,
        ).joinToString("   ")
        seasonRow.visibility = if (item.kind == "tv") View.VISIBLE else View.GONE
        paintSteppers()

        detailServerRow.removeAllViews()
        val servers = catalog?.servers.orEmpty()
        servers.forEachIndexed { index, server ->
            val chip = pill(server.name, primary = index == serverIndex, small = true)
            chip.setOnClickListener {
                serverIndex = index
                paintDetail()
            }
            detailServerRow.addView(chip)
        }
        val play = pill("▶  Play", primary = true)
        play.setOnClickListener { openPlayer() }
        if (compact) {
            play.gravity = Gravity.CENTER
            detailServerRow.addView(play, LinearLayout.LayoutParams(dp(96), dp(36)))
        } else {
            detailServerRow.addView(play)
        }
    }

    private fun paintSteppers() {
        seasonValue.text = "S$season"
        episodeValue.text = "E$episode"
    }

    /* --------------------------------------------------------------- player */

    @SuppressLint("SetJavaScriptEnabled")
    private fun openPlayer() {
        val data = catalog ?: return
        val item = selected ?: return
        val server = data.servers.getOrNull(serverIndex) ?: return
        closePlayer()

        val layer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val view = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString = Http.USER_AGENT
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl(data.address(server, item, season, episode))
        }
        layer.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ContextCompatColor.surface)
            setPadding(dp(if (compact) 10 else 24), dp(8), dp(if (compact) 10 else 24), dp(8))
        }
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            text = if (item.kind == "tv") "S$season E$episode" else item.title
            setTextColor(ContextCompatColor.mint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, dp(10), 0)
            maxWidth = dp(if (compact) 110 else 220)
        }
        bar.addView(label)
        val chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }
        bar.addView(chipsScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = pill("✕", primary = false, small = true)
        close.setOnClickListener { closePlayer() }
        bar.addView(close, LinearLayout.LayoutParams(dp(34), dp(30)))

        layer.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(if (compact) 52 else 60), Gravity.BOTTOM,
        ))

        (window.decorView as FrameLayout).addView(layer)
        playerLayer = layer
        web = view
        playerChips = chips
        tries = 0
        paintPlayerChips()
        armWatchdog()
    }

    private fun paintPlayerChips() {
        val chips = playerChips ?: return
        chips.removeAllViews()
        catalog?.servers.orEmpty().forEachIndexed { index, server ->
            val chip = pill(server.name, primary = index == serverIndex, small = true)
            chip.setOnClickListener {
                serverIndex = index
                tries = 0
                paintPlayerChips()
                reloadServer()
            }
            chips.addView(chip)
        }
    }

    private fun reloadServer() {
        val data = catalog ?: return
        val item = selected ?: return
        val server = data.servers.getOrNull(serverIndex) ?: return
        web?.loadUrl(data.address(server, item, season, episode))
        armWatchdog()
    }

    /** Ask the guest page for a decoded frame; a dead mirror returns 200 and paints black. */
    private fun armWatchdog() {
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 12_000)
    }

    private fun probePlayback() {
        val view = web ?: return
        view.evaluateJavascript(
            "(function(){var v=document.querySelector('video');return v && v.readyState>=2;})()",
        ) { result ->
            if (playerLayer == null) return@evaluateJavascript
            if (result != null && result.contains("true")) return@evaluateJavascript
            val total = catalog?.servers?.size ?: 0
            if (total == 0) return@evaluateJavascript
            tries += 1
            if (tries >= total) return@evaluateJavascript
            serverIndex = (serverIndex + 1) % total
            paintPlayerChips()
            reloadServer()
        }
    }

    private fun closePlayer() {
        main.removeCallbacks(watchdog)
        web?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        web = null
        playerChips = null
        playerLayer?.let { (window.decorView as FrameLayout).removeView(it) }
        playerLayer = null
    }

    override fun onDestroy() {
        closePlayer()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (playerLayer != null) {
            closePlayer()
            return
        }
        super.onBackPressed()
    }

    private companion object {
        /** How many posters a scroll adds; about three phone screens. */
        const val PAGE = 45
    }
}

/** The palette, read once so the screen needs no theme lookup per view. */
private object ContextCompatColor {
    val bg = Color.parseColor("#060911")
    val surface = Color.parseColor("#0D1524")
    val text = Color.parseColor("#F2F5FF")
    val muted = Color.parseColor("#8B98B4")
    val mint = Color.parseColor("#35E7C0")
    val chipText = Color.parseColor("#B9C6DE")
    val pillText = Color.parseColor("#08101F")
}

/**
 * Posters are small, numerous and remote; a cache plus four threads keeps the
 * D-pad ahead of the network instead of stalling the main thread on decode.
 */
private object PosterLoader {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(4)

    fun load(url: String, into: ImageView) {
        if (url.isBlank()) return
        into.tag = url
        cache.get(url)?.let {
            into.setImageBitmap(it)
            return
        }
        pool.execute {
            val bitmap = try {
                val bytes = Http.getBytes(url, 20_000)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (error: Exception) {
                null
            } ?: return@execute
            cache.put(url, bitmap)
            into.post {
                if (into.tag == url) into.setImageBitmap(bitmap)
            }
        }
    }
}
