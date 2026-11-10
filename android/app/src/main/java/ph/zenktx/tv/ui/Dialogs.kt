package ph.zenktx.tv.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import ph.zenktx.tv.R
import ph.zenktx.tv.data.Donation

/**
 * Dialog helpers. Everything uses the dark dialog theme and keeps controls
 * focusable so a D-pad can drive it without a touchscreen.
 */
object Dialogs {

    data class Field(
        val key: String,
        val label: String,
        val initial: String = "",
        val hint: String = "",
        val secret: Boolean = false,
        val uri: Boolean = false,
    )

    fun choose(context: Context, title: String, items: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(context, R.style.Theme_Zenktx_Dialog)
            .setTitle(title)
            .setItems(items.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                onPick(which)
            }
            .show()
    }

    /**
     * The donation sheet: the note, then the GCash and Maya codes side by side.
     * The images live in `assets/donation/` so the same two files ship inside
     * both APKs without a drawable per density, and a build that somehow
     * arrives without them shows the empty QR frame instead of a broken card.
     */
    fun donate(context: Context) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_donate, null)
        view.findViewById<ImageView>(R.id.gcashQr)?.let { loadQr(context, it, "donation/gcash.png") }
        view.findViewById<ImageView>(R.id.mayaQr)?.let { loadQr(context, it, "donation/maya.png") }
        val gcashCard = view.findViewById<View>(R.id.gcashCard)
        val mayaCard = view.findViewById<View>(R.id.mayaCard)
        val gap = view.findViewById<View>(R.id.donateGap)
        val hint = view.findViewById<TextView>(R.id.donateHint)
        val paused = view.findViewById<View>(R.id.donatePaused)
        AlertDialog.Builder(context, R.style.Theme_Zenktx_Dialog)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()

        // admin.html publishes which methods are on. The sheet opens with both
        // codes so it never hangs on the network, then the published answer
        // takes one away if the owner switched it off.
        val app = context.applicationContext
        Thread {
            val flags = Donation.current(app)
            Handler(Looper.getMainLooper()).post {
                gcashCard?.visibility = if (flags.gcash) View.VISIBLE else View.GONE
                mayaCard?.visibility = if (flags.maya) View.VISIBLE else View.GONE
                gap?.visibility = if (flags.gcash && flags.maya) View.VISIBLE else View.GONE
                view.findViewById<View>(R.id.donateCodes)?.visibility =
                    if (flags.any) View.VISIBLE else View.GONE
                hint?.visibility = if (flags.any) View.VISIBLE else View.GONE
                paused?.visibility = if (flags.any) View.GONE else View.VISIBLE
                // Name the method that is actually on, so a viewer is not sent
                // looking for a code that the owner switched off.
                if (hint != null && flags.any) {
                    hint.text = if (flags.gcash && flags.maya) {
                        app.getString(R.string.donate_hint)
                    } else {
                        app.getString(
                            R.string.donate_hint_one,
                            app.getString(if (flags.gcash) R.string.donate_gcash else R.string.donate_maya),
                        )
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun loadQr(context: Context, target: ImageView, asset: String) {
        runCatching { context.assets.open(asset).use { BitmapFactory.decodeStream(it) } }
            .getOrNull()
            ?.let { target.setImageBitmap(it) }
    }

    fun info(context: Context, title: String, message: String) {
        AlertDialog.Builder(context, R.style.Theme_Zenktx_Dialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun confirm(
        context: Context,
        title: String,
        message: String,
        positive: String = "OK",
        negative: String = "Cancel",
        onConfirm: () -> Unit,
    ) {
        AlertDialog.Builder(context, R.style.Theme_Zenktx_Dialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onConfirm() }
            .setNegativeButton(negative, null)
            .show()
    }

    fun prompt(
        context: Context,
        title: String,
        hint: String,
        initial: String = "",
        uri: Boolean = true,
        onValue: (String) -> Unit,
    ) {
        val input = editText(context, initial, hint, uri = uri)
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 8), dp(context, 24), 0)
            addView(input)
        }
        AlertDialog.Builder(context, R.style.Theme_Zenktx_Dialog)
            .setTitle(title)
            .setView(wrapper)
            .setPositiveButton(android.R.string.ok) { _, _ -> onValue(input.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun form(
        context: Context,
        title: String,
        fields: List<Field>,
        positive: String = "Save",
        onSubmit: (Map<String, String>) -> Unit,
    ) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 4), dp(context, 24), 0)
        }
        val inputs = LinkedHashMap<String, EditText>()

        fields.forEach { field ->
            val label = TextView(context).apply {
                text = field.label
                setTextColor(ContextCompat.getColor(context, R.color.muted))
                textSize = 11f
                letterSpacing = 0.06f
                setPadding(0, dp(context, 8), 0, dp(context, 4))
            }
            val input = editText(context, field.initial, field.hint, field.uri, field.secret)
            container.addView(label)
            container.addView(input)
            inputs[field.key] = input
        }

        AlertDialog.Builder(context, R.style.Theme_Zenktx_Dialog)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(positive) { _, _ ->
                onSubmit(inputs.mapValues { it.value.text.toString().trim() })
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editText(
        context: Context,
        initial: String,
        hint: String,
        uri: Boolean,
        secret: Boolean = false,
    ): EditText = EditText(context).apply {
        setText(initial)
        this.hint = hint
        setTextColor(ContextCompat.getColor(context, R.color.text))
        setHintTextColor(ContextCompat.getColor(context, R.color.muted))
        textSize = 13f
        setSingleLine(!secret)
        inputType = when {
            secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            uri -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            else -> InputType.TYPE_CLASS_TEXT
        }
        setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
