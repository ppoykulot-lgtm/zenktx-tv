package ph.zenktx.tv.ui

import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Small helpers that give every focusable tile the same TV feel:
 * a lift, a slight scale and a smooth return when focus leaves.
 *
 * Chips in the filter row pass lift = false: a vertical lift combined
 * with a scale clips the rounded corners against the row bounds.
 */
object Ui {

    private const val SCALE_FOCUSED = 1.06f
    private const val LIFT_FOCUSED = -6f
    private const val DURATION = 170L

    fun attachFocusEffect(
        view: View,
        lift: Boolean = true,
        scale: Float = SCALE_FOCUSED,
        onFocused: (() -> Unit)? = null,
    ) {
        view.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) scale else 1f)
                .scaleY(if (hasFocus) scale else 1f)
                .translationY(if (hasFocus && lift) LIFT_FOCUSED else 0f)
                .setDuration(DURATION)
                .setInterpolator(DecelerateInterpolator())
                .start()
            v.z = if (hasFocus) 10f else 0f
            if (hasFocus) onFocused?.invoke()
        }
    }
}
