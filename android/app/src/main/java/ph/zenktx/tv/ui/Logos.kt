package ph.zenktx.tv.ui

import android.content.Context
import android.view.View
import android.widget.ImageView
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.load
import okhttp3.OkHttpClient
import ph.zenktx.tv.net.Http
import java.util.concurrent.TimeUnit

/**
 * Channel logos come from third-party EPG feeds. There is no lettered fallback:
 * drawing a monogram meant every tile painted initials first and then swapped in
 * the real logo when it arrived, which is what made the guide feel slow. A tile
 * without a working logo simply stays empty, like an EPG cell with no artwork.
 */
object Logos {

    @Volatile
    private var loader: ImageLoader? = null

    /**
     * One loader for the whole app, handed to every request explicitly. Going
     * through the global singleton left the tiles stuck: the requests were
     * enqueued on a loader that had already been replaced, so neither the
     * success nor the error callback ever ran.
     *
     * The decoder handles the catalogue's SVG logos, and the client sends the
     * same caller the catalogue fetch uses -- a couple of these hosts sit
     * behind Cloudflare and answer a bare OkHttp request with nothing at all.
     */
    private fun loader(context: Context): ImageLoader =
        loader ?: synchronized(this) {
            loader ?: ImageLoader.Builder(context.applicationContext)
                .components { add(SvgDecoder.Factory()) }
                .okHttpClient {
                    OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .callTimeout(20, TimeUnit.SECONDS)
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
                .crossfade(true)
                .build()
                .also { loader = it }
        }

    /** Shows [url] when it loads, and an empty cell when it does not. */
    fun apply(logo: ImageView, url: String) {
        val address = url.trim()
        if (!address.startsWith("http")) {
            logo.setImageDrawable(null)
            logo.visibility = View.GONE
            return
        }

        logo.visibility = View.VISIBLE
        logo.load(address, loader(logo.context)) {
            listener(
                onSuccess = { _, _ -> logo.visibility = View.VISIBLE },
                onError = { _, _ ->
                    logo.setImageDrawable(null)
                    logo.visibility = View.GONE
                },
            )
        }
    }
}
