package ph.zenktx.tv.ui

import android.view.View
import android.widget.ImageView
import coil.load

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
 *
 * The loader itself is shared with the poster wall ([Images]): one disk cache,
 * one connection pool, and sixteen requests per host instead of OkHttp's five,
 * which is what turns "462 logos five at a time" into a single burst.
 */
object Logos {

    /** One unchanged logo should never cost three requests. */
    private const val MAX_ATTEMPTS = 3

    /**
     * Shows [url] when it loads. A channel with no URL at all gets its initials
     * immediately -- fourteen channels in the published catalogue have no logo
     * that any host will still serve, and an empty cell in a full grid looks
     * like a bug. The monogram is only drawn once the real artwork is known to
     * be absent, never while it is still on its way, so nothing repaints over
     * it.
     */
    fun apply(logo: ImageView, url: String, name: String = "") {
        val address = url.trim()
        if (!address.startsWith("http")) {
            logo.tag = null
            val fallback = MonogramDrawable.of(name)
            if (fallback == null) {
                logo.setImageDrawable(null)
                logo.visibility = View.GONE
            } else {
                logo.setImageDrawable(fallback)
                logo.visibility = View.VISIBLE
            }
            return
        }

        // A rebind (badge refresh, resume) must not throw away artwork that is
        // already painted: the grid asked for the same address as before.
        if (logo.tag == address && logo.drawable != null) {
            logo.visibility = View.VISIBLE
            return
        }

        logo.tag = address
        logo.visibility = View.VISIBLE
        load(logo, address, name, 1)
    }

    private fun load(logo: ImageView, address: String, name: String, attempt: Int) {
        logo.load(address, Images.loader(logo.context)) {
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
                            { if (logo.tag == address) load(logo, address, name, attempt + 1) },
                            350L * attempt,
                        )
                    } else {
                        logo.setImageDrawable(MonogramDrawable.of(name))
                        logo.visibility = if (logo.drawable == null) View.GONE else View.VISIBLE
                    }
                },
            )
        }
    }
}
