package ph.zenktx.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import ph.zenktx.tv.data.MovieCatalog
import ph.zenktx.tv.data.MovieTitle
import ph.zenktx.tv.data.PlayerServer
import ph.zenktx.tv.net.Http
import java.io.ByteArrayInputStream
import ph.zenktx.tv.ui.Images
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
    private lateinit var search: EditText
    private lateinit var searchClear: TextView

    /** What the field holds, applied to the wall on every keystroke. */
    private var query = ""
    private var loadingAll = false

    private var rendered = 0
    private var appending = false

    private var playerLayer: FrameLayout? = null
    private var web: WebView? = null
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

        /* Search sits on the wall it filters. A television has no keyboard until
           the field is walked onto (or clicked), so focus opens the on-screen one
           and the clear pill is there for the rest of the typing. */
        search = EditText(this).apply {
            hint = "Search a title"
            isSingleLine = true
            setTextColor(ContextCompatColor.text)
            setHintTextColor(ContextCompatColor.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 13f else 12f)
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dp(14), 0, dp(14), 0)
            isFocusable = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty().trim()
                    searchClear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                    render()
                    if (query.isNotEmpty()) loadAllPages()
                }
            })
            setOnClickListener { showKeyboard() }
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) showKeyboard() }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    hideKeyboard()
                    focusFirstCard()
                    true
                } else {
                    false
                }
            }
            /* A single-line field has no use for up and down: hand them to the
               wall, so the D-pad can always leave the keyboard and reach posters. */
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusFirstCard()
                    true
                } else {
                    false
                }
            }
        }
        searchClear = TextView(this).apply {
            text = "✕"
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER
            setTextColor(ContextCompatColor.chipText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundResource(R.drawable.bg_chip)
            visibility = View.GONE
            setOnClickListener {
                search.setText("")
                hideKeyboard()
            }
        }

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
            val searchRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            searchRow.addView(search, LinearLayout.LayoutParams(0, dp(40), 1f))
            searchRow.addView(
                searchClear,
                LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) },
            )
            top.addView(searchRow)
        } else {
            top.addView(back)
            top.addView(title)
            top.addView(tabMovies)
            top.addView(tabSeries)
            top.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
            top.addView(search, LinearLayout.LayoutParams(dp(250), dp(38)))
            top.addView(
                searchClear,
                LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginStart = dp(8) },
            )
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
                appendRows()
            }
        }.start()
    }

    private fun switchKind(next: String) {
        if (kind == next) return
        kind = next
        selected = null
        season = 1
        episode = 1
        query = ""
        search.setText("")
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

    /** The wall's list: the pages pulled so far, narrowed by the search field. */
    private fun shown(): List<MovieTitle> {
        val list = loaded[kind].orEmpty()
        if (query.isEmpty()) return list
        val needle = query.lowercase()
        return list.filter { item ->
            item.title.lowercase().contains(needle) ||
                item.genres.any { it.lowercase().contains(needle) }
        }
    }

    private fun render() {
        grid.removeAllViews()
        rendered = 0
        if (shown().isEmpty()) {
            empty.text = if (query.isEmpty()) "" else "Nothing matched “$query”."
            return
        }
        empty.text = ""
        appendRows()
    }

    /**
     * A search has to look at the whole catalogue, not just the pages that have
     * scrolled by, so the rest is pulled in the background and the wall repaints
     * when it lands.
     */
    private fun loadAllPages() {
        val data = catalog ?: return
        if (loadingAll) return
        val want = kind
        val pages = data.pages(want)
        val from = cursor[want] ?: 0
        if (from >= pages.files.size) return
        loadingAll = true
        Thread {
            val fresh = mutableListOf<MovieTitle>()
            var next = from
            while (next < pages.files.size) {
                val titles = try {
                    MovieCatalog.loadPage(data, want, next)
                } catch (error: Exception) {
                    emptyList()
                }
                if (titles.isEmpty()) break
                fresh.addAll(titles)
                next += 1
            }
            main.post {
                loadingAll = false
                loaded.getOrPut(want) { mutableListOf() }.addAll(fresh)
                cursor[want] = next
                if (kind == want && query.isNotEmpty()) render()
            }
        }.start()
    }

    private fun showKeyboard() {
        val manager = getSystemService(InputMethodManager::class.java) ?: return
        search.postDelayed(
            { manager.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT) },
            120,
        )
    }

    private fun hideKeyboard() {
        val manager = getSystemService(InputMethodManager::class.java) ?: return
        manager.hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    private fun focusFirstCard() {
        val row = grid.getChildAt(0) as? LinearLayout ?: return
        row.getChildAt(0)?.requestFocus()
    }

    private fun appendRows() {
        val list = shown()
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
            /* The next screenful is warmed while the reader is still on this
               one, so the following twenty-eight posters are already decoded
               when they scroll into view. */
            val from = rendered
            val to = min(list.size, rendered + PAGE)
            if (from < to) Images.prefetch(this, list.subList(from, to).map { it.poster }, cardW)
        } finally {
            appending = false
        }
    }

    private fun maybeAppend() {
        val content = scroller.getChildAt(0) ?: return
        if (scroller.scrollY + scroller.height < content.height - dp(400)) return
        if (rendered < shown().size) {
            grid.post { appendRows() }
            return
        }
        /* A filtered wall is complete already: every page arrives when the
           search starts, so scrolling has nothing left to pull. */
        if (query.isNotEmpty()) return
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
        /* The highlight is a frame *around* the artwork, not a stroke on it: a
           stroke drawn on the poster sat under its own edges and corners, and the
           next card painted over the right-hand side of it. A frame with 3dp of
           padding cannot be clipped by either. */
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, posterH,
            )
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val poster = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.bg_tile)
            contentDescription = item.title
        }
        frame.addView(poster)
        val name = TextView(this).apply {
            text = item.title
            setTextColor(ContextCompatColor.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(6), dp(2), 0)
        }
        wrap.addView(frame)
        wrap.addView(name)
        Images.applyPoster(poster, item.poster, width)
        paintCard(wrap, name)

        if (!compact) {
            wrap.setOnFocusChangeListener { view, hasFocus ->
                view.animate().scaleX(if (hasFocus) 1.06f else 1f).scaleY(if (hasFocus) 1.06f else 1f)
                    .setDuration(120).start()
                name.setTextColor(if (hasFocus) ContextCompatColor.text else ContextCompatColor.muted)
                frame.setBackgroundResource(
                    if (hasFocus) R.drawable.bg_poster_focus else 0,
                )
                /* Raised while focused: without this the card to the right is
                   painted later and covers the ring's right-hand edge. */
                view.translationZ = if (hasFocus) dp(12).toFloat() else 0f
            }
        }
        wrap.setOnClickListener { openSheet(item, wrap) }
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

    /* ----------------------------------------------------------------- sheet */

    /**
     * A poster is the only choice the wall asks for. One sheet then carries the
     * rest of the job -- the season and episode steppers for a series, the
     * mirror, and Play -- so starting a title is two D-pad presses on the poster
     * itself instead of a walk down to a strip under the grid.
     */
    private fun openSheet(item: MovieTitle, from: View?) {
        selected = item
        season = 1
        episode = 1
        paintSelection()

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = getDrawable(R.drawable.bg_sheet)
        }

        val heading = TextView(this).apply {
            text = item.title
            setTextColor(ContextCompatColor.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 16f else 18f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val meta = TextView(this).apply {
            text = listOfNotNull(
                item.year.ifBlank { null },
                item.genres.take(2).joinToString(" · ").ifBlank { null },
                if (item.rating > 0) "★ ${item.rating}" else null,
            ).joinToString("   ")
            setTextColor(ContextCompatColor.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(5), 0, 0)
        }
        sheet.addView(heading)
        sheet.addView(meta)

        val steps = FlexboxLayout(this).apply {
            flexWrap = FlexWrap.WRAP
            visibility = if (item.kind == "tv") View.VISIBLE else View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        val seasonValue = sheetPill("S$season")
        val episodeValue = sheetPill("E$episode")
        fun paintSteps() {
            seasonValue.text = "S$season"
            episodeValue.text = "E$episode"
        }
        val seasonMinus = sheetPill("S−")
        val seasonPlus = sheetPill("S+")
        val episodeMinus = sheetPill("E−")
        val episodePlus = sheetPill("E+")
        seasonMinus.setOnClickListener { season = max(1, season - 1); paintSteps() }
        seasonPlus.setOnClickListener { season += 1; paintSteps() }
        episodeMinus.setOnClickListener { episode = max(1, episode - 1); paintSteps() }
        episodePlus.setOnClickListener { episode += 1; paintSteps() }
        listOf(seasonMinus, seasonValue, seasonPlus, episodeMinus, episodeValue, episodePlus)
            .forEach {
                it.layoutParams = FlexboxLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8); topMargin = dp(8) }
                steps.addView(it)
            }
        sheet.addView(steps)

        val label = TextView(this).apply {
            text = "PLAYER"
            setTextColor(ContextCompatColor.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            letterSpacing = 0.16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, 0)
        }
        sheet.addView(label)

        val chips = FlexboxLayout(this).apply {
            flexWrap = FlexWrap.WRAP
            setPadding(0, dp(2), 0, 0)
        }
        fun paintChips() {
            chips.removeAllViews()
            catalog?.servers.orEmpty().forEachIndexed { index, server ->
                val chip = serverChip(server.name, index == serverIndex)
                chip.layoutParams = FlexboxLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8); topMargin = dp(8) }
                chip.setOnClickListener {
                    serverIndex = index
                    paintChips()
                    chips.getChildAt(index)?.requestFocus()
                }
                chips.addView(chip)
            }
        }
        paintChips()
        sheet.addView(chips)

        val play = TextView(this).apply {
            text = "▶  Play"
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.04f
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_pill_red)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44),
            ).apply { topMargin = dp(18) }
            /* No scale on the full-width Play: growing it made the button wider
               than the sheet, and the parent clipped the left and right sides of
               its focus border away. */
            setOnFocusChangeListener { view, hasFocus ->
                view.setBackgroundResource(R.drawable.bg_pill_red)
                view.alpha = if (hasFocus) 1f else 0.92f
            }
        }
        sheet.addView(play)

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                if (compact) dp(320) else dp(540),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setGravity(Gravity.CENTER)
        }
        play.setOnClickListener {
            dialog.dismiss()
            openPlayer()
        }
        dialog.setOnShowListener { play.requestFocus() }
        dialog.setOnDismissListener {
            /* The D-pad goes back to the poster that opened the sheet, not to the
               top of the wall. */
            if (!compact) from?.requestFocus()
        }
        dialog.show()
    }

    /** The sheet's steppers: same chip look, a size a television can read. */
    private fun sheetPill(label: String): TextView =
        TextView(this).apply {
            text = label
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.04f
            setTextColor(ContextCompatColor.chipText)
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            minWidth = dp(56)
            setOnFocusChangeListener { view, hasFocus ->
                view.animate().scaleX(if (hasFocus) 1.05f else 1f)
                    .scaleY(if (hasFocus) 1.05f else 1f).setDuration(110).start()
                view.setBackgroundResource(if (hasFocus) R.drawable.bg_chip_mint else R.drawable.bg_chip)
                (view as TextView).setTextColor(
                    if (hasFocus) ContextCompatColor.mint else ContextCompatColor.chipText,
                )
            }
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
        /* The embed owns the whole screen. Its own controls are the player: the
           strip we drew under them took the D-pad away from the page, and a
           picture that cannot be started or paused is not a player. */
        layer.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        /* One close button in the corner, so the picture always has a way out. */
        val close = TextView(this).apply {
            text = "✕"
            isClickable = true
            isFocusable = true
            gravity = Gravity.CENTER
            setTextColor(ContextCompatColor.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.parseColor("#66000000"))
            setOnClickListener { closePlayer() }
        }
        layer.addView(close, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; topMargin = dp(14); marginEnd = dp(14) })

        (window.decorView as FrameLayout).addView(layer)
        playerLayer = layer
        web = view
        tries = 0
        armWatchdog()
        /* These pages open on a still frame with their own play button, and a
           D-pad cannot reach a button inside a page. Ask the page to start a few
           times instead of leaving the viewer on a poster. */
        listOf(900L, 2_500L, 5_000L, 9_000L, 14_000L).forEach { delay ->
            main.postDelayed({ startPlayback() }, delay)
        }
    }

    /** Asks the guest page to start: its <video> first, then its own play button. */
    private fun startPlayback() {
        if (playerLayer == null) return
        js(
            "(function(){var v=document.querySelector('video');" +
                "if(v){try{v.play().catch(function(){});}catch(e){}}" +
                "var b=document.querySelector('button[aria-label*=\"lay\" i]," +
                "[class*=\"big-play\"],[class*=\"play-button\"],[class*=\"playbutton\"]');" +
                "if(b){try{b.click();}catch(e){}}" +
                "return !!v;})()",
        )
    }

    private fun js(code: String) = web?.evaluateJavascript(code, null)

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

