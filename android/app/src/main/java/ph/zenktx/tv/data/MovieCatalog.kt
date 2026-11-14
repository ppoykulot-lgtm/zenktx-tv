package ph.zenktx.tv.data

import org.json.JSONObject
import ph.zenktx.tv.net.Http

/**
 * The Movies & Series catalogue published with the site.
 *
 * It is an index plus pages of titles: opening the screen costs one small
 * request and the rest arrives as the reader scrolls. No key ships in any
 * build -- the shells only template a TMDB id into a player address.
 */
data class MovieTitle(
    val id: Int,
    val kind: String,
    val title: String,
    val year: String,
    val rating: Double,
    val overview: String,
    val poster: String,
    val backdrop: String,
    val genres: List<String>,
)

data class PlayerServer(
    val id: String,
    val name: String,
    val movie: String,
    val tv: String,
)

/** One published page: how many titles in total, and the files that hold them. */
data class CatalogPages(val count: Int, val files: List<String>)

data class MovieCatalog(
    val servers: List<PlayerServer>,
    val movies: CatalogPages,
    val series: CatalogPages,
) {
    fun pages(kind: String): CatalogPages = if (kind == "series") series else movies

    /** Fills the season and episode slots for a series, and ignores them for a film. */
    fun address(server: PlayerServer, title: MovieTitle, season: Int, episode: Int): String {
        val template = if (title.kind == "tv") server.tv else server.movie
        return template
            .replace("{id}", title.id.toString())
            .replace("{season}", season.toString())
            .replace("{episode}", episode.toString())
    }

    companion object {
        const val BASE = "https://ppoykulot-lgtm.github.io/zenktx-tv/movies/"

        fun parse(json: String): MovieCatalog {
            val root = JSONObject(json)
            val servers = root.optJSONArray("servers")?.let { array ->
                (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    PlayerServer(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        movie = o.optString("movie"),
                        tv = o.optString("tv"),
                    )
                }
            }.orEmpty()

            fun pages(key: String): CatalogPages {
                val o = root.optJSONObject(key) ?: return CatalogPages(0, emptyList())
                val files = o.optJSONArray("files")?.let { array ->
                    (0 until array.length()).map { array.optString(it) }
                }.orEmpty()
                return CatalogPages(count = o.optInt("count", 0), files = files)
            }

            return MovieCatalog(servers = servers, movies = pages("movies"), series = pages("series"))
        }

        /** Blocking; call from a background thread. */
        fun load(): MovieCatalog = parse(Http.getText("${BASE}index.json?t=${System.currentTimeMillis()}"))

        /**
         * One page of titles. Blocking; call from a background thread. A page that
         * fails to download reads as empty rather than taking the screen down.
         */
        fun loadPage(catalog: MovieCatalog, kind: String, index: Int): List<MovieTitle> {
            val page = catalog.pages(kind)
            val file = page.files.getOrNull(index) ?: return emptyList()
            val json = Https.get("${BASE}${file}?t=${System.currentTimeMillis()}") ?: return emptyList()
            val root = JSONObject(json)
            val array = root.optJSONArray("items") ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                title(array.optJSONObject(i) ?: return@mapNotNull null)
            }
        }

        private fun title(o: JSONObject): MovieTitle? {
            if (o.optString("title").isBlank() || o.optString("poster").isBlank()) return null
            val genres = o.optJSONArray("genres")?.let { g ->
                (0 until g.length()).map { g.optString(it) }
            }.orEmpty()
            return MovieTitle(
                id = o.optInt("id"),
                kind = o.optString("kind", "movie"),
                title = o.optString("title"),
                year = o.optString("year"),
                rating = o.optDouble("rating", 0.0),
                overview = o.optString("overview"),
                poster = o.optString("poster"),
                backdrop = o.optString("backdrop"),
                genres = genres,
            )
        }
    }
}

/** The catalogue fetch that may legitimately come back empty. */
private object Https {
    fun get(url: String): String? = try {
        Http.getText(url)
    } catch (error: Exception) {
        null
    }
}
