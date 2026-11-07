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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
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

        /** Long enough to read the slogan and the credit under it. */
        private const val WELCOME_MIN_MS = 10_000L

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
    private var detail: Channel? = null
    private var loadError: String? = null
    private var query: String = ""

    private lateinit var chips: CategoryChipAdapter
    private lateinit var grid: ChannelGridAdapter

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
            onFocused = { pos -> binding.chips.smoothScrollToPosition(pos) },
        )
        // The TV guide wraps every category onto its own rows; a phone scrolls a
        // single row instead, because five chip rows ate half the screen.
        binding.chips.layoutManager =
            if (resources.getBoolean(R.bool.chips_wrap)) {
                FlexboxLayoutManager(this, FlexDirection.ROW, FlexWrap.WRAP)
            } else {
                LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            }
        binding.chips.adapter = chips

        grid = ChannelGridAdapter(
            onSelect = { showDetail(it) },
            onOpen = { openPlayer(it) },
            onFocused = { showDetail(it) },
            isFavourite = { library.isFavourite(it) },
            doubleTapToPlay = resources.getBoolean(R.bool.double_tap_play),
        )
        val columns = resources.getInteger(R.integer.grid_columns)
        binding.grid.layoutManager = GridLayoutManager(this, columns)
        binding.grid.adapter = grid
        binding.grid.itemAnimator = null

        Ui.attachFocusEffect(binding.detailWatch)
        Ui.attachFocusEffect(binding.detailFav, scale = 1.04f)
        Ui.attachFocusEffect(binding.menuButton, scale = 1.04f)
        Ui.attachFocusEffect(binding.searchButton, scale = 1.04f)

        binding.detailWatch.setOnClickListener { detail?.let { openPlayer(it) } }
        binding.detailFav.setOnClickListener { detail?.let { toggleFavourite(it) } }
        binding.menuButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.searchButton.setOnClickListener { showSearch() }

        startWelcome()
        applyFilter(ALL)
        loadChannels()
        scheduleUpdateCheck()
    }

    // ---- full view --------------------------------------------------------

    /**
     * Edge to edge: the guide runs behind the status and navigation bars and
     * pads itself by their height instead of leaving two dead strips at the top
     * and bottom of the screen.
     */
    private fun applyFullView() {
        if (!resources.getBoolean(R.bool.full_view)) return

        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = false

        val baseTop = binding.content.paddingTop
        val baseBottom = binding.content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                bars.top + baseTop,
                view.paddingRight,
                bars.bottom + baseBottom,
            )
            insets
        }
        binding.content.requestApplyInsets()
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
        showDetail(visible.firstOrNull())
    }

    // ---- filtering --------------------------------------------------------

    private fun onChipSelected(category: String) {
        query = ""
        applyFilter(category)
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
        showDetail(visible.firstOrNull())
    }

    /** Explicitly focus the first tile -- requestFocus() on a RecyclerView is not reliable. */
    private fun focusFirstTile() {
        binding.grid.post {
            val manager = binding.grid.layoutManager as? LinearLayoutManager
            val tile = manager?.findViewByPosition(0)
            if (tile != null) tile.requestFocus() else binding.grid.requestFocus()
        }
    }

    // ---- detail strip -----------------------------------------------------

    private fun showDetail(channel: Channel?) {
        if (channel == null) {
            detail = null
            binding.detailName.text = ""
            binding.detailTag.text = ""
            binding.detailMeta.text = ""
            binding.detailLogo.setImageDrawable(null)
            binding.detailLogo.visibility = View.GONE
            binding.detailFav.text = getString(R.string.favourite_off)
            return
        }
        detail = channel
        binding.detailName.text = channel.name
        binding.detailTag.text = channel.category.uppercase()
        binding.detailMeta.text = listOf(
            channel.quality,
            channel.streamType.uppercase(),
            if (channel.isFilipino) "PH" else "",
        ).filter { it.isNotBlank() }.joinToString("  \u00b7  ")
        binding.detailFav.text = getString(
            if (library.isFavourite(channel.key)) R.string.favourite_on else R.string.favourite_off
        )
        Logos.apply(binding.detailLogo, channel.logo)
    }

    private fun toggleFavourite(channel: Channel) {
        library.toggleFavourite(channel.key)
        if (detail?.key == channel.key) showDetail(channel)
        grid.refreshBadges()
        if (filter == FAVOURITES && query.isBlank()) applyFilter(FAVOURITES)
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
        showDetail(detail)
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
