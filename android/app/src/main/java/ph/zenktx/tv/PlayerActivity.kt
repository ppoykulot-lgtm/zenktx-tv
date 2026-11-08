package ph.zenktx.tv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.util.Base64
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import ph.zenktx.tv.data.Channel
import ph.zenktx.tv.data.ChannelRepository
import ph.zenktx.tv.data.LibraryStore
import ph.zenktx.tv.databinding.ActivityPlayerBinding
import ph.zenktx.tv.net.Http
import ph.zenktx.tv.ui.Logos
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Full-screen live player.
 *
 * - streams HLS (.m3u8) and DASH (.mpd) through Media3
 * - CH+ / CH- (or D-pad up/down) hops between channels in the current filter
 * - the ZENKTX control bar carries the star, the volume wheel, the time, the
 *   quality switch and the close button, so it matches the desktop player
 * - a watchdog turns a silent black screen into an actionable message
 */
@UnstableApi
class PlayerActivity : Activity() {

    companion object {
        const val EXTRA_QUEUE = "channel_queue"
        const val EXTRA_INDEX = "channel_index"
        const val EXTRA_URL = "channel_url"
        const val EXTRA_NAME = "channel_name"
        const val EXTRA_LOGO = "channel_logo"

        private const val STALL_MS = 18_000L
        private const val CHIP_MS = 3_500L
        private const val CONTROLS_MS = 5_000L
        private const val MAX_RECOVERIES = 2
        private const val RECOVER_DELAY_MS = 900L
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var library: LibraryStore
    private var player: ExoPlayer? = null

    /** Set per channel, before the media source is built. */
    private var clearKey: DrmSessionManager? = null

    private var queue: List<Channel> = emptyList()
    private var index = 0

    private var hasVideo = false
    private var failed = false
    private var recoveries = 0
    private var startedAt = 0L
    private var lastCode = ""

    /** This channel's address and its mirrors, in the order they are tried. */
    private var sources: List<String> = emptyList()
    private var sourceIndex = 0

    /** One fresh pull of the publisher's feed per channel, not more. */
    private var liveRetried = false

    /** Set from the HTTP failure so a dead link can be told apart from a codec. */
    private var lastStatus: Int? = null

    /** Height chosen in the quality menu; 0 means Auto. */
    private var qualityHeight = 0
    private var draggingSeek = false
    private var savedVolume = 1f

    /**
     * The TV layout has no star, no ✕ and no CH buttons: a remote changes
     * channel on the picture and leaves with BACK. The phone layout still
     * carries all four, so they are found by name and may be missing.
     */
    private var favView: TextView? = null
    private var tvDpad = false

    private val main = Handler(Looper.getMainLooper())

    private val hideChip = Runnable {
        binding.channelChip.animate().alpha(0f).setDuration(250).withEndAction {
            binding.channelChip.visibility = View.GONE
        }.start()
    }

    private val hideControls = Runnable { setControlsVisible(false) }

    /** Keeps the clock and the seek bar honest without touching the video. */
    private val ticker = object : Runnable {
        override fun run() {
            updateTime()
            main.postDelayed(this, 500)
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            val exo = player
            if (exo == null || failed) return
            val elapsed = SystemClock.elapsedRealtime() - startedAt

            if (elapsed > STALL_MS) {
                if (!hasVideo) {
                    fail(getString(R.string.err_stall, getString(R.string.player_no_video)), "NO_VIDEO")
                    return
                }
                if (exo.playbackState == Player.STATE_BUFFERING) {
                    fail(getString(R.string.err_stall, "buffering"), "STALLED")
                    return
                }
            }
            main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        library = LibraryStore(this)
        goFullscreen()

        resolveQueue()
        if (queue.isEmpty()) {
            finish()
            return
        }

        tvDpad = resources.getBoolean(R.bool.tv_dpad)
        favView = optionalView("btnFav")

        favView?.setOnClickListener { toggleCurrentFavourite() }
        optionalView<View>("btnClose")?.setOnClickListener { finish() }
        optionalView<View>("btnPrevChannel")?.setOnClickListener { switchChannel(-1) }
        optionalView<View>("btnNextChannel")?.setOnClickListener { switchChannel(1) }
        binding.btnRetry.setOnClickListener { playCurrent() }
        binding.btnNext.setOnClickListener {
            if (queue.size < 2) finish() else switchChannel(1)
        }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlay.setOnClickListener { togglePlayback() }
        binding.btnMute.setOnClickListener { toggleMute() }
        binding.btnQuality.setOnClickListener { showQuality() }

        binding.volBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                player?.volume = value / 100f
                savedVolume = if (value == 0) savedVolume else value / 100f
                binding.btnMute.setImageResource(
                    if (value == 0) R.drawable.ic_mute else R.drawable.ic_volume
                )
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) = Unit
            override fun onStartTrackingTouch(bar: SeekBar) {
                draggingSeek = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                draggingSeek = false
                val exo = player ?: return
                val duration = exo.duration
                if (duration > 0) exo.seekTo((duration * bar.progress) / 1000L)
            }
        })

