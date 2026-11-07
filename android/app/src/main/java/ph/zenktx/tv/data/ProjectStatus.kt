package ph.zenktx.tv.data

import android.content.Context
import org.json.JSONObject
import ph.zenktx.tv.net.Http

/**
 * The kill switch.
 *
 * The admin page publishes a tiny status.json next to the catalogue. When it
 * says the project has stopped, every build shows that message and refuses to
 * load channels: no guide, no player, no menu. The answer is cached, so turning
 * the network off does not bring the app back -- only a newer published file
 * can, which is why this cannot be bypassed from the device.
 */
object ProjectStatus {

    private const val URL = "https://ppoykulot-lgtm.github.io/zenktx-tv/status.json"
    private const val PREFS = "zenktx_status"
    private const val KEY_STOPPED = "stopped"
    private const val KEY_MESSAGE = "message"

    data class Status(val stopped: Boolean, val message: String)

    /** Blocking: call from a background thread. Falls back to the last answer. */
    fun current(context: Context): Status {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        runCatching {
            val text = Http.getText("$URL?t=${System.currentTimeMillis()}", timeoutMs = 8_000)
            val json = JSONObject(text)
            prefs.edit()
                .putBoolean(KEY_STOPPED, json.optBoolean("maintenance", false))
                .putString(KEY_MESSAGE, json.optString("message"))
                .apply()
        }

        return Status(
            stopped = prefs.getBoolean(KEY_STOPPED, false),
            message = prefs.getString(KEY_MESSAGE, "").orEmpty(),
        )
    }
}
