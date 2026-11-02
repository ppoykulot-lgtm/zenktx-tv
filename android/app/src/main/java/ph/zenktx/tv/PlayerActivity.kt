package ph.zenktx.tv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import coil.load
import ph.zenktx.tv.data.Channel
import ph.zenktx.tv.data.ChannelRepository
import ph.zenktx.tv.databinding.ActivityPlayerBinding
import ph.zenktx.tv.net.Http

/**
 * Full-screen live player.
 *
 * - streams HLS (.m3u8) and DASH (.mpd) through Media3
 * - CH+ / CH- (or D-pad up/down) hops between channels in the current filter
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
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null

    private var queue: List<Channel> = emptyList()
    private var index = 0

    private var hasVideo = false
    private var failed = false
    private var startedAt = 0L
    private var lastCode = ""

    private val main = Handler(Looper.getMainLooper())

    private val hideChip = Runnable {
        binding.channelChip.animate().alpha(0f).setDuration(250).withEndAction {
            binding.channelChip.visibility = View.GONE
        }.start()
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

        resolveQueue()
        if (queue.isEmpty()) {
            finish()
            return
        }

        binding.btnRetry.setOnClickListener { playCurrent() }
        binding.btnNext.setOnClickListener {
            if (queue.size < 2) finish() else switchChannel(1)
        }
        binding.btnBack.setOnClickListener { finish() }

        wireControllerButtons()
        playCurrent()
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

    private fun wireControllerButtons() {
        binding.playerView.post {
            binding.playerView.findViewById<View>(R.id.btnPrev)?.setOnClickListener {
                switchChannel(-1)
            }
            binding.playerView.findViewById<View>(R.id.btnNextChannel)?.setOnClickListener {
                switchChannel(1)
            }
        }
    }

    // ---- playback ---------------------------------------------------------

    private fun playCurrent() {
        val channel = queue.getOrNull(index) ?: return

        failed = false
        hasVideo = false
        lastCode = ""
        startedAt = SystemClock.elapsedRealtime()

        binding.errorPanel.visibility = View.GONE
        binding.buffering.visibility = View.VISIBLE
        showChip(channel)

        val exo = player ?: buildPlayer().also { player = it }

        binding.playerView.player = exo
        exo.clearMediaItems()
        exo.setMediaItem(mediaItemFor(channel))
        exo.prepare()
        exo.playWhenReady = true

        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 1_000)
    }

    private fun mediaItemFor(channel: Channel): MediaItem {
        val mime = when (channel.streamType) {
            "mpd" -> MimeTypes.APPLICATION_MPD
            "hls" -> MimeTypes.APPLICATION_M3U8
            "mp4" -> MimeTypes.VIDEO_MP4
            else -> null
        }
        return MediaItem.Builder()
            .setUri(channel.url)
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

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sourceFactory)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                binding.buffering.visibility =
                    if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE

                if (state == Player.STATE_ENDED) {
                    fail(getString(R.string.err_empty), "STREAM_ENDED")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                lastCode = error.errorCodeName
                fail(describe(error), error.errorCodeName)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) hasVideo = true
            }
        })
        return exo
    }

    private fun describe(error: PlaybackException): String {
        val status = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        return when {
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

    private fun switchChannel(delta: Int) {
        if (queue.isEmpty()) return
        index = ((index + delta) % queue.size + queue.size) % queue.size
        playCurrent()
        binding.playerView.requestFocus()
    }

    private fun showChip(channel: Channel) {
        binding.channelName.text = channel.name
        binding.channelMeta.text = getString(R.string.player_channel, index + 1, queue.size) +
            "  \u00b7  " + channel.category.uppercase()
        binding.channelLogo.load(channel.logo.ifBlank { null }) {
            placeholder(R.drawable.ic_channel_placeholder)
            error(R.drawable.ic_channel_placeholder)
        }

        binding.channelChip.animate().cancel()
        binding.channelChip.alpha = 1f
        binding.channelChip.visibility = View.VISIBLE

        main.removeCallbacks(hideChip)
        main.postDelayed(hideChip, CHIP_MS)
    }

    // ---- input ------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val inErrorPanel = binding.errorPanel.visibility == View.VISIBLE
        return when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                switchChannel(1); true
            }

            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                switchChannel(-1); true
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (inErrorPanel) super.onKeyDown(keyCode, event)
                else { switchChannel(1); true }
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (inErrorPanel) super.onKeyDown(keyCode, event)
                else { switchChannel(-1); true }
            }

            KeyEvent.KEYCODE_BACK -> {
                finish(); true
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
