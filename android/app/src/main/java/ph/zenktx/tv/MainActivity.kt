package ph.zenktx.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
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
import ph.zenktx.tv.databinding.ActivityMainBinding
import ph.zenktx.tv.ui.CategoryChipAdapter
import ph.zenktx.tv.ui.ChannelGridAdapter
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

    private lateinit var chips: CategoryChipAdapter
    private lateinit var grid: ChannelGridAdapter

    private val clockHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val clockTick = object : Runnable {
        override fun run() {
            binding.clock.text = timeFormat.format(Date())
            clockHandler.postDelayed(this, 15_000)
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

        library = LibraryStore(this)
        playlists = PlaylistStore(this)
        github = GitHubStore(this)
        settings = AppSettings(this)

        chips = CategoryChipAdapter(
            onSelected = { onChipSelected(it) },
            onFocused = { pos -> binding.chips.smoothScrollToPosition(pos) },
        )
        binding.chips.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.chips.adapter = chips

        grid = ChannelGridAdapter(
            onClick = { openPlayer(it) },
            onLongClick = { showChannelActions(it) },
            onFocused = { showDetail(it) },
            isFavourite = { library.isFavourite(it) },
        )
        val columns = resources.getInteger(R.integer.grid_columns)
        binding.grid.layoutManager = GridLayoutManager(this, columns)
        binding.grid.adapter = grid
        binding.grid.itemAnimator = null

        Ui.attachFocusEffect(binding.detailWatch)
        Ui.attachFocusEffect(binding.detailFav, scale = 1.04f)
        Ui.attachFocusEffect(binding.menuButton, scale = 1.04f)

        binding.detailWatch.setOnClickListener { detail?.let { openPlayer(it) } }
        binding.detailFav.setOnClickListener { detail?.let { toggleFavourite(it) } }
        binding.menuButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        applyFilter(ALL)
        loadChannels()
        scheduleUpdateCheck()
    }

    // ---- loading ----------------------------------------------------------

    private fun loadChannels() {
        binding.status.visibility = View.VISIBLE
        binding.status.text = getString(R.string.loading)

        scope.launch {
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
            focusFirstTile()
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

    // ---- filtering --------------------------------------------------------

    private fun onChipSelected(category: String) {
        if (category == filter) return
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
        binding.detailLogo.load(channel.logo.ifBlank { null }) {
            crossfade(true)
            placeholder(R.drawable.ic_channel_placeholder)
            error(R.drawable.ic_channel_placeholder)
        }
    }

    private fun toggleFavourite(channel: Channel) {
        library.toggleFavourite(channel.key)
        if (detail?.key == channel.key) showDetail(channel)
        grid.refreshBadges()
        if (filter == FAVOURITES) applyFilter(FAVOURITES)
    }

    // ---- channel actions --------------------------------------------------

    private fun showChannelActions(channel: Channel) {
        val actions = ArrayList<Pair<String, () -> Unit>>()

        actions += if (library.isFavourite(channel.key)) {
            getString(R.string.action_fav_remove) to { toggleFavourite(channel) }
        } else {
            getString(R.string.action_fav_add) to { toggleFavourite(channel) }
        }

        actions += getString(R.string.action_logo_set) to {
            Dialogs.prompt(
                this,
                channel.name,
                getString(R.string.logo_hint),
                initial = library.logoOverride(channel.key) ?: channel.logo,
            ) { url ->
                library.setLogoOverride(channel.key, url.ifBlank { "" })
                afterChannelEdit(channel, getString(if (url.isBlank()) R.string.logo_cleared else R.string.logo_saved))
            }
        }

        if (channel.logo.isNotBlank() || library.logoOverride(channel.key) != null) {
            actions += getString(R.string.action_logo_clear) to {
                library.setLogoOverride(channel.key, "")
                afterChannelEdit(channel, getString(R.string.logo_cleared))
            }
        }

        actions += getString(R.string.action_hide) to {
            library.setHidden(channel.key, true)
            ChannelRepository.refresh(library)
            all = ChannelRepository.cached()
            categories = buildCategories(all)
            applyFilter(if (filter in categories) filter else ALL)
        }

        Dialogs.choose(this, channel.name, actions.map { it.first }) { index ->
            actions.getOrNull(index)?.second?.invoke()
        }
    }

    private fun afterChannelEdit(channel: Channel, message: String) {
        all = ChannelRepository.refresh(library)
        grid.submit(visible.map { edited -> all.firstOrNull { it.key == edited.key } ?: edited })
        val updated = all.firstOrNull { it.key == channel.key } ?: channel
        showDetail(updated)
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
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
            loadChannels()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::binding.isInitialized) clockHandler.removeCallbacks(clockTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        clockHandler.removeCallbacksAndMessages(null)
    }
}
