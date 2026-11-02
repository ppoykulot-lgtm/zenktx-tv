package ph.zenktx.tv.data

import android.content.Context

/**
 * Tiny favourites store backed by SharedPreferences.
 */
class FavouritesStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("zenktx_favourites", Context.MODE_PRIVATE)

    fun isFavourite(channelId: Int): Boolean = prefs.getBoolean(key(channelId), false)

    fun toggle(channelId: Int): Boolean {
        val next = !isFavourite(channelId)
        prefs.edit().putBoolean(key(channelId), next).apply()
        return next
    }

    private fun key(id: Int) = "ch_$id"
}
