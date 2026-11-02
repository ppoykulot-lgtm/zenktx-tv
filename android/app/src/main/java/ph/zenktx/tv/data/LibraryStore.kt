package ph.zenktx.tv.data

import android.content.Context

/**
 * Per-device state that is not part of the channel list itself:
 * favourites, hidden channels and per-channel logo edits.
 */
class LibraryStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("zenktx_library", Context.MODE_PRIVATE)

    // ---- favourites -------------------------------------------------------

    fun favourites(): Set<String> = prefs.getStringSet(KEY_FAV, null)?.toSet().orEmpty()

    fun isFavourite(key: String): Boolean = favourites().contains(key)

    fun setFavourite(key: String, on: Boolean) {
        val next = favourites().toMutableSet()
        if (on) next += key else next -= key
        prefs.edit().putStringSet(KEY_FAV, next).apply()
    }

    fun toggleFavourite(key: String): Boolean {
        val next = !isFavourite(key)
        setFavourite(key, next)
        return next
    }

    // ---- hidden channels --------------------------------------------------

    fun hidden(): Set<String> = prefs.getStringSet(KEY_HIDDEN, null)?.toSet().orEmpty()

    fun isHidden(key: String): Boolean = hidden().contains(key)

    fun setHidden(key: String, on: Boolean) {
        val next = hidden().toMutableSet()
        if (on) next += key else next -= key
        prefs.edit().putStringSet(KEY_HIDDEN, next).apply()
    }

    // ---- logo overrides ---------------------------------------------------

    /** null = untouched, "" = logo removed, otherwise a replacement URL. */
    fun logoOverride(key: String): String? = prefs.getString(KEY_LOGO_PREFIX + key, null)

    fun setLogoOverride(key: String, url: String?) {
        val edit = prefs.edit()
        if (url == null) edit.remove(KEY_LOGO_PREFIX + key)
        else edit.putString(KEY_LOGO_PREFIX + key, url)
        edit.apply()
    }

    fun logoOverrides(): Map<String, String> =
        prefs.all
            .filterKeys { it.startsWith(KEY_LOGO_PREFIX) }
            .mapKeys { it.key.removePrefix(KEY_LOGO_PREFIX) }
            .mapValues { it.value as? String ?: "" }

    fun clearLogoOverrides() {
        val edit = prefs.edit()
        logoOverrides().keys.forEach { edit.remove(KEY_LOGO_PREFIX + it) }
        edit.apply()
    }

    private companion object {
        const val KEY_FAV = "favourites"
        const val KEY_HIDDEN = "hidden"
        const val KEY_LOGO_PREFIX = "logo:"
    }
}