        // A tap on the picture shows or hides the bar.
        binding.playerView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) toggleControls()
            true
        }

        playCurrent()
        showControls()
        main.post(ticker)
    }

    /** A view only one flavour's layout carries, looked up without R.id. */
    private fun <T : View> optionalView(name: String): T? {
        val id = resources.getIdentifier(name, "id", packageName)
        @Suppress("UNCHECKED_CAST")
        return if (id == 0) null else findViewById(id) as T?
    }

    /**
     * Playback owns the whole screen. On a phone the status and navigation bars
     * used to sit on top of the picture; a swipe from the edge brings them back
     * for a moment without shrinking the video.
     */
    private fun goFullscreen() {
        runCatching {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, binding.root).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }

    private fun resolveQueue() {
        val keys = intent.getStringArrayListExtra(EXTRA_QUEUE).orEmpty()
        queue = keys.mapNotNull { key -> ChannelRepository.byKey(key) }

        if (queue.isEmpty()) {
            val url = intent.getStringExtra(EXTRA_URL).orEmpty()
            if (url.isBlank()) return
            queue = listOf(
                Channel(
                    key = "intent",
                    id = 0,
                    name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Channel" },
                    slug = "",
                    category = "",
                    logo = intent.getStringExtra(EXTRA_LOGO).orEmpty(),
                    url = url,
                    streamType = ChannelRepository.streamTypeOf(url),
                    quality = "",
                    isFilipino = false,
                )
            )
        }
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, queue.lastIndex)
    }

    // ---- controls ---------------------------------------------------------

    private fun showControls() {
        setControlsVisible(true)
        main.removeCallbacks(hideControls)
        main.postDelayed(hideControls, CONTROLS_MS)
        // A remote has to land on something: the play button is the leftmost
        // stop, so OK on the picture then ◀▶ walks the bar from there.
        if (tvDpad) binding.btnPlay.requestFocus()
    }

    private fun toggleControls() = setControlsVisible(binding.controls.visibility != View.VISIBLE)

    private fun setControlsVisible(visible: Boolean) {
        binding.controls.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            main.removeCallbacks(hideControls)
            main.postDelayed(hideControls, CONTROLS_MS)
        }
    }

    private fun togglePlayback() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
        showPlayState()
        showControls()
    }

    private fun showPlayState() {
        val playing = player?.isPlaying == true || player?.playWhenReady == true
        binding.btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun toggleMute() {
        val exo = player ?: return
        if (exo.volume > 0f) {
            savedVolume = exo.volume
            exo.volume = 0f
        } else {
            exo.volume = if (savedVolume > 0f) savedVolume else 1f
        }
        binding.volBar.progress = (exo.volume * 100).toInt()
        binding.btnMute.setImageResource(
            if (exo.volume == 0f) R.drawable.ic_mute else R.drawable.ic_volume
        )
        showControls()
    }

    /** Clock, or position when the stream lets us seek. */
    private fun updateTime() {
        val exo = player ?: return
        val duration = exo.duration
        val position = exo.currentPosition.coerceAtLeast(0L)

        if (duration > 0) {
            binding.pTime.text = clock(position)
            binding.pTotal.text = clock(duration)
            if (!draggingSeek) {
                binding.seekBar.progress = ((position * 1000) / duration).toInt()
            }
            binding.liveBadge.visibility = View.GONE
        } else {
            // A live playlist rarely exposes a duration; the wall clock is the
            // only time that means anything, and the badge says it is live.
            binding.pTime.text = clock(System.currentTimeMillis())
            binding.pTotal.text = "LIVE"
            binding.seekBar.progress = 0
            binding.liveBadge.visibility = View.VISIBLE
        }
        showPlayState()
    }

    private fun clock(ms: Long): String {
        val total = ms / 1000
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Quality follows the desktop bar: Auto lets Media3 pick, and a number pins
     * the tallest track at or below that height. A live HLS master playlist
     * carries its variants, so this is the same ladder the player already has.
     */
    private fun showQuality() {
        val exo = player ?: return
        val heights = linkedSetOf<Int>()
        for (group in exo.currentTracks.groups) {
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                if (format.height > 0) heights += format.height
            }
        }

        val options = ArrayList<String>()
        val values = ArrayList<Int>()
        options += getString(R.string.player_quality_auto)
        values += 0
        heights.sortedDescending().forEach {
            options += "${it}p"
            values += it
        }

        val checked = values.indexOf(qualityHeight).coerceAtLeast(0)
        AlertDialog.Builder(this, R.style.Theme_Zenktx_Dialog)
            .setTitle(R.string.player_quality)
            .setSingleChoiceItems(options.toTypedArray(), checked) { dialog, which ->
                qualityHeight = values[which]
                applyQuality(qualityHeight)
                dialog.dismiss()
                showControls()
            }
            .show()
    }

    private fun applyQuality(height: Int) {
        val exo = player ?: return
        val params = exo.trackSelectionParameters.buildUpon()
        if (height <= 0) {
            params.clearOverrides()
            params.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
            params.setMaxVideoBitrate(Int.MAX_VALUE)
            binding.btnQuality.text = getString(R.string.player_quality_auto)
        } else {
            params.setMaxVideoSize(Int.MAX_VALUE, height)
            params.setMaxVideoBitrate(Int.MAX_VALUE)
            binding.btnQuality.text = "${height}p"
        }
        exo.trackSelectionParameters = params.build()
    }

    // ---- favourites -------------------------------------------------------

    /** The star follows the channel on screen, including after a channel hop. */
    private fun showFavouriteState() {
        val star = favView ?: return
        val channel = queue.getOrNull(index) ?: return
        val saved = library.isFavourite(channel.key)
        star.text = getString(if (saved) R.string.favourite_on else R.string.favourite_off)
        star.alpha = if (saved) 1f else 0.72f
    }

    private fun toggleCurrentFavourite() {
        val channel = queue.getOrNull(index) ?: return
        val saved = library.toggleFavourite(channel.key)
        showFavouriteState()
        showControls()
        Toast.makeText(
            this,
            getString(if (saved) R.string.action_fav_saved else R.string.action_fav_removed, channel.name),
            Toast.LENGTH_SHORT,
        ).show()
    }

    // ---- playback ---------------------------------------------------------

    private fun playCurrent() {
        val channel = queue.getOrNull(index) ?: return
        sources = ChannelRepository.sourcesFor(channel)
        sourceIndex = 0
        liveRetried = false
        binding.errorPanel.visibility = View.GONE
        showChip(channel)
        prepareSource()
        if (qualityHeight > 0) applyQuality(qualityHeight)
    }

    /** Prepares the address at [sourceIndex]; a mirror swap leaves the rest alone. */
    private fun prepareSource() {
        val channel = queue.getOrNull(index) ?: return
        val url = sources.getOrNull(sourceIndex) ?: channel.url

        failed = false
        hasVideo = false
        recoveries = 0
        lastCode = ""
        lastStatus = null
        startedAt = SystemClock.elapsedRealtime()

        binding.errorPanel.visibility = View.GONE
        binding.buffering.visibility = View.VISIBLE

        val exo = player ?: buildPlayer().also { player = it }

        clearKey = clearKeyManager(channel)

        binding.playerView.player = exo
        exo.clearMediaItems()
        exo.setMediaItem(mediaItemFor(channel, url))
        exo.prepare()
        exo.playWhenReady = true

        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 1_000)
    }

    /**
     * The address decides the container, not the feed's streamType: that field
     * goes stale when a provider swaps a channel between HLS and DASH, and an
     * .mpd handed to the HLS parser fails before it requests a single segment.
     */
    private fun mediaItemFor(channel: Channel, url: String): MediaItem {
        val clean = url.substringBefore('?').lowercase()
        val type = when {
            clean.endsWith(".mpd") -> "mpd"
            clean.endsWith(".m3u8") -> "hls"
            clean.endsWith(".mp4") -> "mp4"
            else -> channel.streamType
        }
        val mime = when (type) {
            "mpd" -> MimeTypes.APPLICATION_MPD
            "hls" -> MimeTypes.APPLICATION_M3U8
            "mp4" -> MimeTypes.VIDEO_MP4
            else -> null
        }
        return MediaItem.Builder()
            .setUri(url)
            .apply { if (mime != null) setMimeType(mime) }
            .build()
    }

    private fun buildPlayer(): ExoPlayer {
        // A browser-like UA fixes most HTTP 403/404 failures from CDNs that
        // reject the default ExoPlayer agent.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.9",
                )
            )

        val sourceFactory = DefaultMediaSourceFactory(http)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(2))
            // Only consulted when the manifest turns out to be encrypted.
            .setDrmSessionManagerProvider { clearKey ?: DrmSessionManager.DUMMY }

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sourceFactory)
            .build()

        exo.volume = savedVolume

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                binding.buffering.visibility =
                    if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE

                if (state == Player.STATE_ENDED) {
                    // A live playlist ends for a moment while the provider
                    // re-tokenises it. Try again before reporting it as gone.
                    if (!retryQuietly() && !nextSource()) {
                        fail(getString(R.string.err_empty), "STREAM_ENDED")
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                lastCode = error.errorCodeName
                lastStatus =
                    (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                if (!retryQuietly() && !nextSource()) fail(describe(error), error.errorCodeName)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                showPlayState()
                if (isPlaying) clearFailure()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) {
                    hasVideo = true
                    clearFailure()
                }
            }
        })
        return exo
    }

    /**
     * ClearKey is the only protection the publisher ships that opens without a
     * licence server: the manifest names the key id and the feed carries the
     * key. Media3 decrypts it through the platform MediaDrm ClearKey UUID.
     */
    private fun clearKeyManager(channel: Channel): DrmSessionManager? {
        if (channel.drmType != "clearkey") return null
        val kid = base64Url(channel.keyId) ?: return null
        val key = base64Url(channel.drmKey) ?: return null

        val request = JSONObject()
            .put(
                "keys",
                JSONArray().put(
                    JSONObject().put("kty", "oct").put("kid", kid).put("k", key)
                )
            )
            .put("type", "temporary")

        val provider = ExoMediaDrm.Provider {
            FrameworkMediaDrm.newInstance(C.CLEARKEY_UUID)
        }
        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, provider)
            .build(LocalMediaDrmCallback(request.toString().toByteArray(Charsets.UTF_8)))
    }

    /** ClearKey takes the id and key as 16 raw bytes, base64url, unpadded. */
    private fun base64Url(hex: String): String? {
        val clean = hex.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        if (clean.length != 32) return null
        val bytes = ByteArray(16) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * Re-prepare the same channel after a transient end or error. The panel is
     * a report about the stream, so it only appears once the channel has failed
     * [MAX_RECOVERIES] times in a row instead of after the first hiccup.
     */
    private fun retryQuietly(): Boolean {
        val exo = player ?: return false
        if (isFinishing || recoveries >= MAX_RECOVERIES) return false

        recoveries++
        failed = false
        hasVideo = false
        startedAt = SystemClock.elapsedRealtime()

        binding.errorPanel.visibility = View.GONE
        binding.buffering.visibility = View.VISIBLE

        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 1_000)
        main.postDelayed({
            if (isFinishing || failed) return@postDelayed
            exo.prepare()
            exo.playWhenReady = true
        }, RECOVER_DELAY_MS)
        return true
    }

    /**
     * Windows walks this chain before it reports anything: another address for
     * the same feed, then the publisher's feed pulled again when the link has
     * expired. The error card is the last resort, not the first answer.
     */
    private fun nextSource(): Boolean {
        if (sourceIndex + 1 < sources.size) {
            sourceIndex++
            prepareSource()
            return true
        }
        if (!liveRetried && isLinkDead()) {
            liveRetried = true
            refreshLiveAndRetry()
            return true
        }
        return false
    }

    /** 410 is the provider's "this token is gone"; the other three mean the same. */
    private fun isLinkDead(): Boolean {
        val status = lastStatus ?: return false
        return status == 403 || status == 404 || status == 410 || status == 451
    }

    /**
     * Pulls the feed again and retries with whatever address it carries now.
     * The fetch is a network call, so it stays off the main thread and the
     * result is applied back on it.
     */
    private fun refreshLiveAndRetry() {
        val channel = queue.getOrNull(index) ?: return
        binding.errorPanel.visibility = View.GONE
        binding.buffering.visibility = View.VISIBLE

        Thread {
            val fresh = runCatching { ChannelRepository.refreshLive(this) }.getOrDefault(false)
            main.post {
                if (isFinishing) return@post
                if (!fresh) {
                    fail(getString(R.string.err_link), "LINK_EXPIRED")
                    return@post
                }

                val updated = ChannelRepository.byKey(channel.key) ?: channel
                queue = queue.toMutableList().also { it[index] = updated }
                sources = ChannelRepository.sourcesFor(updated)
                sourceIndex = 0
                prepareSource()
            }
        }.start()
    }

    /** Frames are back: drop the panel and let the watchdog guard again. */
    private fun clearFailure() {
        if (binding.errorPanel.visibility != View.VISIBLE) return
        binding.errorPanel.visibility = View.GONE
        if (!failed) return

        failed = false
        recoveries = 0
        startedAt = SystemClock.elapsedRealtime()
        binding.buffering.visibility = View.GONE
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 1_000)
    }

    private fun describe(error: PlaybackException): String {
        val status = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        return when {
            status == 403 || status == 404 || status == 410 || status == 451 ->
                getString(R.string.err_link)

            status != null -> getString(R.string.err_http, status)
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                getString(R.string.err_network)

            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                getString(R.string.err_timeout)

            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                getString(R.string.err_codec)

            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                getString(R.string.err_generic, error.errorCodeName)

            else -> getString(R.string.err_generic, error.errorCodeName)
        }
    }

    private fun fail(message: String, code: String) {
        if (isFinishing) return
        failed = true
        main.removeCallbacks(watchdog)

        binding.buffering.visibility = View.GONE
        binding.errorTitle.text = queue.getOrNull(index)?.name ?: getString(R.string.app_name)
        binding.errorBody.text = message
        binding.errorCode.text = if (code.isBlank()) "STREAM" else code
        binding.errorPanel.visibility = View.VISIBLE
        binding.errorPanel.alpha = 0f
        binding.errorPanel.animate().alpha(1f).setDuration(180).start()
        binding.btnRetry.requestFocus()
    }

    // ---- channel hopping --------------------------------------------------

    /**
     * Channel hop. On a TV the banner is the whole feedback: raising the control
     * bar over the picture after every ▲ press is what made zapping feel wrong.
     */
    private fun switchChannel(delta: Int) {
        if (queue.isEmpty()) return
        index = ((index + delta) % queue.size + queue.size) % queue.size
        setControlsVisible(false)
        main.removeCallbacks(hideControls)
        playCurrent()
        if (!tvDpad) showControls()
    }

    private fun showChip(channel: Channel) {
        binding.channelName.text = channel.name
        binding.channelMeta.text = getString(R.string.player_channel, index + 1, queue.size) +
            "  \u00b7  " + channel.category.uppercase()
        Logos.apply(binding.channelLogo, channel.logo, channel.name)

        binding.channelChip.animate().cancel()
        binding.channelChip.alpha = 1f
        binding.channelChip.visibility = View.VISIBLE

        main.removeCallbacks(hideChip)
        main.postDelayed(hideChip, CHIP_MS)

        showFavouriteState()
        showPlayState()
    }

    // ---- input ------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val inErrorPanel = binding.errorPanel.visibility == View.VISIBLE
        val controlsUp = binding.controls.visibility == View.VISIBLE
        return when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                switchChannel(1); true
            }

            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                switchChannel(-1); true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                togglePlayback(); true
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (!controlsUp) {
                    showControls(); true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }

            // On the picture, all four directions zap: a remote that has to be
            // pointed at a pair of tiny CH buttons is not a TV remote.
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (inErrorPanel || controlsUp) {
                    super.onKeyDown(keyCode, event)
                } else {
                    switchChannel(1); true
                }
            }

            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (inErrorPanel || controlsUp) {
                    super.onKeyDown(keyCode, event)
                } else {
                    switchChannel(-1); true
                }
            }

            // BACK closes the bar first and the player second, so a mistaken
            // press never drops the viewer back into the guide.
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_BACK -> {
                if (controlsUp && !inErrorPanel) setControlsVisible(false) else finish()
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onStart() {
        super.onStart()
        if (!failed) player?.playWhenReady = true
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }
}
