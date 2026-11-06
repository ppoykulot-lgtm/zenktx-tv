package ph.zenktx.tv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.load
import okhttp3.OkHttpClient
import ph.zenktx.tv.net.Http
import java.util.concurrent.TimeUnit

/**
 * Channel logos come from third-party EPG feeds: some rows carry no logo at
 * all, and a few URLs are dead. An empty play glyph in those cells reads as a
 * broken image, so a missing logo falls back to the channel's initials on a
 * colour derived from its name -- the cell still identifies the channel.
 */
object Logos {

    /** "Kapamilya Channel" -> "KC", "A2Z" -> "A2", "TV5" -> "TV". */
    fun initials(name: String): String {
        val words = name
            .split(' ', '-', '_', '.', '|', '/', '(', ')', ':')
            .map { word -> word.filter { it.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
            .take(2)

        return when (words.size) {
            0 -> "?"
            1 -> words[0].take(2)
            else -> words.joinToString("") { it.take(1) }
        }.uppercase()
    }

    /** Stable per-name colour, dark enough for the white initials on top. */
    fun tint(name: String): Int {
        val hue = ((name.hashCode().toLong() and 0x7fffffff) % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.42f))
    }

    private fun chip(view: TextView, color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * view.resources.displayMetrics.density
            setColor(color)
        }

    @Volatile
    private var loader: ImageLoader? = null

    /**
     * One loader for the whole app, handed to every request explicitly. Going
     * through the global singleton left the tiles stuck on their initials: the
     * requests were enqueued on a loader that had already been replaced, so
     * neither the success nor the error callback ever ran.
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

    /**
     * Shows [url] when it loads and the caller's initials tile otherwise. The
     * monogram sits underneath, so a slow or dead URL never leaves a blank box.
     */
    fun apply(logo: ImageView, monogram: TextView, name: String, url: String) {
        monogram.text = initials(name)
        monogram.background = chip(monogram, tint(name))
        monogram.visibility = View.VISIBLE

        val address = url.trim()
        if (address.isEmpty() || !address.startsWith("http")) {
            logo.setImageDrawable(null)
            logo.visibility = View.GONE
            return
        }

        logo.visibility = View.VISIBLE
        logo.load(address, loader(logo.context)) {
            listener(
                onSuccess = { _, _ -> monogram.visibility = View.GONE },
                onError = { _, _ ->
                    // Keep the image view in place but empty: the initials that
                    // sit underneath are what the viewer sees.
                    logo.setImageDrawable(null)
                    logo.visibility = View.GONE
                    monogram.visibility = View.VISIBLE
                },
            )
        }
    }
}
