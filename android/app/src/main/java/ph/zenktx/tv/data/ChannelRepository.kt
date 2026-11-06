package ph.zenktx.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ph.zenktx.tv.net.Http
import java.security.MessageDigest

/**
 * Loads every channel the app knows about: the bundled list plus the channel
 * files of all enabled user playlists. Parsing supports both the ZENKTX JSON
 * format and plain M3U/M3U8 playlists, so an IPTV list or a GitHub raw file
 * works without conversion.
 */
object ChannelRepository {

    private const val ASSET = "channels.json"

    // The feed these lists are built from. Every address is re-tokenised daily,
    // so the bundled copy alone goes stale within hours of a release.
    private const val LIVE_FEED =
        "https://dcoderztv-server-default-rtdb.asia-southeast1.firebasedatabase.app/channels.json"
    private const val LIVE_PREFS = "zenktx_live"
    private const val KEY_LIVE_MAP = "map"
    private const val KEY_LIVE_AT = "at"
    // Zero: every launch asks the feed for its current addresses. These links
    // are re-tokenised through the day, and a catalogue channel that was fixed
    // an hour ago must be in the guide now, not after a six-hour cache.
    private const val LIVE_TTL_MS = 0L

    /** One address plus two mirrors, the same budget the Windows build uses. */
    private const val MIRROR_LIMIT = 3

    // The one channel list every build reads. Publishing a change is a commit to
    // this file; the app picks it up on the next launch, so a new, fixed,
    // reordered or removed channel never waits for a new APK.
    private const val CATALOG_URL = "https://ppoykulot-lgtm.github.io/zenktx-tv/catalog.json"
    private const val CATALOG_PREFS = "zenktx_catalog"
    private const val KEY_CATALOG = "json"
    private const val KEY_CATALOG_AT = "at"
    // Zero for the same reason: the welcome screen covers the fetch, and the
    // copies in SharedPreferences and the APK remain the offline fallback.
    private const val CATALOG_TTL_MS = 0L
    private val STREAM_PLAYLIST =
        Regex("#EXT-X-(STREAM-INF|TARGETDURATION|MEDIA-SEQUENCE|I-FRAME-STREAM-INF)")

    data class Result(
        val channels: List<Channel>,
        val errors: List<String> = emptyList(),
    )

    @Volatile
    private var cache: List<Channel> = emptyList()

    /** Everything loaded from disk/network, before local edits are applied. */
    @Volatile
    private var base: List<Channel> = emptyList()

    /** The rows as they were read, before the live feed rewrote their addresses. */
    @Volatile
    private var sourceList: List<Channel> = emptyList()

    /** Kept so a live refresh can re-apply the hidden channels and logo overrides. */
    @Volatile
    private var libraryRef: LibraryStore? = null

    /** Set when a playlist source changed and the home screen should reload. */
    @Volatile
    var dirty: Boolean = false

    /** Channels without touching the network: used by the player queue. */
    fun cached(): List<Channel> = cache

    /** Re-applies favourite-independent local edits (hidden channels, logos). */
    fun refresh(library: LibraryStore): List<Channel> {
        val hidden = library.hidden()
        val overrides = library.logoOverrides()
        cache = base
            .filterNot { it.key in hidden }
            .map { channel ->
                when (val override = overrides[channel.key]) {
                    null -> channel
                    "" -> channel.copy(logo = "")
                    else -> channel.copy(logo = override)
                }
            }
        return cache
    }

    fun bundled(context: Context): List<Channel> =
        fromJson(
            context.assets.open(ASSET).bufferedReader().use { it.readText() },
            "b",
        )

    /** Blocking; call from a background thread. Adds remote playlists. */
    fun load(context: Context, playlists: PlaylistStore, library: LibraryStore): Result {
        val errors = ArrayList<String>()
        val out = ArrayList<Channel>(500)

        val bundled = runCatching { catalog(context).ifEmpty { bundled(context) } }
            .onFailure { errors += "Bundled channels: ${it.message}" }
            .getOrDefault(emptyList())
        out += bundled

        for (playlist in playlists.list().filter { it.enabled }) {
            runCatching {
                val text = Http.getText(playlist.url)
                val parsed = parseSource(text, playlist)
                if (parsed.isEmpty()) errors += "${playlist.name}: no channels found"
                out += parsed
            }.onFailure {
                errors += "${playlist.name}: ${it.message ?: "unreachable"}"
            }
        }

        sourceList = out
        libraryRef = library

        // Deduplicate by URL, first source wins.
        val seen = HashSet<String>()
        base = withLiveLinks(context, out).filter { seen.add(it.url) }
        cache = refresh(library)
        return Result(cache, errors)
    }

