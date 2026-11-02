package ph.zenktx.tv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ph.zenktx.tv.R
import ph.zenktx.tv.data.Playlist
import ph.zenktx.tv.databinding.ItemPlaylistBinding

class PlaylistAdapter(
    private val onClick: (Playlist) -> Unit,
    private val onLongClick: (Playlist) -> Unit,
) : RecyclerView.Adapter<PlaylistAdapter.VH>() {

    private var items: List<Playlist> = emptyList()

    fun submit(next: List<Playlist>) {
        items = next
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemPlaylistBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(playlist: Playlist) {
            val context = binding.root.context
            binding.playlistName.text = playlist.name
            binding.playlistUrl.text = playlist.url
            binding.playlistState.text = context.getString(
                if (playlist.enabled) R.string.playlist_enabled else R.string.playlist_disabled
            )
            binding.playlistState.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (playlist.enabled) R.color.mint else R.color.muted,
                )
            )
            binding.root.setOnClickListener { onClick(playlist) }
            binding.root.setOnLongClickListener {
                onLongClick(playlist)
                true
            }
            Ui.attachFocusEffect(binding.root, scale = 1.02f)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        return VH(ItemPlaylistBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size
}
