package ph.zenktx.tv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import coil.Coil
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.load

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

    private var loaderReady = false

    /**
     * 45 of the catalogue's logos are SVGs, and the stock Coil build decodes
     * none of them -- those channels fell back to their initials even though the
     * admin page has a logo for them. The decoder is registered explicitly so a
     * minified release cannot drop it.
     */
    private fun install(context: Context) {
        if (loaderReady) return
        loaderReady = true
        runCatching {
            Coil.setImageLoader(
                ImageLoader.Builder(context.applicationContext)
                    .components { add(SvgDecoder.Factory()) }
                    .crossfade(true)
                    .build()
            )
        }
    }

    /**
     * Shows [url] when it loads and the caller's initials tile otherwise. The
     * monogram sits underneath, so a slow or dead URL never leaves a blank box.
     */
    fun apply(logo: ImageView, monogram: TextView, name: String, url: String) {
        install(logo.context)
        monogram.text = initials(name)
        monogram.background = chip(monogram, tint(name))
        monogram.visibility = View.VISIBLE

        val address = url.trim()
        if (address.isEmpty()) {
            logo.setImageDrawable(null)
            logo.visibility = View.GONE
            return
        }

        logo.visibility = View.GONE
        logo.load(address) {
            crossfade(true)
            listener(
                onSuccess = { _, _ ->
                    logo.visibility = View.VISIBLE
                    monogram.visibility = View.GONE
                },
                onError = { _, _ ->
                    logo.setImageDrawable(null)
                    logo.visibility = View.GONE
                    monogram.visibility = View.VISIBLE
                },
            )
        }
    }
}