    /**
     * A source is not always a list: a lone .m3u8 / .mpd, or an M3U master
     * playlist, is one channel. Parsing a media playlist as a list turned every
     * segment into a "Channel N" tile and threw away the title that was typed.
     */
    fun parseSource(text: String, playlist: Playlist): List<Channel> {
        val body = text.trimStart()
        if (body.startsWith("#EXTM3U") || body.startsWith("#EXTINF")) {
            if (!STREAM_PLAYLIST.containsMatchIn(body)) {
                val channels = fromM3u(text, playlist.id)
                if (channels.isNotEmpty()) return channels
            }
            return singleStream(playlist)
        }
        return fromJson(text, playlist.id).ifEmpty { singleStream(playlist) }
    }

    private fun singleStream(playlist: Playlist): List<Channel> = listOf(
        Channel(
            key = "${playlist.id}:single",
            id = 0,
            name = playlist.name.ifBlank {
                playlist.url.substringAfterLast('/').substringBefore('?')
            },
            slug = "",
            category = "Playlist",
            logo = playlist.logo,
            url = playlist.url,
            streamType = streamTypeOf(playlist.url),
            quality = "HD",
            isFilipino = false,
        )
    )

    /** A cache buster: both hosts happily serve yesterday's copy without one. */
    private fun stamped(url: String): String {
        val separator = if (url.contains('?')) '&' else '?'
        return "$url${separator}t=${System.currentTimeMillis()}"
    }

    /**
     * The published catalogue, fetched on every launch. Offline, or if the
     * catalogue moved, the cached copy and then the one inside the APK keep the
     * app usable.
     */
    private fun catalog(context: Context): List<Channel> {
        val prefs = context.applicationContext
            .getSharedPreferences(CATALOG_PREFS, Context.MODE_PRIVATE)

        if (System.currentTimeMillis() - prefs.getLong(KEY_CATALOG_AT, 0L) > CATALOG_TTL_MS) {
            runCatching {
                val text = Http.getText(stamped(CATALOG_URL))
                if (fromJson(text, "b").isNotEmpty()) {
                    prefs.edit()
                        .putString(KEY_CATALOG, text)
                        .putLong(KEY_CATALOG_AT, System.currentTimeMillis())
                        .apply()
                }
            }
        }

        val cached = prefs.getString(KEY_CATALOG, null) ?: return emptyList()
        return runCatching { fromJson(cached, "b") }.getOrDefault(emptyList())
    }

    /**
     * Pulls the publisher's current list, caches it for six hours and moves every
     * bundled channel onto its live address. Channels from the user's own
     * playlists are left alone: those addresses are not ours to rewrite.
     */
    private fun withLiveLinks(context: Context, channels: List<Channel>): List<Channel> {
        val prefs = context.applicationContext.getSharedPreferences(LIVE_PREFS, Context.MODE_PRIVATE)

        if (System.currentTimeMillis() - prefs.getLong(KEY_LIVE_AT, 0L) > LIVE_TTL_MS) {
            runCatching {
                val map = parseLive(Http.getText(stamped(LIVE_FEED)))
                if (map.isNotEmpty()) {
                    val json = JSONObject()
                    map.forEach { (key, entry) -> json.put(key, entry.toJson()) }
                    prefs.edit()
                        .putString(KEY_LIVE_MAP, json.toString())
                        .putLong(KEY_LIVE_AT, System.currentTimeMillis())
                        .apply()
                }
            }
        }

        val map = runCatching {
            val raw = prefs.getString(KEY_LIVE_MAP, null) ?: return channels
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { LiveEntry.from(json.getJSONObject(it)) }
        }.getOrNull() ?: return channels

        return applyLive(channels, map)
    }

