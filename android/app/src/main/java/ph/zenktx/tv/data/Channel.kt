package ph.zenktx.tv.data

/**
 * One live channel.
 *
 * [key] is the stable identity used for favourites, hidden channels and logo
 * overrides. Bundled channels use "b:<id>"; channels that come from a user
 * playlist are namespaced by the playlist id so two playlists can hold the
 * same channel without fighting over the same favourite flag.
 */
data class Channel(
    val key: String,
    val id: Int,
    val name: String,
    val slug: String,
    val category: String,
    val logo: String,
    val url: String,
    val streamType: String,   // "hls" | "mpd" | "other"
    val quality: String,
    val isFilipino: Boolean,
    // ClearKey arrives from the feed as a plain key id + key pair, which is the
    // only protection Media3 can open here: everything else needs a licence
    // server that these providers do not publish.
    val drmType: String = "",
    val keyId: String = "",
    val drmKey: String = "",
)
