package ph.zenktx.tv.data

import android.content.Context
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

    data class Result(
        val channels: List<Channel>,
        val errors: List<String> = emptyList(),
    )

    @Volatile
    private var cache: List<Channel> = emptyList()

    /** Everything loaded from disk/network, before local edits are applied. */
    @Volatile
    private var base: List<Channel> = emptyList()

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

        val bundled = runCatching { bundled(context) }
            .onFailure { errors += "Bundled channels: ${it.message}" }
            .getOrDefault(emptyList())
        out += bundled

        for (playlist in playlists.list().filter { it.enabled }) {
            runCatching {
                val text = Http.getText(playlist.url)
                val parsed = if (looksLikeM3u(text)) fromM3u(text, playlist.id)
                else fromJson(text, playlist.id)
                if (parsed.isEmpty()) errors += "${playlist.name}: no channels found"
                out += parsed
            }.onFailure {
                errors += "${playlist.name}: ${it.message ?: "unreachable"}"
            }
        }

        // Deduplicate by URL, first source wins.
        val seen = HashSet<String>()
        base = out.filter { seen.add(it.url) }
        cache = refresh(library)
        return Result(cache, errors)
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
            if (url.isBlank()) continue
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
