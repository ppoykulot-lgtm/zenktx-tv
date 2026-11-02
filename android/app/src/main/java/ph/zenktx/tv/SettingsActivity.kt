package ph.zenktx.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ph.zenktx.tv.data.AppSettings
import ph.zenktx.tv.data.ChannelRepository
import ph.zenktx.tv.data.GitHubConfig
import ph.zenktx.tv.data.GitHubStore
import ph.zenktx.tv.data.GitHubSync
import ph.zenktx.tv.data.Integrity
import ph.zenktx.tv.data.LibraryStore
import ph.zenktx.tv.data.M3uWriter
import ph.zenktx.tv.data.PlaylistStore
import ph.zenktx.tv.databinding.ActivitySettingsBinding
import ph.zenktx.tv.ui.Dialogs
import ph.zenktx.tv.ui.Ui
import ph.zenktx.tv.ui.Updater

/**
 * Device settings: playlist sources, GitHub sync, updates and local clean-up.
 */
class SettingsActivity : Activity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var library: LibraryStore
    private lateinit var playlists: PlaylistStore
    private lateinit var github: GitHubStore
    private lateinit var settings: AppSettings

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        library = LibraryStore(this)
        playlists = PlaylistStore(this)
        github = GitHubStore(this)
        settings = AppSettings(this)

        binding.versionLabel.text = "v${BuildConfig.VERSION_NAME}"

        binding.backButton.setOnClickListener { finish() }

        listOf(
            binding.backButton to 1.04f,
            binding.rowPlaylists to 1.02f,
            binding.rowGithub to 1.02f,
            binding.rowUpdates to 1.02f,
            binding.rowLogos to 1.02f,
            binding.rowHidden to 1.02f,
            binding.rowAbout to 1.02f,
        ).forEach { (view, scale) -> Ui.attachFocusEffect(view, lift = false, scale = scale) }

        binding.rowPlaylists.setOnClickListener {
            startActivity(Intent(this, PlaylistsActivity::class.java))
        }
        binding.rowGithub.setOnClickListener { showGitHub() }
        binding.rowUpdates.setOnClickListener { Updater.check(this, silent = false) }
        binding.rowLogos.setOnClickListener { resetLogos() }
        binding.rowHidden.setOnClickListener { restoreHidden() }
        binding.rowAbout.setOnClickListener { showAbout() }

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
        binding.subLogos.text = getString(R.string.row_logos_sub, library.logoOverrides().size)
        binding.subHidden.text = getString(R.string.row_hidden_sub, library.hidden().size)
        binding.subAbout.text = getString(R.string.row_about_sub, BuildConfig.VERSION_NAME)

        val config = github.config()
        binding.subGithub.text = if (config.isReady) config.slug + " · " + config.path
        else getString(R.string.row_github_sub)
    }

    // ---- GitHub -----------------------------------------------------------

    private fun showGitHub() {
        val config = github.config()
        Dialogs.form(
            this,
            getString(R.string.github_title),
            listOf(
                Dialogs.Field("owner", getString(R.string.github_owner), config.owner),
                Dialogs.Field("repo", getString(R.string.github_repo), config.repo),
                Dialogs.Field("path", getString(R.string.github_path), config.path),
                Dialogs.Field("branch", getString(R.string.github_branch), config.branch),
                Dialogs.Field("token", getString(R.string.github_token), config.token, secret = true),
            ),
        ) { values ->
            val saved = GitHubConfig(
                owner = values["owner"].orEmpty(),
                repo = values["repo"].orEmpty(),
                path = values["path"].orEmpty().ifBlank { "channels.m3u" },
                branch = values["branch"].orEmpty().ifBlank { "main" },
                token = values["token"].orEmpty(),
            )
            github.save(saved)
            refresh()
            if (saved.isReady) showGitHubActions() else status(getString(R.string.github_not_configured))
        }
    }

    private fun showGitHubActions() {
        val config = github.config()
        val actions = listOf(
            getString(R.string.github_pull),
            getString(R.string.github_push),
            getString(R.string.github_configure),
        )
        Dialogs.choose(this, config.slug, actions) { index ->
            when (index) {
                0 -> pull(config)
                1 -> push(config)
                else -> showGitHub()
            }
        }
    }

    private fun pull(config: GitHubConfig) {
        status("Pulling ${config.slug}…")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = GitHubSync.pull(config)
                    val parsed = if (text.trimStart().startsWith("#EXTM3U")) {
                        ChannelRepository.fromM3u(text, "gh")
                    } else {
                        ChannelRepository.fromJson(text, "gh")
                    }
                    parsed.size
                }
            }
            result.onSuccess { count ->
                val existing = playlists.list().firstOrNull { it.name == "GitHub · ${config.slug}" }
                if (existing != null) {
                    playlists.setUrl(existing.id, GitHubSync.rawUrl(config))
                    playlists.setEnabled(existing.id, true)
                } else {
                    playlists.add("GitHub · ${config.slug}", GitHubSync.rawUrl(config))
                }
                ChannelRepository.dirty = true
                status(getString(R.string.github_pulled, count))
                refresh()
            }.onFailure { status(it.message ?: "Pull failed") }
        }
    }

    private fun push(config: GitHubConfig) {
        val channels = ChannelRepository.cached()
        if (channels.isEmpty()) {
            status("No channels loaded yet — open the guide first.")
            return
        }
        Dialogs.confirm(
            this,
            getString(R.string.github_push),
            "Replace ${config.owner}/${config.repo}/${config.path} with your current ${channels.size} channels?",
            positive = getString(R.string.github_push),
        ) {
            status("Pushing…")
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        GitHubSync.push(
                            config,
                            M3uWriter.write(channels),
                            "Sync ${channels.size} channels from ZENKTX TV",
                        )
                    }
                }
                result.onSuccess { status(getString(R.string.github_pushed, channels.size)) }
                    .onFailure { status(it.message ?: "Push failed") }
            }
        }
    }

    // ---- local clean-up ---------------------------------------------------

    private fun resetLogos() {
        val count = library.logoOverrides().size
        if (count == 0) {
            status("No logo edits to reset.")
            return
        }
        Dialogs.confirm(
            this,
            getString(R.string.row_logos),
            "Restore the original logo for $count channel(s)?",
            positive = "Reset",
        ) {
            library.clearLogoOverrides()
            ChannelRepository.refresh(library)
            ChannelRepository.dirty = true
            status("Logo edits cleared.")
            refresh()
        }
    }

    private fun restoreHidden() {
        val hidden = library.hidden()
        if (hidden.isEmpty()) {
            status("No hidden channels.")
            return
        }
        Dialogs.confirm(
            this,
            getString(R.string.row_hidden),
            getString(R.string.restore_hidden, hidden.size),
            positive = "Restore",
        ) {
            hidden.forEach { library.setHidden(it, false) }
            ChannelRepository.refresh(library)
            ChannelRepository.dirty = true
            status("Restored ${hidden.size} channel(s).")
            refresh()
        }
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

    private fun status(message: String) {
        binding.status.visibility = View.VISIBLE
        binding.status.text = message
        binding.status.setTextColor(ContextCompat.getColor(this, R.color.mint))
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
