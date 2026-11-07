package ph.zenktx.tv.ui

import android.content.Context
import android.view.View
import android.widget.ImageView
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.load
import coil.memory.MemoryCache
import okhttp3.OkHttpClient
import ph.zenktx.tv.net.Http
import java.util.concurrent.TimeUnit

/**
 * Channel logos come from third-party EPG feeds. There is no lettered fallback:
 * drawing a monogram meant every tile painted initials first and then swapped in
 * the real logo when it arrived, which is what made the guide feel slow. A tile
 * without a working logo simply stays empty, like an EPG cell with no artwork.
 *
 * The artwork used to come and go between launches. Two things caused it: the
 * images were only cached for as long as the host's headers said (GitHub Pages
 * says ten minutes, so every launch fetched 400 files again), and a request that
 * timed out during that burst was dropped for good until the tile was rebound.
 * The cache now keeps a logo for good and a failure is retried twice.
 */
object Logos {

    @Volatile
    private var loader: ImageLoader? = null

    /** 96 MB holds the whole catalogue's artwork, twice over. */
    private const val CACHE_BYTES = 96L * 1024 * 1024

    /** One unchanged logo should never cost three requests. */
    private const val MAX_ATTEMPTS = 3

    private fun loader(context: Context): ImageLoader =
        loader ?: synchronized(this) {
            loader ?: ImageLoader.Builder(context.applicationContext)
                .components { add(SvgDecoder.Factory()) }
                .okHttpClient {
                    OkHttpClient.Builder()
                        .connectTimeout(12, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .callTimeout(30, TimeUnit.SECONDS)
                        .retryOnConnectionFailure(true)
                        .addInterceptor { chain ->
                            chain.proceed(
                                chain.request().newBuilder()
                                    .header("User-Agent", Http.USER_AGENT)
                                    .header("Referer", "https://iyad.click/")
                                    .header("Accept", "image/*,*/*")
                                    .build()
                            )
                        }
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(context.applicationContext.cacheDir.resolve("logos"))
                        .maxSizeBytes(CACHE_BYTES)
                        .build()
                }
                .memoryCache {
                    MemoryCache.Builder(context.applicationContext)
                        .maxSizePercent(0.25)
                        .build()
                }
                // The host's ten-minute Cache-Control is what emptied the guide
                // between launches; the files themselves never change.
                .respectCacheHeaders(false)
                .crossfade(true)
                .build()
                .also { loader = it }
        }

    /** Shows [url] when it loads, and an empty cell when it does not. */
    fun apply(logo: ImageView, url: String) {
        val address = url.trim()
        if (!address.startsWith("http")) {
            logo.tag = null
            logo.setImageDrawable(null)
            logo.visibility = View.GONE
            return
        }

        logo.tag = address
        logo.visibility = View.VISIBLE
        load(logo, address, 1)
    }

    private fun load(logo: ImageView, address: String, attempt: Int) {
        logo.load(address, loader(logo.context)) {
            listener(
                onSuccess = { _, _ ->
                    if (logo.tag == address) logo.visibility = View.VISIBLE
                },
                onError = { _, _ ->
                    // A tile scrolled away owns a different address by now, and
                    // retrying for it would land the wrong artwork on it.
                    if (logo.tag != address) return@listener
                    if (attempt < MAX_ATTEMPTS) {
                        logo.postDelayed(
                            { if (logo.tag == address) load(logo, address, attempt + 1) },
                            350L * attempt,
                        )
                    } else {
                        logo.setImageDrawable(null)
                        logo.visibility = View.GONE
                    }
                },
            )
        }
    }
}
