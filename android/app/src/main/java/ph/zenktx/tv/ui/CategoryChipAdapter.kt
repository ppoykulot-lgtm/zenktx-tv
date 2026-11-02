package ph.zenktx.tv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ph.zenktx.tv.R
import ph.zenktx.tv.databinding.ItemCategoryChipBinding

/**
 * Horizontal category filter row. The selected chip stays light, the
 * focused chip gets the mint ring -- same language as the prototypes.
 */
class CategoryChipAdapter(
    private val onSelected: (String) -> Unit,
    private val onFocused: (Int) -> Unit,
) : RecyclerView.Adapter<CategoryChipAdapter.ChipVH>() {

    private var chips: List<String> = emptyList()
    private var selected: String = "All"

    fun submit(next: List<String>, selectedChip: String) {
        chips = next
        selected = selectedChip
        notifyDataSetChanged()
    }

    inner class ChipVH(val binding: ItemCategoryChipBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(chip: String) {
            val context = binding.root.context
            val active = chip == selected

            binding.chipText.text = chip
            binding.chipText.setBackgroundResource(
                if (active) R.drawable.bg_chip_active else R.drawable.bg_chip
            )
            binding.chipText.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (active) R.color.btn_primary_text else R.color.chip_text
                )
            )
            binding.chipText.setOnClickListener { onSelected(chip) }
            // No lift here: a vertical lift + scale clips the chip's rounded
            // corners against the top of the filter row.
            Ui.attachFocusEffect(
                view = binding.chipText,
                lift = false,
                scale = 1.04f,
                onFocused = { onFocused(bindingAdapterPosition) },
            )
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipVH {
        val inflater = LayoutInflater.from(parent.context)
        return ChipVH(ItemCategoryChipBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: ChipVH, position: Int) = holder.bind(chips[position])

    override fun getItemCount(): Int = chips.size
}
