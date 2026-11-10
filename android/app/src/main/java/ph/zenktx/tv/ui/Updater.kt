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
import ph.zenktx.tv.data.Integrity
import ph.zenktx.tv.data.UpdateChecker

/**
 * Shared GitHub-release update flow. On launch the build is gated: an outdated
 * copy cannot reach the guide until it installs the release attached to the
 * newest tag. A check that fails (no network, GitHub down) never locks anyone
 * out, because a build that cannot see the feed would be bricked on a plane.
 * The Settings check stays a plain, cancellable prompt.
 */
object Updater {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var working = false

    /** The blocking dialog, kept so a second resume cannot stack another one. */
    private var gate: AlertDialog? = null

    /** Set while the installer is in front; the app re-gates when it returns. */
    private var awaitingInstall = false

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
     * Launch gate. Runs regardless of the automatic-check preference: a build
     * that the owner has replaced must not stay usable, and the feed is the
     * only place that knows which number is current.
     */
    fun enforce(activity: Activity) {
        if (working) return
        val repo = AppSettings(activity).updateRepo()
        if (repo.isBlank()) return

        working = true
        scope.launch {
            val release = withContext(Dispatchers.IO) {
                runCatching { UpdateChecker.latest(repo) }.getOrNull()
            }
            working = false
            if (activity.isFinishing || activity.isDestroyed) return@launch
            // No answer from the feed is not a verdict on the installed build.
            if (release == null) return@launch
            if (!UpdateChecker.isNewer(BuildConfig.VERSION_NAME, release.version)) {
                gate?.takeIf { it.isShowing }?.dismiss()
                gate = null
                return@launch
            }
            showRequired(activity, release, null)
        }
    }

    /** Re-gate once the installer closes, unless the copy on disk changed. */
    fun onResume(activity: Activity) {
        if (!awaitingInstall) return
        awaitingInstall = false
        enforce(activity)
    }

    fun close(activity: Activity) {
        gate = null
        activity.finishAffinity()
    }

    /**
     * The one dialog an outdated build can show. Home still works, but there is
     * no way back into the app: download it, or close the app.
     */
    private fun showRequired(activity: Activity, release: UpdateChecker.Release, problem: String?) {
        if (activity.isFinishing || activity.isDestroyed) return
        gate?.takeIf { it.isShowing }?.dismiss()

        // No release notes on the gate: it is one sentence, and the workflow's
        // body is a platform table whose stray lines only confuse.
        val body = buildString {
            if (problem != null) append(activity.getString(R.string.update_required_problem, problem)).append("\n\n")
            append(
                activity.getString(
                    R.string.update_required_body,
                    release.version,
                    BuildConfig.VERSION_NAME,
                )
            )
        }

        val dialog = AlertDialog.Builder(activity, R.style.Theme_Zenktx_Dialog)
            .setTitle(R.string.update_required_title)
            .setMessage(body)
            .setPositiveButton(R.string.update_download) { _, _ -> download(activity, release, forced = true) }
            .setNegativeButton(R.string.update_close) { _, _ -> close(activity) }
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        gate = dialog
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

    private fun download(activity: Activity, release: UpdateChecker.Release, forced: Boolean = false) {
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

        val builder = AlertDialog.Builder(context, R.style.Theme_Zenktx_Dialog)
            .setTitle(R.string.update_title)
            .setView(container)
        // A forced download cannot be abandoned into the guide, so its only
        // way out is the failure dialog, which offers the retry.
        if (!forced) builder.setNegativeButton(android.R.string.cancel, null)
        val dialog = builder.setCancelable(!forced).create()
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
                // Android refuses to replace an app signed by another key and
                // only says "App not installed". Check before handing the file
                // to the installer so the reason is on screen.
                val installed = Integrity.signingSha256(context)
                val incoming = UpdateChecker.apkSigningSha256(context, apk)
                if (installed != null && incoming != null && installed != incoming) {
                    val why = "This download is signed with a different key than the copy on " +
                        "this device, so Android will not replace it. Uninstall ZENKTX, then " +
                        "install this APK."
                    // A forced copy cannot fall back into the guide on this path
                    // either; the gate carries the instruction instead.
                    if (forced) {
                        showRequired(activity, release, why)
                    } else {
                        Dialogs.info(context, context.getString(R.string.update_title), why)
                    }
                    return@onSuccess
                }
                val ready = AlertDialog.Builder(context, R.style.Theme_Zenktx_Dialog)
                    .setTitle(R.string.update_ready)
                    .apply { if (!forced) setNegativeButton(R.string.update_later, null) }
                    .setCancelable(!forced)
                    .create()
                ready.setButton(AlertDialog.BUTTON_POSITIVE, context.getString(R.string.update_install)) { _, _ ->
                    awaitingInstall = true
                    runCatching { UpdateChecker.install(context, apk) }
                        .onFailure {
                            awaitingInstall = false
                            val why = it.message ?: "Install failed"
                            if (forced) {
                                // The install sheet is replaced, not stacked.
                                ready.dismiss()
                                showRequired(activity, release, why)
                            } else {
                                Dialogs.info(context, context.getString(R.string.update_title), why)
                            }
                        }
                }
                ready.show()
            }.onFailure {
                val why = it.message ?: "unknown error"
                if (forced) {
                    showRequired(activity, release, why)
                } else {
                    Dialogs.info(
                        context,
                        context.getString(R.string.update_title),
                        context.getString(R.string.update_failed, why),
                    )
                }
            }
        }
    }
}
