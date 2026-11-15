package ph.zenktx.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Movies & Series.
 *
 * Deliberately its own screen: the guide's D-pad model is built for zapping and
 * a poster wall needs a different one. The catalogue is an index plus pages, so
 * the wall grows by 28 posters as the reader reaches the bottom of what it has.
 *
 * Playback is the publisher's embedded player in a WebView -- X-Frame-Options
 * does not apply to a WebView, which is why this works where an iframe would be
 * refused -- with our own transport row over it, because the embedded page's
 * controls sit under the cutout on a phone. A server that paints nothing is
 * walked past instead of shown as a black rectangle.
 *
 * The whole screen runs immersive: no status bar, no navigation bar, no clock.
 */
class MoviesActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private var catalog: MovieCatalog? = null
    private var kind = "movies"
    private var selected: MovieTitle? = null
    private var serverIndex = 0
    private var season = 1
    private var episode = 1

    /** Titles pulled so far, per kind, and how many pages are behind them. */
    private val loaded = mutableMapOf<String, MutableList<MovieTitle>>()
    private val cursor = mutableMapOf<String, Int>()
    private var loadingPage = false

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
    private var playHost: LinearLayout? = null
    private lateinit var seasonRow: LinearLayout
    private lateinit var seasonValue: TextView
    private lateinit var episodeValue: TextView

    private var rendered = 0
    private var appending = false

    private var playerLayer: FrameLayout? = null
    private var web: WebView? = null
    private var playerChips: LinearLayout? = null
    private var muted = false
    private var volume = 100
    private var embedHost = ""
    private var tries = 0
    private var probes = 0
    private val watchdog = Runnable { probePlayback() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersive()
        buildUi()
        load()
    }

    /* --------------------------------------------------------------- window */

    /** Nothing but the app: the clock strip and the navigation bar go away. */
    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
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
            setPadding(padH, if (compact) dp(16) else dp(22), padH, if (compact) dp(10) else dp(14))
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

        tabMovies = if (compact) segment("Movies") else pill("Movies", primary = false)
        tabMovies.setOnClickListener { switchKind("movies") }
        tabSeries = if (compact) segment("Series") else pill("Series", primary = false)
        tabSeries.setOnClickListener { switchKind("series") }

        if (compact) {
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
            tabs.addView(tabMovies, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) })
            tabs.addView(tabSeries, LinearLayout.LayoutParams(0, dp(40), 1f))
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
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(ContextCompatColor.line)
        })
        val footer = LinearLayout(this).apply {
            orientation = if (compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (compact) Gravity.START else Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, if (compact) dp(2) else 0)
        }
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        detailTitle = TextView(this).apply {
            text = "Pick a title"
            setTextColor(ContextCompatColor.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 16f else 15f)
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
            orientation = if (compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
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
        listOf(seasonMinus, seasonValue, seasonPlus, episodeMinus, episodeValue, episodePlus).forEach {
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 12f else 10f)
            it.setPadding(dp(14), dp(if (compact) 8 else 7), dp(14), dp(if (compact) 8 else 7))
            if (!compact) it.minWidth = dp(52)
        }
        seasonMinus.setOnClickListener { season = max(1, season - 1); paintSteppers() }
        seasonPlus.setOnClickListener { season += 1; paintSteppers() }
        episodeMinus.setOnClickListener { episode = max(1, episode - 1); paintSteppers() }
        episodePlus.setOnClickListener { episode += 1; paintSteppers() }
        if (compact) {
            /* Seasons on one line, episodes on the next: three chips fit, six do not.
               Each line is horizontal inside the vertical stepper block. */
            val seasonLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(seasonMinus, seasonValue, seasonPlus).forEach { seasonLine.addView(it) }
            seasonRow.addView(seasonLine)
            val episodeLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            listOf(episodeMinus, episodeValue, episodePlus).forEach { episodeLine.addView(it) }
            seasonRow.addView(episodeLine)
        } else {
            listOf(seasonMinus, seasonValue, seasonPlus, episodeMinus, episodeValue, episodePlus)
                .forEach { seasonRow.addView(it) }
        }
        footer.addView(seasonRow)

        detailServerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (compact) {
            /* Play sits outside the scrolling chips: inside it, the pill was the
               last thing in a row that runs off the screen. */
            val chipsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            val chipsScroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                addView(detailServerRow)
            }
            chipsRow.addView(chipsScroll, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            ))
            playHost = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, 0, 0)
            }
            chipsRow.addView(playHost, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            footer.addView(chipsRow)
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

    /** A player server: dark when idle, mint-edged once chosen. */
    private fun serverChip(label: String, on: Boolean, small: Boolean = true): TextView =
        TextView(this).apply {
            text = label
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 9f else 10f)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.04f
            setTextColor(if (on) ContextCompatColor.mint else ContextCompatColor.chipText)
            setBackgroundResource(if (on) R.drawable.bg_chip_mint else R.drawable.bg_chip)
            val padH = if (small) dp(10) else dp(14)
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

    /** The phone's two-up switch. */
    private fun segment(label: String): TextView =
        pill(label, primary = false).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompatColor.chipText)
            setBackgroundResource(R.drawable.bg_chip)
        }

    /* -------------------------------------------------------------- loading */

    private fun load() {
        progress.visibility = View.VISIBLE
        empty.text = "Loading the catalogue…"
        Thread {
            val index = try {
                MovieCatalog.load()
            } catch (error: Exception) {
                null
            }
            main.post {
                progress.visibility = View.GONE
                if (index == null) {
                    empty.text = "The Movies catalogue could not be loaded."
                    return@post
                }
                catalog = index
                empty.text = ""
                loadNextPage()
            }
        }.start()
    }

    /** Pulls the next published page; the wall stays usable while it arrives. */
    private fun loadNextPage() {
        val data = catalog ?: return
        if (loadingPage) return
        val pages = data.pages(kind)
        val next = cursor[kind] ?: 0
        if (next >= pages.files.size) return
        loadingPage = true
        if (loaded[kind].isNullOrEmpty()) progress.visibility = View.VISIBLE
        Thread {
            val titles = try {
                MovieCatalog.loadPage(data, kind, next)
            } catch (error: Exception) {
                emptyList()
            }
            main.post {
                loadingPage = false
                progress.visibility = View.GONE
                val list = loaded.getOrPut(kind) { mutableListOf() }
                list.addAll(titles)
                cursor[kind] = next + 1
                if (list.isEmpty()) {
                    empty.text = "Nothing to show yet."
                    return@post
                }
                empty.text = ""
                paintTabs()
                if (selected == null) selected = list.first()
                appendRows()
                paintDetail()
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
        paintTabs()
        render()
        if (loaded[kind].isNullOrEmpty()) loadNextPage()
    }

    private fun paintTabs() {
        tabMovies.setBackgroundResource(if (kind == "movies") R.drawable.bg_segment_on else R.drawable.bg_chip)
        tabSeries.setBackgroundResource(if (kind == "series") R.drawable.bg_segment_on else R.drawable.bg_chip)
        tabMovies.setTextColor(if (kind == "movies") ContextCompatColor.mint else ContextCompatColor.chipText)
        tabSeries.setTextColor(if (kind == "series") ContextCompatColor.mint else ContextCompatColor.chipText)
    }

    private fun render() {
        grid.removeAllViews()
        rendered = 0
        val list = loaded[kind].orEmpty()
        if (list.isEmpty()) return
        if (selected == null) selected = list.first()
        appendRows()
        paintDetail()
    }

    private fun appendRows() {
        val list = loaded[kind].orEmpty()
        if (list.isEmpty() || appending) return
        appending = true
        try {
            if (rendered == 0) grid.removeAllViews()
            val width = resources.displayMetrics.widthPixels - padH * 2
            val columns = if (compact) 3 else max(4, width / dp(165))
            val cardW = (width - gap * (columns - 1)) / columns
            val posterH = (cardW * 1.5f).toInt()
            val limit = min(list.size, rendered + PAGE)
            while (rendered < limit) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, gap)
                }
                var column = 0
                while (column < columns && rendered < list.size) {
                    row.addView(card(list[rendered], cardW, posterH, column == columns - 1))
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
        val content = scroller.getChildAt(0) ?: return
        if (scroller.scrollY + scroller.height < content.height - dp(400)) return
        if (rendered < loaded[kind].orEmpty().size) {
            grid.post { appendRows() }
            return
        }
        val pages = catalog?.pages(kind) ?: return
        if ((cursor[kind] ?: 0) < pages.files.size) loadNextPage()
    }

    private fun card(item: MovieTitle, width: Int, posterH: Int, lastColumn: Boolean): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            /* On a phone nothing should take focus: doing so scrolls the wall
               down on open and cuts the first row in half. */
            isFocusable = !compact
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
        catalog?.servers.orEmpty().forEachIndexed { index, server ->
            val chip = serverChip(server.name, index == serverIndex)
            chip.setOnClickListener {
                serverIndex = index
                paintDetail()
            }
            detailServerRow.addView(chip)
        }
        val play = TextView(this).apply {
            text = "▶  Play"
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.04f
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_pill_red)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { openPlayer() }
        }
        if (compact) {
            playHost?.removeAllViews()
            playHost?.addView(play, LinearLayout.LayoutParams(dp(82), dp(34)))
        } else {
            detailServerRow.addView(play, LinearLayout.LayoutParams(dp(110), dp(34)))
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

        val layer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        val url = data.address(server, item, season, episode)
        embedHost = Uri.parse(url).host.orEmpty()
        muted = false
        volume = 100

        val view = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.setSupportMultipleWindows(false)
            settings.userAgentString = Http.USER_AGENT
            webViewClient = EmbedClient()
            webChromeClient = object : WebChromeClient() {
                /* Pop-unders are how these pages pay for themselves; refuse them. */
                override fun onCreateWindow(
                    view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?,
                ): Boolean = false
            }
            loadUrl(url)
        }
        /* The embed owns only the stage. Its own controls then sit above our strip
           instead of behind it, which is what made the bar look smeared. */
        val stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        stage.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(stage, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        val chrome = playerChrome(item)
        column.addView(chrome, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        layer.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        /* A corner toggle so full screen is always one tap away, and one tap back. */
        val corner = TextView(this).apply {
            text = "⛶"
            isClickable = true
            isFocusable = true
            gravity = Gravity.CENTER
            setTextColor(ContextCompatColor.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(Color.parseColor("#66000000"))
            setOnClickListener {
                chrome.visibility = if (chrome.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                applyImmersive()
            }
        }
        stage.addView(corner, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; topMargin = dp(14); marginEnd = dp(14) })

        (window.decorView as FrameLayout).addView(layer)
        playerLayer = layer
        web = view
        tries = 0
        paintPlayerChips()
        armWatchdog()
    }

    /** Transport, volume and close, in one strip wired to the guest's <video>. */
    private fun playerChrome(item: MovieTitle): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompatColor.surface)
            setPadding(dp(if (compact) 10 else 20), dp(5), dp(if (compact) 10 else 20), dp(7))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            text = if (item.kind == "tv") "S$season E$episode" else item.title
            visibility = if (compact) View.GONE else View.VISIBLE
            setTextColor(ContextCompatColor.mint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(if (compact) 100 else 220)
            setPadding(0, 0, dp(10), 0)
        }
        topRow.addView(label)
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = serverChip("✕", on = false)
        close.setOnClickListener { closePlayer() }
        topRow.addView(close, LinearLayout.LayoutParams(dp(34), dp(30)))
        bar.addView(topRow)

        playerChips = chips
        return bar
    }

    private fun js(code: String) = web?.evaluateJavascript(code, null)

    private fun paintPlayerChips() {
        val chips = playerChips ?: return
        chips.removeAllViews()
        catalog?.servers.orEmpty().forEachIndexed { index, server ->
            val chip = serverChip(server.name, index == serverIndex)
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
        val url = data.address(server, item, season, episode)
        embedHost = Uri.parse(url).host.orEmpty()
        web?.loadUrl(url)
        armWatchdog()
    }

    /**
     * These pages are funded by video ads and pop-unders. Requests to the ad
     * networks are answered empty and navigations off the embed's own host are
     * refused, which leaves the player itself untouched.
     */
    private inner class EmbedClient : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView, request: WebResourceRequest,
        ): WebResourceResponse? {
            val host = request.url.host?.lowercase().orEmpty()
            if (AD_HOSTS.any { host == it || host.endsWith(".$it") }) {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val host = request.url.host?.lowercase().orEmpty()
            if (host.isEmpty() || host == embedHost) return false
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            /* Belt and braces: some pages open windows from script. */
            view.evaluateJavascript("window.open=function(){return null;};", null)
            /* Some mirrors float a "confirm you are not a robot" card over the
               video. Removing it by text is the only handle a wrapper has: it is
               the page's own markup, not a request we can refuse. */
            listOf(900L, 2_500L, 5_000L).forEach { delay ->
                main.postDelayed({ dismissOverlay(view) }, delay)
            }
        }
    }

    private fun dismissOverlay(view: WebView) {
        view.evaluateJavascript(
            "(function(){var out=[];document.querySelectorAll('div,section,iframe').forEach(function(el){" +
                "var st=getComputedStyle(el);if(st.position!=='fixed'&&st.position!=='absolute')return;" +
                "if(parseFloat(st.zIndex||'0')<10)return;var r=el.getBoundingClientRect();" +
                "if(r.width<innerWidth*0.6||r.height<innerHeight*0.35)return;" +
                "var t=(el.textContent||'').toLowerCase();" +
                "if(/not a robot|are you human|captcha|verify you|sponsored/.test(t)){el.remove();out.push(1);}});" +
                "return out.length;})()",
            null,
        )
    }

    /** Ask the guest page for a decoded frame; a dead mirror returns 200 and paints black. */
    private fun armWatchdog() {
        main.removeCallbacks(watchdog)
        probes = 0
        main.postDelayed(watchdog, 15_000)
    }

    /**
     * A mirror is given up on only when it has produced no <video> element at
     * all. A page that already has one is buffering, showing an ad or waiting
     * for a tap -- switching servers there was yanking the picture away from a
     * source that worked.
     */
    private fun probePlayback() {
        val view = web ?: return
        view.evaluateJavascript(
            "(function(){var v=document.querySelector('video');if(!v)return 'none';" +
                "var alive=v.readyState>=1||v.duration>0||v.currentTime>0||!v.paused;" +
                "return alive?'alive':'idle';})()",
        ) { result ->
            if (playerLayer == null) return@evaluateJavascript
            val state = result.orEmpty()
            if (state.contains("alive")) return@evaluateJavascript
            if (state.contains("idle") && probes < 1) {
                probes += 1
                main.postDelayed(watchdog, 15_000)
                return@evaluateJavascript
            }
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
        applyImmersive()
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
        /** First screenful, and how many a scroll adds. */
        const val PAGE = 28

        /** Ad networks the embed pages pull from; everything else is left alone. */
        val AD_HOSTS = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "pagead2.googlesyndication.com",
            "popads.net", "popcash.net", "propellerads.com", "propellerpops.com",
            "juicyads.com", "exoclick.com", "exosrv.com", "trafficjunky.com",
            "adsterra.com", "hilltopads.net", "clickadu.com", "onclicksuper.com",
            "mgid.com", "revcontent.com", "taboola.com", "outbrain.com",
            "criteo.com", "criteo.net", "adnxs.com", "pubmatic.com",
            "rubiconproject.com", "openx.net", "zedo.com", "ad-score.com",
            "scorecardresearch.com", "quantserve.com", "moatads.com",
            "adsafeprotected.com", "doubleverify.com", "histats.com",
            "adcolony.com", "applovin.com", "unityads.unity3d.com",
            "s.adroll.com", "adroll.com", "serving-sys.com", "smartadserver.com",
        )
    }
}

/** The palette, read once so the screen needs no theme lookup per view. */
private object ContextCompatColor {
    val bg = Color.parseColor("#060911")
    val surface = Color.parseColor("#0D1524")
    val line = Color.parseColor("#1E2A42")
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
