package ph.zenktx.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ph.zenktx.tv.R

/**
 * The thin strip on the right of the guide: a dim track, a mint thumb sized to
 * how much of the list is on screen, and a chevron at each end that lights up
 * only when there is something that way. It answers the one question a D-pad
 * cannot: whether pressing down three more times is worth the effort.
 *
 * Everything is drawn, so it costs one view and no drawables, and it hides
 * itself whenever the whole list already fits.
 */
class ScrollHint @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.line)
        style = Paint.Style.FILL
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.mint)
        alpha = 150
        style = Paint.Style.FILL
    }
    private val arrowOn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.mint)
        alpha = 210
        style = Paint.Style.FILL
    }
    private val arrowOff = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.muted)
        alpha = 70
        style = Paint.Style.FILL
    }

    private val trackRect = RectF()
    private val thumbRect = RectF()
    private val arrow = Path()

    private var list: RecyclerView? = null
    private var canUp = false
    private var canDown = false
    private var thumbStart = 0f
    private var thumbLength = 0f
    private var scrollable = false

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = sync()
    }

    /** Follows [view]'s scroll position. Re-attaching the same view is a no-op. */
    fun attach(view: RecyclerView) {
        list?.removeOnScrollListener(scrollListener)
        list = view
        view.addOnScrollListener(scrollListener)
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sync() }
        post { sync() }
    }

    /** Recomputed after a filter, a reload or a favourite: data alone never scrolls. */
    fun sync() {
        val view = list ?: return
        val range = view.computeVerticalScrollRange()
        val extent = view.computeVerticalScrollExtent()
        val offset = view.computeVerticalScrollOffset()

        scrollable = range > extent + 1 && extent > 0
        canUp = scrollable && offset > 4
        canDown = scrollable && offset + extent < range - 4

        val top = dp(11f)
        val bottom = height - dp(11f)
        val usable = (bottom - top).coerceAtLeast(1f)
        if (scrollable) {
            // A thumb shorter than 24dp stops reading as a position at all.
            thumbLength = (usable * extent / range).coerceAtLeast(dp(24f))
            val travel = (usable - thumbLength).coerceAtLeast(0f)
            val progress = offset.toFloat() / (range - extent).toFloat()
            thumbStart = top + travel * progress.coerceIn(0f, 1f)
        }

        // A list that fits has nothing to say, but the view still takes its
        // 10dp: hiding it would reflow the grid under a moving finger.
        alpha = if (scrollable) 1f else 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (alpha == 0f) return
        val cx = width / 2f
        val trackTop = dp(14f)
        val trackBottom = height - dp(14f)
        if (trackBottom - trackTop < dp(16f)) return

        val trackHalf = dp(1.2f)
        trackRect.set(cx - trackHalf, trackTop, cx + trackHalf, trackBottom)
        canvas.drawRoundRect(trackRect, trackHalf, trackHalf, track)

        if (scrollable && thumbLength > 0f) {
            val thumbHalf = dp(2.4f)
            thumbRect.set(cx - thumbHalf, thumbStart, cx + thumbHalf, thumbStart + thumbLength)
            canvas.drawRoundRect(thumbRect, thumbHalf, thumbHalf, thumbPaint)
        }

        drawChevron(canvas, cx, dp(7f), up = true)
        drawChevron(canvas, cx, height - dp(7f), up = false)
    }

    private fun drawChevron(canvas: Canvas, cx: Float, cy: Float, up: Boolean) {
        val half = dp(4f)
        val reach = dp(3.2f)
        arrow.reset()
        if (up) {
            arrow.moveTo(cx, cy - reach)
            arrow.lineTo(cx + half, cy + reach * 0.6f)
            arrow.lineTo(cx - half, cy + reach * 0.6f)
        } else {
            arrow.moveTo(cx, cy + reach)
            arrow.lineTo(cx + half, cy - reach * 0.6f)
            arrow.lineTo(cx - half, cy - reach * 0.6f)
        }
        arrow.close()
        canvas.drawPath(arrow, if (up) if (canUp) arrowOn else arrowOff else if (canDown) arrowOn else arrowOff)
    }
}
