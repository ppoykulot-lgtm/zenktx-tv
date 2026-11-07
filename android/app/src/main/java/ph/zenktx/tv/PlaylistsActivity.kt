package ph.zenktx.tv

import android.app.Activity
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import ph.zenktx.tv.data.ChannelRepository
import ph.zenktx.tv.data.Playlist
import ph.zenktx.tv.data.PlaylistStore
import ph.zenktx.tv.databinding.ActivityPlaylistsBinding
import ph.zenktx.tv.ui.Dialogs
import ph.zenktx.tv.ui.PlaylistAdapter
import ph.zenktx.tv.ui.Ui

/**
 * Add, edit, disable or remove channel sources. The bundled list is always
 * active and cannot be removed, so the app never ends up with zero channels.
 */
class PlaylistsActivity : Activity() {

    private lateinit var binding: ActivityPlaylistsBinding
    private lateinit var store: PlaylistStore
    private lateinit var adapter: PlaylistAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = PlaylistStore(this)

        adapter = PlaylistAdapter(
            onClick = { playlist -> editPlaylist(playlist) },
            onLongClick = { playlist -> playlistActions(playlist) },
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        Ui.attachFocusEffect(binding.backButton, lift = false, scale = 1.04f)
        Ui.attachFocusEffect(binding.addButton, lift = false, scale = 1.02f)

        binding.backButton.setOnClickListener { finish() }
        binding.addButton.setOnClickListener { addPlaylist() }

        refresh()
        binding.backButton.requestFocus()
    }

    private fun addPlaylist() {
        Dialogs.form(
            this,
            getString(R.string.add_playlist),
            listOf(
                Dialogs.Field("name", getString(R.string.playlist_name)),
                Dialogs.Field("url", getString(R.string.playlist_url), uri = true),
                Dialogs.Field("logo", getString(R.string.playlist_logo), uri = true),
            ),
        ) { values ->
            val url = values["url"].orEmpty()
            if (url.isBlank()) return@form
            store.add(values["name"].orEmpty(), url, values["logo"].orEmpty())
            ChannelRepository.dirty = true
            refresh()
        }
    }

    /**
     * Every field is editable, so a source saved with a typo in its stream URL
     * or its logo can be fixed here instead of being removed and re-added.
     */
    private fun editPlaylist(playlist: Playlist) {
        Dialogs.form(
            this,
            getString(R.string.playlist_edit),
            listOf(
                Dialogs.Field("name", getString(R.string.playlist_name), playlist.name),
                Dialogs.Field("url", getString(R.string.playlist_url), playlist.url, uri = true),
                Dialogs.Field("logo", getString(R.string.playlist_logo), playlist.logo, uri = true),
            ),
        ) { values ->
            val url = values["url"].orEmpty()
            if (url.isBlank()) return@form
            store.update(playlist.id, values["name"].orEmpty(), url, values["logo"].orEmpty())
            ChannelRepository.dirty = true
            refresh()
        }
    }

    private fun playlistActions(playlist: Playlist) {
        val actions = listOf(
            getString(if (playlist.enabled) R.string.playlist_disable else R.string.playlist_enable),
            getString(R.string.playlist_edit),
            getString(R.string.playlist_remove),
        )
        Dialogs.choose(this, playlist.name, actions) { index ->
            when (index) {
                0 -> {
                    store.setEnabled(playlist.id, !playlist.enabled)
                    ChannelRepository.dirty = true
                    refresh()
                }

                1 -> editPlaylist(playlist)

                else -> Dialogs.confirm(
                    this,
                    getString(R.string.playlist_remove_title),
                    getString(R.string.playlist_remove_body, playlist.name),
                    positive = getString(R.string.playlist_remove),
                ) {
                    store.remove(playlist.id)
                    ChannelRepository.dirty = true
                    refresh()
                }
            }
        }
    }

    private fun refresh() {
        val items = store.list()
        adapter.submit(items)
        binding.playlistCount.text = "${items.size} SOURCE(S)"
        binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }
}
