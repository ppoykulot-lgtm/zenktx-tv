package ph.zenktx.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A user playlist: an M3U/M3U8 URL, a ZENKTX JSON file, or a GitHub raw file.
 */
data class Playlist(
    val id: String,
    val name: String,
    val url: String,
    val logo: String = "",
    val enabled: Boolean = true,
)

/**
 * Playlist list persisted as JSON in SharedPreferences.
 */
class PlaylistStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("zenktx_playlists", Context.MODE_PRIVATE)

    fun list(): List<Playlist> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Playlist(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    url = o.optString("url"),
                    logo = o.optString("logo"),
                    enabled = o.optBoolean("enabled", true),
                )
            }.filter { it.id.isNotBlank() && it.url.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun add(name: String, url: String, logo: String = ""): Playlist {
        val playlist = Playlist(
            id = UUID.randomUUID().toString().take(8),
            name = name.ifBlank { defaultName(url) },
            url = url.trim(),
            logo = logo.trim(),
        )
        save(list() + playlist)
        return playlist
    }

    fun remove(id: String) = save(list().filterNot { it.id == id })

    fun setEnabled(id: String, on: Boolean) =
        save(list().map { if (it.id == id) it.copy(enabled = on) else it })

    /** Fixes a source that was saved with the wrong name, URL or logo. */
    fun update(id: String, name: String, url: String, logo: String) =
        save(
            list().map {
                if (it.id != id) it
                else it.copy(
                    name = name.ifBlank { defaultName(url) },
                    url = url.trim(),
                    logo = logo.trim(),
                )
            }
        )

    fun setUrl(id: String, url: String) =
        save(list().map { if (it.id == id) it.copy(url = url.trim()) else it })

    private fun save(items: List<Playlist>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("url", it.url)
                    .put("logo", it.logo)
                    .put("enabled", it.enabled)
            )
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun defaultName(url: String): String =
        url.substringAfterLast('/').substringBefore('?').ifBlank { "Playlist" }

    private companion object {
        const val KEY_LIST = "playlists"
    }
}
