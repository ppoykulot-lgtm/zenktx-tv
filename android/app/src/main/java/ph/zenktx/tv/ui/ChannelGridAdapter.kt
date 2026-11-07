package ph.zenktx.tv.ui

import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import ph.zenktx.tv.data.Channel
import ph.zenktx.tv.databinding.ItemGridChannelBinding

/**
 * EPG-style channel grid. Focus drives the detail strip below the grid.
 *
 * On a phone a tap picks the channel and a double tap starts it, which is what
 * the Windows build does with a double click; on the TV a single click with the
 * D-pad still opens the player, because a remote has no double tap.
 */
class ChannelGridAdapter(
    private val onSelect: (Channel) -> Unit,
    private val onOpen: (Channel) -> Unit,
    private val onFocused: (Channel) -> Unit,
    private val isFavourite: (String) -> Boolean,
    private val doubleTapToPlay: Boolean,
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
            Logos.apply(binding.tileLogo, channel.logo)
            binding.tileStar.visibility =
                if (isFavourite(channel.key)) View.VISIBLE else View.GONE

            if (doubleTapToPlay) {
                // A tap selects (the detail sheet fills in), a double tap plays.
                val detector = GestureDetector(
                    binding.root.context,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            onSelect(channel)
                            return true
                        }

                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            onOpen(channel)
                            return true
                        }
                    },
                )
                binding.root.setOnClickListener(null)
                binding.root.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
            } else {
                binding.root.setOnClickListener { onOpen(channel) }
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
