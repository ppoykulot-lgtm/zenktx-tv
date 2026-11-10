package ph.zenktx.tv.data

import android.content.Context
import org.json.JSONObject
import ph.zenktx.tv.net.Http

/**
 * The donation switches.
 *
 * admin.html publishes a tiny donation.json beside the catalogue, and the
 * support section of the site hides a method that is switched off. The apps
 * ship both QR images, so they have to read the same file: otherwise a viewer
 * scans a code the owner has already turned off.
 *
 * Only an explicit `false` hides a method. A missing key, an empty file or a
 * dead network leaves both codes on screen, so a hiccup never hides the only
 * way to give.
 */
object Donation {

    private const val URL = "https://ppoykulot-lgtm.github.io/zenktx-tv/donation.json"
    private const val PREFS = "zenktx_status"
    private const val KEY_GCASH = "donate_gcash"
    private const val KEY_MAYA = "donate_maya"

    data class Flags(val gcash: Boolean, val maya: Boolean) {
        val any: Boolean get() = gcash || maya
    }

    /** Blocking: call from a background thread. Falls back to the last answer. */
    fun current(context: Context): Flags {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        runCatching {
            val text = Http.getText("$URL?t=${System.currentTimeMillis()}", timeoutMs = 8_000)
            val json = JSONObject(text)
            prefs.edit()
                .putBoolean(KEY_GCASH, json.optBoolean("gcash", true))
                .putBoolean(KEY_MAYA, json.optBoolean("maya", true))
                .apply()
        }

        return Flags(
            gcash = prefs.getBoolean(KEY_GCASH, true),
            maya = prefs.getBoolean(KEY_MAYA, true),
        )
    }
}
