package ph.zenktx.tv.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Small blocking HTTP helper. Always called from a background thread.
 */
object Http {

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 ZENKTX/1.0"

    fun getText(url: String, timeoutMs: Int = 15_000, token: String? = null): String {
        val conn = open(url, token, timeoutMs)
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun put(url: String, body: ByteArray, token: String, timeoutMs: Int = 20_000): String {
        val conn = open(url, token, timeoutMs).apply {
            requestMethod = "PUT"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code: ${text.take(180)}")
            text
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, token: String?, timeoutMs: Int): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "*/*")
        if (!token.isNullOrBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        return conn
    }
}
