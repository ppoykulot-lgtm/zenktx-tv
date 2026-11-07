package ph.zenktx.tv

import android.animation.ObjectAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ph.zenktx.tv.data.AppSettings
import ph.zenktx.tv.data.Channel
import ph.zenktx.tv.data.ChannelRepository
import ph.zenktx.tv.data.GitHubStore
import ph.zenktx.tv.data.Integrity
import ph.zenktx.tv.data.LibraryStore
import ph.zenktx.tv.data.PlaylistStore
import ph.zenktx.tv.data.ProjectStatus
import ph.zenktx.tv.databinding.ActivityMainBinding
import ph.zenktx.tv.ui.CategoryChipAdapter
import ph.zenktx.tv.ui.ChannelGridAdapter
import ph.zenktx.tv.ui.Logos
import ph.zenktx.tv.ui.Dialogs
import ph.zenktx.tv.ui.Ui
import ph.zenktx.tv.ui.Updater
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home screen -- Design B (Guide): category chips, EPG grid, detail strip.
 * Everything is reachable with a D-pad.
 */
class MainActivity : Activity() {

    companion object {
        private val FAVOURITES = "\u2605 Favourites"
        private val PINOY = "Pinoy"
        private val ALL = "All"

        /**
         * Just long enough that the mark does not flash and vanish. The guide
         * used to be held back a full ten seconds after the catalogue had
         * already arrived, which read as a hang rather than a splash.
         */
        private const val WELCOME_MIN_MS = 1_200L

        /**
         * Ceiling as well as a floor. The catalogue is a network read, and on a
         * slow box it took ten seconds or more: the splash sat there saying
         * "Loading channels…" long after the app looked broken. The guide comes
         * up on this timer whatever the read is doing, then fills itself in.
         */
        private const val WELCOME_MAX_MS = 2_500L

        /** A channel that dies while the guide is open is replaced this often. */
        private const val REFRESH_EVERY_MS = 10 * 60 * 1000L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var library: LibraryStore
    private lateinit var playlists: PlaylistStore
    private lateinit var github: GitHubStore
    private lateinit var settings: AppSettings

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var all: List<Channel> = emptyList()
    private var visible: List<Channel> = emptyList()
    private var categories: List<String> = listOf(ALL, FAVOURITES, PINOY)
    private var filter: String = ALL
    private var loadError: String? = null
    private var query: String = ""

    private lateinit var chips: CategoryChipAdapter
    private lateinit var grid: ChannelGridAdapter

    // Neither build shows a detail strip: the TV replaced it with a D-pad
    // legend, the phone with a search bar. The search field itself only exists
    // on the phone, so every view here is looked up and may be null.
    private var searchField: EditText? = null
    private var searchClearView: View? = null

    private var welcomeAt = 0L
    private var welcomeAnim: ObjectAnimator? = null

    private val clockHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val clockTick = object : Runnable {
        override fun run() {
            binding.clock.text = timeFormat.format(Date())
            clockHandler.postDelayed(this, 15_000)
        }
    }

    /** Replaces the catalogue in place while the guide stays usable. */
    private val refreshTick = object : Runnable {
        override fun run() {
            loadChannels(silent = true)
            clockHandler.postDelayed(this, REFRESH_EVERY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val verdict = Integrity.check(this)
        if (!verdict.ok) {
            showTamperDialog(verdict.message)
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyFullView()

        library = LibraryStore(this)
        playlists = PlaylistStore(this)
        github = GitHubStore(this)
        settings = AppSettings(this)

        chips = CategoryChipAdapter(
            onSelected = { onChipSelected(it) },
            // One row on every build. Wrapped, the band grew a second row that
            // shoved the grid down and dragged the whole guide under the
            // highlight; one row that slides keeps the guide still.
            onFocused = { pos -> binding.chips.smoothScrollToPosition(pos) },
        )
        binding.chips.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.chips.adapter = chips

        grid = ChannelGridAdapter(
            onSelect = { },
            onOpen = { openPlayer(it) },
            onFocused = { },
            onLongPress = { toggleFavourite(it) },
            isFavourite = { library.isFavourite(it) },
        )
        val columns = resources.getInteger(R.integer.grid_columns)
        binding.grid.layoutManager = GridLayoutManager(this, columns)
        binding.grid.adapter = grid
        binding.grid.itemAnimator = null

        Ui.attachFocusEffect(binding.menuButton, scale = 1.04f)
        binding.menuButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        bindGuideViews()

        startWelcome()
        applyFilter(ALL)
        loadChannels()
        scheduleUpdateCheck()
    }

    // ---- full view --------------------------------------------------------

    /**
     * Full view: the guide owns the whole display. The clock strip at the top and
     * the button strip at the bottom are hidden rather than merely drawn behind,
     * so the phone shows nothing but the app. Only a display cutout still takes
     * its notch out of the padding. A swipe from an edge brings the bars back for
     * a moment, and [onWindowFocusChanged] hides them again.
     */
    private fun applyFullView() {
        if (!resources.getBoolean(R.bool.full_view)) return

        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT

        val hideBars = resources.getBoolean(R.bool.hide_system_bars)
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            if (hideBars) {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        val baseTop = binding.content.paddingTop
        val baseBottom = binding.content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            // While the bars are hidden their inset is zero, so the guide starts
            // at the top of the screen instead of below an empty strip.
            view.setPadding(
                view.paddingLeft,
                (if (hideBars) 0 else bars.top) + cut.top + baseTop,
                view.paddingRight,
                (if (hideBars) 0 else bars.bottom) + cut.bottom + baseBottom,
            )
            insets
        }
        binding.content.requestApplyInsets()
    }

    /** Bars come back after a swipe or a dialog; hide them again on focus. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android hands the first focus to whatever focusable it meets first,
        // which is the MENU pill in the header. The guide is the screen, so the
        // grid takes it back as soon as the window settles.
        if (hasFocus && ::binding.isInitialized &&
            binding.welcomePanel.root.visibility != View.VISIBLE
        ) {
            focusFirstTile()
        }
        if (!hasFocus || !::binding.isInitialized) return
        if (!resources.getBoolean(R.bool.full_view)) return
        if (!resources.getBoolean(R.bool.hide_system_bars)) return
        WindowInsetsControllerCompat(window, binding.root)
            .hide(WindowInsetsCompat.Type.systemBars())
    }

    // ---- welcome ----------------------------------------------------------

    /**
     * The guide stays behind this screen until the published catalogue has been
     * fetched, so every launch starts from the current channel list: a channel
     * added, renamed or fixed in the admin page is there the next time the app
     * opens instead of after a cache that used to last half an hour.
     */
    private fun startWelcome() {
        welcomeAt = SystemClock.elapsedRealtime()
        binding.welcomePanel.root.visibility = View.VISIBLE
        welcomeAnim = ObjectAnimator.ofFloat(binding.welcomePanel.welcomeGlow, View.ALPHA, 0.55f, 1f)
            .apply {
                duration = 1_400
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                start()
            }
        // A ceiling as well as a floor: a slow catalogue read used to keep this
        // screen up for ten seconds or more, which read as a hang.
        binding.root.postDelayed({ hideWelcome() }, WELCOME_MAX_MS)
    }

    private fun hideWelcome() {
        if (!::binding.isInitialized) return
        val panel = binding.welcomePanel.root
        if (panel.visibility != View.VISIBLE) return
        val remaining = WELCOME_MIN_MS - (SystemClock.elapsedRealtime() - welcomeAt)
        panel.postDelayed({
            if (isFinishing) return@postDelayed
            welcomeAnim?.cancel()
            welcomeAnim = null
            panel.animate().alpha(0f).setDuration(280).withEndAction {
                panel.visibility = View.GONE
                panel.alpha = 1f
                // The guide was filled behind the splash, so the D-pad had no
                // tile to sit on: the first press of OK did nothing at all.
                focusFirstTile()
            }.start()
        }, remaining.coerceAtLeast(0L))
    }

    /**
     * The kill switch: the welcome screen becomes the notice and nothing else
     * loads. There is no "continue" button, and the state survives a restart
     * without a network, because the last published answer is cached.
     */
    private fun showStopped(message: String) {
        clockHandler.removeCallbacks(refreshTick)
        binding.welcomePanel.welcomeLoading.visibility = View.GONE
        binding.welcomePanel.welcomeNotice.visibility = View.VISIBLE
        binding.welcomePanel.welcomeNotice.text =
            message.ifBlank { getString(R.string.maintenance_body) }

        binding.content.visibility = View.GONE
    }

    // ---- loading ----------------------------------------------------------

    private fun loadChannels(silent: Boolean = false) {
        if (!silent) {
            binding.status.visibility = View.VISIBLE
            binding.status.text = getString(R.string.loading)
        }

        scope.launch {
            // Paint the list that ships inside the APK before touching the
            // network. The read below takes seconds on a slow box, and a guide
            // that says "0 channels" for that long looks broken.
            if (all.isEmpty()) {
                val quick = withContext(Dispatchers.IO) {
                    runCatching { ChannelRepository.bundled(this@MainActivity) }
                        .getOrDefault(emptyList())
                }
                if (isFinishing) return@launch
                if (quick.isNotEmpty()) {
                    all = quick
                    categories = buildCategories(all)
                    applyFilter(if (filter in categories) filter else ALL)
                    if (!silent) focusFirstTile()
                }
            }

            val stopped = withContext(Dispatchers.IO) {
                runCatching { ProjectStatus.current(this@MainActivity) }.getOrNull()
            }
            if (isFinishing) return@launch
            if (stopped?.stopped == true) {
                showStopped(stopped.message)
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                runCatching { ChannelRepository.load(this@MainActivity, playlists, library) }
                    .getOrElse { ChannelRepository.Result(emptyList(), listOf(it.message ?: "load failed")) }
            }
            if (isFinishing) return@launch

            all = result.channels
            categories = buildCategories(all)

            loadError = result.errors.takeIf { it.isNotEmpty() }?.let {
                getString(R.string.playlist_errors, it.size, it.first())
            }
            applyFilter(if (filter in categories) filter else ALL)
            if (!silent) focusFirstTile()
            hideWelcome()

            clockHandler.removeCallbacks(refreshTick)
            clockHandler.postDelayed(refreshTick, REFRESH_EVERY_MS)
        }
    }

    private fun buildCategories(channels: List<Channel>): List<String> {
        val preferred = listOf(
            "News", "Sports", "Movies", "Kids",
            "Entertainment", "Documentary", "Music", "International"
        )
        val found = channels.map { it.category }.toSet()
        val ordered = preferred.filter { it in found } + found.filter { it !in preferred }.sorted()
        return listOf(ALL, FAVOURITES, PINOY) + ordered
    }

    // ---- search -----------------------------------------------------------

    /**
     * A plain query dialog rather than a search field in the header: on a phone
     * the keyboard covers the grid anyway, and on the TV a field in the header
     * is one more stop for the D-pad before the guide.
     */
    private fun showSearch() {
        Dialogs.prompt(
            this,
            getString(R.string.search),
            getString(R.string.search_hint),
            initial = query,
            uri = false,
        ) { value ->
            query = value.trim()
            if (query.isBlank()) {
                applyFilter(if (filter in categories) filter else ALL)
            } else {
                showResults(query)
            }
        }
    }

    private fun showResults(text: String) {
        val needle = text.lowercase()
        visible = all.filter {
            it.name.lowercase().contains(needle) || it.category.lowercase().contains(needle)
        }
        grid.submit(visible)
        binding.grid.scrollToPosition(0)
        binding.channelCount.text = getString(R.string.channel_count, visible.size)
        binding.status.visibility = View.VISIBLE
        binding.status.text = if (visible.isEmpty()) {
            getString(R.string.search_empty, text)
        } else {
            "\u201c$text\u201d  \u00b7  ${visible.size}"
        }
    }

    // ---- filtering --------------------------------------------------------

    private fun onChipSelected(category: String) {
        query = ""
        val field = searchField
        if (field != null && !field.text.isNullOrEmpty()) {
            // The phone's search field is the filter too: emptying it runs the
            // watcher, which applies the category that was just tapped.
            filter = category
            field.setText("")
        } else {
            applyFilter(category)
        }
        focusFirstTile()
    }

    private fun applyFilter(category: String) {
        filter = category
        visible = when (category) {
            ALL -> all
            FAVOURITES -> all.filter { library.isFavourite(it.key) }
            PINOY -> all.filter { it.isFilipino }
            else -> all.filter { it.category == category }
        }

        grid.submit(visible)
        chips.submit(categories, filter)
        binding.grid.scrollToPosition(0)
        binding.channelCount.text = getString(R.string.channel_count, visible.size)

        val message = if (visible.isEmpty()) getString(R.string.no_channels) else loadError
        if (message != null) {
            binding.status.visibility = View.VISIBLE
            binding.status.text = message
        } else {
            binding.status.visibility = View.GONE
        }
    }

    /**
     * Explicitly focus the first tile -- requestFocus() on a RecyclerView is not
     * reliable. A tile that has not been laid out yet refuses focus and the
     * header's MENU pill picks it up instead, so this retries a few frames
     * before it settles for the grid itself.
     */
    private fun focusFirstTile(attempt: Int = 0) {
        binding.grid.postDelayed({
            if (isFinishing) return@postDelayed
            val manager = binding.grid.layoutManager as? LinearLayoutManager
            val tile = manager?.findViewByPosition(0)
                ?: binding.grid.findViewHolderForAdapterPosition(0)?.itemView
            if (tile != null && tile.requestFocus()) return@postDelayed
            if (attempt < 5) focusFirstTile(attempt + 1) else binding.grid.requestFocus()
        }, if (attempt == 0) 0L else 60L)
    }

    // ---- search & guide bar ------------------------------------------------

    /**
     * Binds whatever this build's guide actually has. The TV has a search bar
     * under the categories; the phone has a field that filters as it types. The
     * other is absent, so every lookup is optional instead of assumed.
     */
    private fun bindGuideViews() {
        optionalView<View>("searchButton")?.let { view ->
            Ui.attachFocusEffect(view, scale = 1.04f)
            view.setOnClickListener { showSearch() }
        }

        searchField = optionalView("searchInput")
        searchClearView = optionalView("searchClear")
        searchClearView?.setOnClickListener { searchField?.setText("") }
        // The phone filters as it types: a dialog in front of the guide made a
        // search a two-step errand and hid the results behind the keyboard.
        searchField?.doAfterTextChanged { text ->
            val value = text?.toString()?.trim().orEmpty()
            searchClearView?.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
            query = value
            if (value.isEmpty()) applyFilter(if (filter in categories) filter else ALL)
            else showResults(value)
        }
    }

    /**
     * A view only one flavour's layout carries. Looking it up by name keeps the
     * shared activity off R.id, which is generated per flavour: the id of a view
     * the phone no longer has would not compile there at all.
     */
    private fun <T : View> optionalView(name: String): T? {
        val id = resources.getIdentifier(name, "id", packageName)
        @Suppress("UNCHECKED_CAST")
        return if (id == 0) null else findViewById(id) as T?
    }

    private fun toggleFavourite(channel: Channel) {
        val saved = library.toggleFavourite(channel.key)
        grid.refreshBadges()
        if (filter == FAVOURITES && query.isBlank()) applyFilter(FAVOURITES)
        Toast.makeText(
            this,
            getString(
                if (saved) R.string.action_fav_saved else R.string.action_fav_removed,
                channel.name,
            ),
            Toast.LENGTH_SHORT,
        ).show()
    }

    // ---- player -----------------------------------------------------------

    private fun openPlayer(channel: Channel) {
        val index = visible.indexOfFirst { it.key == channel.key }.coerceAtLeast(0)
        val queue = ArrayList(visible.map { it.key })
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putStringArrayListExtra(PlayerActivity.EXTRA_QUEUE, queue)
                .putExtra(PlayerActivity.EXTRA_INDEX, index)
                .putExtra(PlayerActivity.EXTRA_URL, channel.url)
                .putExtra(PlayerActivity.EXTRA_NAME, channel.name)
                .putExtra(PlayerActivity.EXTRA_LOGO, channel.logo)
        )
    }

    // ---- updates & integrity ---------------------------------------------

    private fun scheduleUpdateCheck() {
        if (!settings.autoCheckUpdates()) return
        binding.root.postDelayed({
            if (!isFinishing && !isDestroyed) Updater.check(this, silent = true)
        }, 2_500)
    }

    private fun showTamperDialog(message: String) {
        AlertDialog.Builder(this, R.style.Theme_Zenktx_Dialog)
            .setTitle(R.string.integrity_title)
            .setMessage(message)
            .setPositiveButton(R.string.integrity_close) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        clockHandler.post(clockTick)
        grid.refreshBadges()

        // Playlist sources or GitHub pushes changed: reload in the background.
        if (ChannelRepository.dirty) {
            ChannelRepository.dirty = false
            loadChannels(silent = true)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::binding.isInitialized) clockHandler.removeCallbacks(clockTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        welcomeAnim?.cancel()
        welcomeAnim = null
        clockHandler.removeCallbacksAndMessages(null)
    }
}
