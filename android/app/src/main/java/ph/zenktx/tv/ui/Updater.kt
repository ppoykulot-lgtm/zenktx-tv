package ph.zenktx.tv.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ph.zenktx.tv.BuildConfig
import ph.zenktx.tv.R
import ph.zenktx.tv.data.AppSettings
import ph.zenktx.tv.data.UpdateChecker

/**
 * Shared GitHub-release update flow: silent check on launch, or an explicit
 * check from Settings. The user always has to confirm the download.
 */
object Updater {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var working = false

    fun check(activity: Activity, silent: Boolean) {
        if (working) return
        val settings = AppSettings(activity)
        val repo = settings.updateRepo()
        if (repo.isBlank()) {
            if (!silent) Dialogs.info(activity, activity.getString(R.string.update_title), activity.getString(R.string.github_not_configured))
            return
        }

        working = true
        scope.launch {
            val release = withContext(Dispatchers.IO) {
                runCatching { UpdateChecker.latest(repo) }.getOrNull()
            }
            working = false
            if (activity.isFinishing || activity.isDestroyed) return@launch

            when {
                release == null -> if (!silent) {
                    Dialogs.info(
                        activity,
                        activity.getString(R.string.update_title),
                        activity.getString(R.string.update_failed, "no published release found"),
                    )
                }

                UpdateChecker.isNewer(BuildConfig.VERSION_NAME, release.version) ->
                    promptDownload(activity, release)

                !silent -> Dialogs.info(
                    activity,
                    activity.getString(R.string.update_title),
                    activity.getString(R.string.update_latest, BuildConfig.VERSION_NAME),
                )
            }
        }
    }

    /**
     * Release notes are Markdown written by the workflow: a platform table plus
     * a closing note. Keep only the readable sentences so the popup shows what
     * actually changed instead of pipes and backticks.
     */
    private fun highlightNotes(body: String): String {
        val lines = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("|") && !it.startsWith("#") && !it.startsWith("---") }
            .filterNot {
                it.contains("Only the platforms edited", true) ||
                    it.contains("signed only when", true) ||
                    it.contains("certificate secrets", true)
            }
            .map { it.removePrefix("- ").removePrefix("* ").replace("`", "").trim() }
            .filter { it.isNotEmpty() }
            .take(6)
            .toList()
        return if (lines.isEmpty()) "Bug fixes and new channels."
        else lines.joinToString("\n\u2022 ", prefix = "\u2022 ")
    }

    private fun promptDownload(activity: Activity, release: UpdateChecker.Release) {
        val notes = highlightNotes(release.notes)
        AlertDialog.Builder(activity, R.style.Theme_Zenktx_Dialog)
            .setTitle(activity.getString(R.string.update_title))
            .setMessage(
                activity.getString(
                    R.string.update_body,
                    release.version,
                    BuildConfig.VERSION_NAME,
                    notes,
                )
            )
            .setPositiveButton(R.string.update_download) { _, _ -> download(activity, release) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun download(activity: Activity, release: UpdateChecker.Release) {
        val context = activity
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            setPadding(0, 24, 0, 0)
            progressTintList = ContextCompat.getColorStateList(context, R.color.mint)
        }
        val label = TextView(context).apply {
            text = context.getString(R.string.update_downloading, 0)
            setTextColor(ContextCompat.getColor(context, R.color.muted))
            textSize = 12f
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(label)
            addView(progress)
        }

        val dialog = AlertDialog.Builder(context, R.style.Theme_Zenktx_Dialog)
            .setTitle(R.string.update_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    UpdateChecker.download(context, release) { percent ->
                        activity.runOnUiThread {
                            if (!activity.isFinishing) {
                                progress.progress = percent
                                label.text = context.getString(R.string.update_downloading, percent)
                            }
                        }
                    }
                }
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            dialog.dismiss()

            result.onSuccess { apk ->
                AlertDialog.Builder(context, R.style.Theme_Zenktx_Dialog)
                    .setTitle(R.string.update_ready)
                    .setPositiveButton(R.string.update_install) { _, _ ->
                        runCatching { UpdateChecker.install(context, apk) }
                            .onFailure {
                                Dialogs.info(context, context.getString(R.string.update_title), it.message ?: "Install failed")
                            }
                    }
                    .setNegativeButton(R.string.update_later, null)
                    .show()
            }.onFailure {
                Dialogs.info(
                    context,
                    context.getString(R.string.update_title),
                    context.getString(R.string.update_failed, it.message ?: "unknown error"),
                )
            }
        }
    }
}
