package ph.zenktx.tv.data

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import ph.zenktx.tv.net.Http

/**
 * GitHub-backed playlist sync: the channel file lives in a repo, every device
 * pulls the same list from the raw URL, and the TV app can push its own current
 * list back with the Contents API.
 */
data class GitHubConfig(
    val owner: String = "",
    val repo: String = "",
    val path: String = "channels.m3u",
    val branch: String = "main",
    val token: String = "",
) {
    val isReady: Boolean get() = owner.isNotBlank() && repo.isNotBlank() && path.isNotBlank()
    val slug: String get() = "$owner/$repo"
}

class GitHubStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("zenktx_github", Context.MODE_PRIVATE)

    fun config(): GitHubConfig = GitHubConfig(
        owner = prefs.getString("owner", "").orEmpty(),
        repo = prefs.getString("repo", "").orEmpty(),
        path = prefs.getString("path", "channels.m3u").orEmpty(),
        branch = prefs.getString("branch", "main").orEmpty(),
        token = prefs.getString("token", "").orEmpty(),
    )

    fun save(config: GitHubConfig) {
        prefs.edit()
            .putString("owner", config.owner.trim())
            .putString("repo", config.repo.trim())
            .putString("path", config.path.trim().ifBlank { "channels.m3u" })
            .putString("branch", config.branch.trim().ifBlank { "main" })
            .putString("token", config.token.trim())
            .apply()
    }
}

object GitHubSync {

    fun rawUrl(config: GitHubConfig): String =
        "https://raw.githubusercontent.com/${config.owner}/${config.repo}/" +
            "${config.branch}/${config.path}"

    /** Downloads the channel file. Works for public repos without a token. */
    fun pull(config: GitHubConfig): String {
        require(config.isReady) { "Set the GitHub owner and repository first" }
        if (config.token.isBlank()) return Http.getText(rawUrl(config))
        val body = Http.getText(contentsUrl(config), token = config.token)
        val encoded = JSONObject(body).optString("content").replace("\n", "")
        return String(Base64.decode(encoded, Base64.DEFAULT))
    }

    /**
     * Replaces the remote file with [content]. Needs a token with `contents:write`.
     */
    fun push(config: GitHubConfig, content: String, message: String): String {
        require(config.isReady) { "Set the GitHub owner and repository first" }
        require(config.token.isNotBlank()) { "Pushing needs a fine-grained GitHub token" }

        val url = contentsUrl(config)
        val sha = runCatching {
            JSONObject(Http.getText(url, token = config.token)).optString("sha")
        }.getOrNull().orEmpty()

        val payload = JSONObject()
            .put("message", message.ifBlank { "Update channel playlist from ZENKTX TV" })
            .put("content", Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP))
            .put("branch", config.branch)
        if (sha.isNotBlank()) payload.put("sha", sha)

        return Http.put(url, payload.toString().toByteArray(), config.token)
    }

    private fun contentsUrl(config: GitHubConfig): String =
        "https://api.github.com/repos/${config.owner}/${config.repo}/contents/${config.path}" +
            "?ref=${config.branch}"
}

/** Turns the loaded channel list into a portable M3U playlist. */
object M3uWriter {

    fun write(channels: List<Channel>): String = buildString {
        appendLine("#EXTM3U")
        channels.forEach { channel ->
            append("#EXTINF:-1")
            if (channel.logo.isNotBlank()) append(" tvg-logo=\"${channel.logo}\"")
            append(" group-title=\"${channel.category}\"")
            append(",")
            appendLine(channel.name)
            appendLine(channel.url)
        }
    }
}
