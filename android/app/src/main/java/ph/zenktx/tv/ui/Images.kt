package ph.zenktx.tv.ui

import android.content.Context
import android.widget.ImageView
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.load
import coil.memory.MemoryCache
import coil.request.ImageRequest
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import ph.zenktx.tv.net.Http
import java.util.concurrent.TimeUnit

/**
 * One picture pipeline for the whole app: channel logos and movie posters share
 * it, so artwork is downloaded once, decoded once and kept on disk between
 * launches.
 *
 * Three things were making every wall paint slowly:
 *
 *  - posters were fetched with a plain four-thread loader and a memory cache
 *    only, so a cold start re-downloaded dozens of files over a narrow link;
 *  - they were asked for at w500, roughly three times the pixels a card shows,
 *    which costs both bandwidth and decode time;
 *  - logo requests were capped at OkHttp's default of five per host, so a grid
 *    of 462 badges walked through the catalogue five at a time.
 *
 * Now every request goes through one OkHttp client that allows sixteen
 * requests per host, and TMDB artwork is re-pointed at the width the card
 * actually paints. Nothing else in the catalogue changes: the same file hash
 * exists at every TMDB width.
 */
object Images {

    /** The cache keeps the whole catalogue's artwork, posters included. */
    private const val CACHE_BYTES = 256L * 1024 * 1024

    /** Enough parallel requests to fill a screen in one round trip, not 462. */
    private const val PER_HOST = 16

    /** A card wider than this in pixels gets the larger TMDB file. */
    private const val WIDE_CARD_PX = 400

    @Volatile
    private var shared: ImageLoader? = null

    fun loader(context: Context): ImageLoader =
        shared ?: synchronized(this) {
            shared ?: build(context.applicationContext).also { shared = it }
        }

    private fun build(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .okHttpClient {
                OkHttpClient.Builder()
                    /* A dead logo host used to hold a socket for twelve seconds
                       before the next one started; six is enough for the feeds
                       that answer at all on a television's connection. */
                    .connectTimeout(6, TimeUnit.SECONDS)
                    .readTimeout(12, TimeUnit.SECONDS)
                    .callTimeout(20, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .dispatcher(
                        Dispatcher().apply {
                            maxRequests = PER_HOST * 4
                            maxRequestsPerHost = PER_HOST
                        }
                    )
                    .connectionPool(ConnectionPool(PER_HOST, 5, TimeUnit.MINUTES))
                    .addInterceptor { chain ->
                        val request = chain.request()
                        val builder = request.newBuilder()
                            .header("User-Agent", Http.USER_AGENT)
                            .header("Accept", "image/*,*/*")
                        // Third-party EPG logo hosts check the referer; TMDB does not.
                        if (!request.url.host.contains("tmdb.org")) {
                            builder.header("Referer", "https://iyad.click/")
                        }
                        chain.proceed(builder.build())
                    }
                    .build()
            }
            .diskCache {
                // The old logos directory is kept as the cache home so a launch
                // after this update still opens with yesterday's badges on disk.
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("logos"))
                    .maxSizeBytes(CACHE_BYTES)
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.25)
                    .build()
            }
            // The host's ten-minute Cache-Control is what emptied the guide
            // between launches; the files themselves never change.
            .respectCacheHeaders(false)
            // No crossfade: every badge refresh rebinds the tiles, and a fade
            // on each rebind is what read as a logo "still loading".
            .crossfade(false)
            .build()

    /**
     * TMDB serves one file at /t/p/w92, w185, w342, w500, w780 and original.
     * A 150 dp card is 342 px at most on any of these devices, so asking for
     * w500 only spends bandwidth. Anything that is not TMDB is left alone.
     */
    fun poster(url: String, widthPx: Int = 342): String {
        val address = url.trim()
        if (address.isEmpty() || !address.contains("image.tmdb.org/t/p/")) return address
        return address.replace(TMDB_WIDTH, "/t/p/w${if (widthPx > WIDE_CARD_PX) 500 else 342}/")
    }

    private val TMDB_WIDTH = Regex("/t/p/(w\\d+|original)/")

    /** Paints [url] into a poster cell, and skips the work when it is painted already. */
    fun applyPoster(view: ImageView, url: String, widthPx: Int) {
        val address = poster(url, widthPx)
        if (address.isEmpty()) return
        if (view.tag == address && view.drawable != null) return
        view.tag = address
        view.load(address, loader(view.context)) {
            size(widthPx.coerceAtLeast(1), (widthPx * 1.5f).toInt().coerceAtLeast(1))
        }
    }

    /**
     * Warms the next screenful while the reader is still looking at this one,
     * so scrolling to the following twenty-eight posters is a cache hit instead
     * of twenty-eight fresh requests.
     */
    fun prefetch(context: Context, urls: List<String>, widthPx: Int) {
        if (urls.isEmpty()) return
        val app = context.applicationContext
        val request = loader(app)
        val width = widthPx.coerceAtLeast(1)
        urls.forEach { url ->
            val address = poster(url, width)
            if (address.isEmpty()) return@forEach
            request.enqueue(
                ImageRequest.Builder(app)
                    .data(address)
                    .size(width, (width * 1.5f).toInt().coerceAtLeast(1))
                    .build()
            )
        }
    }
}