    /** Moves every bundled row onto the feed's current address and key pair. */
    private fun applyLive(channels: List<Channel>, map: Map<String, LiveEntry>): List<Channel> {
        if (map.isEmpty()) return channels
        val patched = channels.map { channel ->
            if (!channel.key.startsWith("b:")) return@map channel
            val live = map[liveKey(channel.name)] ?: return@map channel
            channel.copy(
                url = live.url,
                logo = live.logo.ifBlank { channel.logo },
                streamType = live.streamType.ifBlank { channel.streamType },
                quality = live.quality.ifBlank { channel.quality },
                drmType = channel.drmType.ifBlank { live.drmType },
                keyId = channel.keyId.ifBlank { live.keyId },
                drmKey = channel.drmKey.ifBlank { live.drmKey },
            )
        }
        return patched + liveExtras(patched, map)
    }

    /**
     * Pulls the feed again, ignoring the six-hour cache. A link the guide cached
     * this morning can answer 410 tonight while the channel itself is still
     * alive, so the player calls this the moment a channel fails instead of
     * waiting for the cache to expire.
     */
    fun refreshLive(context: Context): Boolean {
        val original = sourceList
        if (original.isEmpty()) return false

        val map = runCatching { parseLive(Http.getText(stamped(LIVE_FEED))) }.getOrDefault(emptyMap())
        if (map.isEmpty()) return false

        runCatching {
            val json = JSONObject()
            map.forEach { (key, entry) -> json.put(key, entry.toJson()) }
            context.applicationContext.getSharedPreferences(LIVE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LIVE_MAP, json.toString())
                .putLong(KEY_LIVE_AT, System.currentTimeMillis())
                .apply()
        }

        val seen = HashSet<String>()
        base = applyLive(original, map).filter { seen.add(it.url) }
        cache = libraryRef?.let { refresh(it) } ?: base
        dirty = true
        return true
    }

    /**
     * The address in the guide first, then the other rows that carry the same
     * feed: a "Stream 2", an HD twin, a leftover duplicate from an imported
     * playlist. Windows walks the same chain, which is why a channel whose first
     * link is dead plays there and used to show the error card here.
     */
    fun sourcesFor(channel: Channel): List<String> {
        val urls = ArrayList<String>(MIRROR_LIMIT)
        urls += channel.url
        val target = mirrorKey(channel.name)
        for (other in cache) {
            if (other.url.isBlank() || urls.contains(other.url)) continue
            if (mirrorKey(other.name) != target) continue
            urls += other.url
            if (urls.size == MIRROR_LIMIT) break
        }
        return urls
    }

