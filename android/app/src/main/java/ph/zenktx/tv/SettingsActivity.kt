package ph.zenktx.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import ph.zenktx.tv.data.AppSettings
import ph.zenktx.tv.data.Integrity
import ph.zenktx.tv.data.PlaylistStore
import ph.zenktx.tv.databinding.ActivitySettingsBinding
import ph.zenktx.tv.ui.Dialogs
import ph.zenktx.tv.ui.Ui
import ph.zenktx.tv.ui.Updater

/**
 * Device settings: playlist sources, updates and the build summary.
 */
class SettingsActivity : Activity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var playlists: PlaylistStore
    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playlists = PlaylistStore(this)
        settings = AppSettings(this)

        binding.versionLabel.text = "v${BuildConfig.VERSION_NAME}"

        binding.backButton.setOnClickListener { finish() }

        listOf(
            binding.backButton to 1.04f,
            binding.rowPlaylists to 1.02f,
            binding.rowUpdates to 1.02f,
            binding.rowAbout to 1.02f,
            binding.rowDonate to 1.02f,
        ).forEach { (view, scale) -> Ui.attachFocusEffect(view, lift = false, scale = scale) }

        binding.rowPlaylists.setOnClickListener {
            startActivity(Intent(this, PlaylistsActivity::class.java))
        }
        binding.rowUpdates.setOnClickListener { Updater.check(this, silent = false) }
        binding.rowAbout.setOnClickListener { showAbout() }
        binding.rowDonate.setOnClickListener { Dialogs.donate(this) }

        binding.backButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        binding.subPlaylists.text = getString(R.string.row_playlists_sub) +
            "  ·  " + playlists.list().size + " source(s)"
        binding.subUpdates.text =
            getString(R.string.row_updates_sub, BuildConfig.VERSION_NAME, if (settings.autoCheckUpdates()) getString(R.string.on) else getString(R.string.off))
        binding.subAbout.text = getString(R.string.row_about_sub, BuildConfig.VERSION_NAME)
    }

    private fun showAbout() {
        val verdict = Integrity.check(this)
        val body = getString(
            R.string.about_body,
            BuildConfig.VERSION_NAME,
            BuildConfig.GITHUB_REPO,
            "${BuildConfig.FLAVOR} · ${BuildConfig.APPLICATION_ID}",
            getString(if (verdict.ok) R.string.integrity_ok else R.string.integrity_title),
        )
        Dialogs.info(this, getString(R.string.row_about), body)
    }

    @Suppress("unused")
    private fun status(message: String) {
        binding.status.visibility = android.view.View.VISIBLE
        binding.status.text = message
        binding.status.setTextColor(ContextCompat.getColor(this, R.color.mint))
    }
}
