package ph.zenktx.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import ph.zenktx.tv.data.Channel
import ph.zenktx.tv.databinding.ItemGridChannelBinding

/**
 * EPG-style channel grid. Focus drives the detail strip below the grid.
 *
 * One click is all it takes on either build: a tap opens the player on a phone,
 * a D-pad click on the TV. Tapping twice to watch was a step nobody asked for.
 *
 * A long press toggles the favourite. The phone guide dropped its detail strip,
 * so that star has to live on the tile itself.
 */
class ChannelGridAdapter(
    private val onSelect: (Channel) -> Unit,
    private val onOpen: (Channel) -> Unit,
    private val onFocused: (Channel) -> Unit,
    private val onLongPress: ((Channel) -> Unit)? = null,
    private val isFavourite: (String) -> Boolean,
) : RecyclerView.Adapter<ChannelGridAdapter.TileVH>() {

    private var channels: List<Channel> = emptyList()

    fun submit(next: List<Channel>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = channels.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                channels[oldPos].key == next[newPos].key

            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                channels[oldPos] == next[newPos] &&
                    isFavourite(channels[oldPos].key) == isFavourite(next[newPos].key)
        })
        channels = next
        diff.dispatchUpdatesTo(this)
    }

    fun refreshBadges() = notifyItemRangeChanged(0, itemCount)

    fun channelAt(position: Int): Channel? = channels.getOrNull(position)

    inner class TileVH(val binding: ItemGridChannelBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(channel: Channel) {
            binding.tileName.text = channel.name
            Logos.apply(binding.tileLogo, channel.logo, channel.name)
            binding.tileStar.visibility =
                if (isFavourite(channel.key)) View.VISIBLE else View.GONE

            binding.root.setOnClickListener {
                onSelect(channel)
                onOpen(channel)
            }
            binding.root.setOnLongClickListener {
                val handler = onLongPress ?: return@setOnLongClickListener false
                handler(channel)
                true
            }
            Ui.attachFocusEffect(binding.root) { onFocused(channel) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileVH {
        val inflater = LayoutInflater.from(parent.context)
        return TileVH(ItemGridChannelBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: TileVH, position: Int) = holder.bind(channels[position])

    override fun getItemCount(): Int = channels.size
}
