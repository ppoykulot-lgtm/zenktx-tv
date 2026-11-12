package ph.zenktx.tv.data

import org.json.JSONObject
import ph.zenktx.tv.net.Http

/**
 * The Movies & Series catalogue published with the site.
 *
 * One file, no key: the shells only template a TMDB id into a player address,
 * so a build never carries an API secret and a new title appears without one.
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

data class MovieCatalog(
    val servers: List<PlayerServer>,
    val movies: List<MovieTitle>,
    val series: List<MovieTitle>,
) {
    fun titles(kind: String): List<MovieTitle> = if (kind == "series") series else movies

    /** Fills the season and episode slots for a series, and ignores them for a film. */
    fun address(server: PlayerServer, title: MovieTitle, season: Int, episode: Int): String {
        val template = if (title.kind == "tv") server.tv else server.movie
        return template
            .replace("{id}", title.id.toString())
            .replace("{season}", season.toString())
            .replace("{episode}", episode.toString())
    }

    companion object {
        const val URL = "https://ppoykulot-lgtm.github.io/zenktx-tv/movies.json"

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

            fun titles(key: String): List<MovieTitle> {
                val array = root.optJSONArray(key) ?: return emptyList()
                return (0 until array.length()).mapNotNull { i ->
                    val o = array.optJSONObject(i) ?: return@mapNotNull null
                    if (o.optString("title").isBlank() || o.optString("poster").isBlank()) {
                        return@mapNotNull null
                    }
                    val genres = o.optJSONArray("genres")?.let { g ->
                        (0 until g.length()).map { g.optString(it) }
                    }.orEmpty()
                    MovieTitle(
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

            return MovieCatalog(servers = servers, movies = titles("movies"), series = titles("series"))
        }

        /** Blocking; call from a background thread. */
        fun load(): MovieCatalog = parse(Http.getText("$URL?t=${System.currentTimeMillis()}"))
    }
}
