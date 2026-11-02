package ph.zenktx.tv.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import ph.zenktx.tv.BuildConfig
import ph.zenktx.tv.net.Http
import java.io.File
import java.io.IOException

/**
 * In-app updates from GitHub Releases. The APK asset attached to the newest
 * release is downloaded into the app cache and handed to the system installer.
 */
object UpdateChecker {

    data class Release(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val pageUrl: String,
        val sizeBytes: Long,
        val publishedAt: String,
    )

    /** Blocking; call from a background thread. */
    fun latest(repo: String = BuildConfig.GITHUB_REPO, token: String? = null): Release? {
        if (repo.isBlank()) return null
        val json = Http.getText(
            "https://api.github.com/repos/$repo/releases/latest",
            token = token?.ifBlank { null },
        )
        val root = JSONObject(json)
        val assets = root.optJSONArray("assets")
        var apkUrl = ""
        var size = 0L
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name").lowercase()
                if (name.endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url")
                    size = asset.optLong("size")
                    break
                }
            }
        }
        if (apkUrl.isBlank()) return null

        return Release(
            version = root.optString("tag_name").removePrefix("v"),
            notes = root.optString("body"),
            apkUrl = apkUrl,
            pageUrl = root.optString("html_url"),
            sizeBytes = size,
            publishedAt = root.optString("published_at"),
        )
    }

    /** True when [remote] is a higher semantic version than [current]. */
    fun isNewer(current: String, remote: String): Boolean {
        val a = parse(current)
        val b = parse(remote)
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (right != left) return right > left
        }
        return false
    }

    private fun parse(version: String): List<Int> =
        version.removePrefix("v")
            .substringBefore('-')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }

    fun download(
        context: Context,
        release: Release,
        onProgress: (percent: Int) -> Unit = {},
    ): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "zenktx-${release.version}.apk")

        val conn = (java.net.URL(release.apkUrl).openConnection() as java.net.HttpURLConnection)
            .apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", Http.USER_AGENT)
            }

        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Download failed (HTTP $code)")
            val total = if (release.sizeBytes > 0) release.sizeBytes else conn.contentLengthLong
            var copied = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) onProgress((copied * 100 / total).toInt())
                    }
                }
            }
            if (target.length() == 0L) throw IOException("Downloaded file is empty")
            return target
        } finally {
            conn.disconnect()
        }
    }

    /** Opens the system package installer for the downloaded APK. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
