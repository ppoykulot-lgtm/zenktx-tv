package ph.zenktx.tv.ui

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ph.zenktx.tv.R
import ph.zenktx.tv.databinding.ItemCategoryChipBinding

/**
 * Horizontal category filter row. The selected chip stays light, the
 * focused chip gets the mint ring -- same language as the prototypes.
 *
 * Every chip carries its own glyph and the number of channels behind it, so the
 * row answers "is there anything in Kids?" before the D-pad walks into an empty
 * grid. The count is drawn as a span rather than a second view: the chip stays
 * one TextView, and its background, focus ring and click target stay the pill
 * they were.
 */
class CategoryChipAdapter(
    private val onSelected: (String) -> Unit,
    private val onFocused: (Int) -> Unit,
) : RecyclerView.Adapter<CategoryChipAdapter.ChipVH>() {

    private var chips: List<String> = emptyList()
    private var counts: Map<String, Int> = emptyMap()
    private var selected: String = "All"

    fun submit(next: List<String>, selectedChip: String, nextCounts: Map<String, Int>) {
        chips = next
        counts = nextCounts
        selected = selectedChip
        notifyDataSetChanged()
    }

    /** Recount without touching the selection: favourites change in place. */
    fun recount(nextCounts: Map<String, Int>) {
        counts = nextCounts
        notifyItemRangeChanged(0, chips.size)
    }

    inner class ChipVH(val binding: ItemCategoryChipBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(chip: String) {
            val context = binding.root.context
            val active = chip == selected
            val main = ContextCompat.getColor(
                context,
                if (active) R.color.btn_primary_text else R.color.chip_text,
            )
            val accent = ContextCompat.getColor(
                context,
                if (active) R.color.btn_primary_text else R.color.muted,
            )

            // The star is drawn as the chip's icon now, so the one the
            // category name still carries would print twice.
            val shown = chip.trimStart('★', ' ')
            val label = SpannableStringBuilder(shown.ifBlank { chip })
            counts[chip]?.let { total ->
                label.append("  ")
                val start = label.length
                label.append(total.toString())
                label.setSpan(RelativeSizeSpan(0.82f), start, label.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                label.setSpan(
                    ForegroundColorSpan(accent),
                    start,
                    label.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            binding.chipText.text = label
            binding.chipText.setTextColor(main)
            binding.chipText.setBackgroundResource(
                if (active) R.drawable.bg_chip_active else R.drawable.bg_chip
            )
            binding.chipText.setCompoundDrawablesRelativeWithIntrinsicBounds(iconFor(chip), 0, 0, 0)
            binding.chipText.compoundDrawableTintList =
                android.content.res.ColorStateList.valueOf(main)
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

    /**
     * The row's own names, not ids: the category list is built from the
     * catalogue, so a new shelf ("Anime", "NBA Live") arrives at runtime and
     * still needs a glyph without an APK.
     */
    private fun iconFor(chip: String): Int = when (chip.lowercase()) {
        "all" -> R.drawable.ic_cat_all
        "\u2605 favourites", "favourites" -> R.drawable.ic_cat_fav
        "pinoy" -> R.drawable.ic_cat_pinoy
        "news" -> R.drawable.ic_cat_news
        "sports", "nba live" -> R.drawable.ic_cat_sports
        "movies" -> R.drawable.ic_cat_movies
        "kids" -> R.drawable.ic_cat_kids
        "documentary" -> R.drawable.ic_cat_docs
        "music" -> R.drawable.ic_cat_music
        "international" -> R.drawable.ic_cat_intl
        "anime", "entertainment" -> R.drawable.ic_cat_ent
        else -> R.drawable.ic_cat_generic
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipVH {
        val inflater = LayoutInflater.from(parent.context)
        return ChipVH(ItemCategoryChipBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: ChipVH, position: Int) = holder.bind(chips[position])

    override fun getItemCount(): Int = chips.size
}
