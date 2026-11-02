package ph.zenktx.tv.data

import android.content.Context
import ph.zenktx.tv.BuildConfig

/**
 * App-level preferences that are not tied to a single channel.
 */
class AppSettings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("zenktx_settings", Context.MODE_PRIVATE)

    /** GitHub repo ("owner/name") whose releases feed the updater. */
    fun updateRepo(): String =
        prefs.getString(KEY_REPO, BuildConfig.GITHUB_REPO).orEmpty()

    fun setUpdateRepo(repo: String) =
        prefs.edit().putString(KEY_REPO, repo.trim()).apply()

    /** Silent check on launch; the user still confirms installation. */
    fun autoCheckUpdates(): Boolean = prefs.getBoolean(KEY_AUTO, true)

    fun setAutoCheckUpdates(on: Boolean) =
        prefs.edit().putBoolean(KEY_AUTO, on).apply()

    fun lastCheckedVersion(): String = prefs.getString(KEY_LAST, "").orEmpty()

    fun setLastCheckedVersion(version: String) =
        prefs.edit().putString(KEY_LAST, version).apply()

    private companion object {
        const val KEY_REPO = "update_repo"
        const val KEY_AUTO = "auto_check_updates"
        const val KEY_LAST = "last_checked_version"
    }
}