    private fun mirrorKey(name: String): String = name.lowercase()
        .replace(Regex("\\b(hd|fhd|sd|uhd)\\b"), " ")
        .replace(Regex("\\bstream\\s*\\d+\\b"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    /**
     * The publisher adds an NBA game stream while the game is on and drops it the
     * next morning, so the catalogue can never list today's game. Anything the
     * feed calls NBA and the guide does not already hold is added here, in its
     * own category, on every refresh.
     */
    private fun liveExtras(channels: List<Channel>, map: Map<String, LiveEntry>): List<Channel> {
        val urls = channels.mapTo(HashSet()) { it.url }
        val extras = ArrayList<Channel>()
        for (entry in map.values) {
            if (!entry.name.contains("nba", ignoreCase = true)) continue
            if (entry.url.isBlank() || !urls.add(entry.url)) continue
            extras += Channel(
                key = "live:${hash(entry.url)}",
                id = 0,
                name = entry.name,
                slug = "",
                category = "NBA Live",
                logo = entry.logo,
                url = entry.url,
                streamType = entry.streamType.ifBlank { streamTypeOf(entry.url) },
                quality = entry.quality.ifBlank { "HD" },
                isFilipino = false,
                drmType = entry.drmType,
                keyId = entry.keyId,
                drmKey = entry.drmKey,
            )
        }
        return extras
    }

    private fun parseLive(text: String): Map<String, LiveEntry> {
        val arr = JSONArray(text)
        val map = HashMap<String, LiveEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            val url = o.optString("url")
            if (name.isBlank() || url.isBlank() || !o.optBoolean("enabled", true)) continue
            val key = liveKey(name)
            if (map.containsKey(key)) continue
            map[key] = LiveEntry(
                name = name,
                url = url,
                logo = o.optString("logo"),
                streamType = o.optString("streamType", streamTypeOf(url)),
                quality = o.optString("quality", "HD"),
                drmType = o.optString("drmType"),
                keyId = o.optString("keyId"),
                drmKey = o.optString("key"),
            )
        }
        return map
    }

    private fun liveKey(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private data class LiveEntry(
        val name: String,
        val url: String,
        val logo: String,
        val streamType: String,
        val quality: String,
        val drmType: String = "",
        val keyId: String = "",
        val drmKey: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("name", name)
            .put("url", url)
            .put("logo", logo)
            .put("streamType", streamType)
            .put("quality", quality)
            .put("drmType", drmType)
            .put("keyId", keyId)
            .put("key", drmKey)

        companion object {
            fun from(o: JSONObject) = LiveEntry(
                name = o.optString("name"),
                url = o.optString("url"),
                logo = o.optString("logo"),
                streamType = o.optString("streamType"),
                quality = o.optString("quality"),
                drmType = o.optString("drmType"),
                keyId = o.optString("keyId"),
                drmKey = o.optString("key"),
            )
        }
    }

    fun byKey(key: String): Channel? = cache.firstOrNull { it.key == key }
    // ---- parsing ----------------------------------------------------------

    private fun looksLikeM3u(text: String): Boolean =
        text.trimStart().startsWith("#EXTM3U") || text.trimStart().startsWith("#EXTINF")

    fun fromJson(text: String, prefix: String): List<Channel> {
        val root = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("channels") ?: return emptyList()
        val list = ArrayList<Channel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            // enabled:false is an address the publisher already found dead;
            // tombstone:true is a channel an admin removed. Neither belongs in
            // the guide.
            if (url.isBlank() || !o.optBoolean("enabled", true) ||
                o.optBoolean("tombstone", false)
            ) continue
            val id = o.optInt("id", i + 1)
            list += Channel(
                key = "$prefix:$id",
                id = id,
                name = o.optString("name", "Channel $id"),
                slug = o.optString("slug"),
                category = o.optString("category", "Entertainment"),
                logo = o.optString("logo"),
                url = url,
                streamType = o.optString("streamType", streamTypeOf(url)),
                quality = o.optString("quality", "HD"),
                isFilipino = o.optBoolean("isFilipino", false),
                drmType = o.optString("drmType"),
                keyId = o.optString("keyId"),
                drmKey = o.optString("drmKey", o.optString("key")),
            )
        }
        return list
    }

    fun fromM3u(text: String, prefix: String): List<Channel> {
        val list = ArrayList<Channel>()
        var name = ""
        var logo = ""
        var group = ""
        var index = 0

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    name = line.substringAfter(',', "").trim()
                    logo = attr(line, "tvg-logo")
                    group = attr(line, "group-title").ifBlank { "Imported" }
                }

                line.isNotEmpty() && !line.startsWith("#") -> {
                    index++
                    val id = index
                    list += Channel(
                        key = "$prefix:${hash(line)}",
                        id = id,
                        name = name.ifBlank { "Channel $id" },
                        slug = "",
                        category = group.ifBlank { "Imported" },
                        logo = logo,
                        url = line,
                        streamType = streamTypeOf(line),
                        quality = if (line.contains("1080")) "FHD" else "HD",
                        isFilipino = isFilipino(name, group),
                    )
                    name = ""
                    logo = ""
                }
            }
        }
        return list
    }

    private fun attr(extinf: String, key: String): String {
        val match = Regex("$key=\"([^\"]*)\"").find(extinf) ?: return ""
        return match.groupValues[1]
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(10)
    }

    fun streamTypeOf(url: String): String {
        val clean = url.substringBefore('?').lowercase()
        return when {
            clean.endsWith(".m3u8") || clean.contains(".m3u8") -> "hls"
            clean.endsWith(".mpd") || clean.contains(".mpd") -> "mpd"
            clean.endsWith(".mp4") -> "mp4"
            else -> "hls"
        }
    }

    private fun isFilipino(name: String, group: String): Boolean {
        val haystack = "$name $group".lowercase()
        return FILIPINO_HINTS.any { haystack.contains(it) }
    }

    private val FILIPINO_HINTS = listOf(
        "philippin", "pinoy", "filipino", "manila", "abs-cbn", "gma", "tv5", "a2z",
        "kapatid", "kapamilya", "gma news", "ptv", "smni", "net25", "one ph", "pba",
        "bilyonaryo", "untv", "deped", "iwant", "gtv", "alltv",
    )
}
