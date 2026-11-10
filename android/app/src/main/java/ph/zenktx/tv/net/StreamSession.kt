package ph.zenktx.tv.net

import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One playback's HTTP identity.
 *
 * Every start of a channel gets its own [OkHttpClient]: its own cookie jar, its
 * own connection pool and its own dispatcher. That is the whole point. These
 * providers hand out one session per client, so a player that kept the previous
 * channel's cookies and reused the socket it had just been refused on would read
 * a 410 on every retry, while a second copy of the app — a brand new set of
 * cookies and connections — played the same link straight away.
 *
 * [close] throws all of it away, which is what a completed playback has to do.
 */
class StreamSession {

    val id: Int = COUNTER.incrementAndGet()

    private val cookies = ConcurrentHashMap<String, MutableList<Cookie>>()

    // Short-lived on purpose: a pooled socket is state the next request inherits.
    private val pool = ConnectionPool(2, 3, TimeUnit.SECONDS)

    private val dispatcher = Dispatcher().apply {
        maxRequests = 6
        maxRequestsPerHost = 6
    }

    private val jar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (cookie in cookies) {
                val host = cookie.domain.ifBlank { url.host }
                val list = this@StreamSession.cookies.getOrPut(host) { ArrayList() }
                synchronized(list) {
                    list.removeAll { it.name == cookie.name }
                    list += cookie
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val out = ArrayList<Cookie>()
            val now = System.currentTimeMillis()
            for (list in cookies.values) {
                synchronized(list) {
                    list.removeAll { it.expiresAt < now }
                    out += list.filter { it.matches(url) }
                }
            }
            return out
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(jar)
        // A cached playlist is the previous session's playlist. These tokens
        // roll over through the day, so nothing here may be answered from disk.
        .cache(null)
        .connectionPool(pool)
        .dispatcher(dispatcher)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /** A data source factory bound to this session and nothing else. */
    fun dataSource(): OkHttpDataSource.Factory = OkHttpDataSource.Factory(client)
        .setUserAgent(Http.USER_AGENT)
        .setDefaultRequestProperties(
            mapOf(
                "Accept" to "*/*",
                "Accept-Language" to "en-US,en;q=0.9",
                // Ask every hop for the current playlist instead of a copy the
                // CDN is happy to keep serving.
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache",
            )
        )

    /** Drops the cookies and the sockets this playback was using. */
    fun close() {
        cookies.clear()
        runCatching { pool.evictAll() }
        runCatching { dispatcher.executorService.shutdown() }
    }

    companion object {
        private val COUNTER = AtomicInteger(0)
    }
}
