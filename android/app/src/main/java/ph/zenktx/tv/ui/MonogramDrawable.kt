package ph.zenktx.tv.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * What a tile shows when a channel has no artwork at all: the channel's initials
 * on a wash of its own colour. An empty black cell in the middle of a full grid
 * reads as a broken app; two letters read as a channel whose logo has not been
 * found yet.
 *
 * It is drawn, not fetched, so it appears with the tile instead of a frame after
 * it. The colour is derived from the name, which means the same channel keeps
 * the same tint between launches and between the guide and the player.
 */
class MonogramDrawable(
    private val name: String,
    private val color: Int,
) : Drawable() {

    private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        alpha = 30
        style = Paint.Style.FILL
    }

    private val letters = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isSubpixelText = true
    }

    private val box = RectF()
    private val middle = Rect()

    private val initials: String = run {
        val words = name.split(' ', '.', '-', ':')
            .filter { it.isNotBlank() && it.first().isLetterOrDigit() }
        when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(1).uppercase()
            else -> (words[0].take(1) + words[1].take(1)).uppercase()
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        letters.textSize = bounds.height() * 0.40f
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        val radius = minOf(bounds.width(), bounds.height()) * 0.16f
        box.set(bounds)
        canvas.drawRoundRect(box, radius, radius, plate)

        letters.getTextBounds(initials, 0, initials.length, middle)
        canvas.drawText(
            initials,
            bounds.exactCenterX(),
            bounds.exactCenterY() - middle.exactCenterY(),
            letters,
        )
    }

    override fun setAlpha(alpha: Int) {
        plate.alpha = (alpha * 0.12f).toInt()
        letters.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        plate.colorFilter = colorFilter
        letters.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Kept for the platform contract")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    companion object {
        /** A small, cold palette so no two neighbouring tiles shout. */
        private val PALETTE = intArrayOf(
            0xFF35E7C0.toInt(),
            0xFF5B8CFF.toInt(),
            0xFF9B8CFF.toInt(),
            0xFF4FD1E8.toInt(),
            0xFFE8C46A.toInt(),
            0xFF7FD68B.toInt(),
            0xFFEA8FB0.toInt(),
        )

        /** The monogram for [name], or null when there is no name to draw. */
        fun of(name: String): MonogramDrawable? {
            val clean = name.trim()
            if (clean.isEmpty()) return null
            var hash = 7
            for (char in clean.lowercase()) hash = hash * 31 + char.code
            val color = PALETTE[Math.floorMod(hash, PALETTE.size)]
            return MonogramDrawable(clean, color)
        }
    }
}
