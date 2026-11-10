package ph.zenktx.tv.data

import android.content.Context
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import ph.zenktx.tv.net.Http
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** One row of the guide: what is on, and when it started and stopped. */
data class Program(
    val title: String,
    val start: Long,
    val stop: Long,
    val description: String = "",
)

/**
 * The electronic programme guide.
 *
 * The apps do not carry a schedule: these are free channels and whatever guide
 * covers them is published elsewhere, so [INDEX_URL] names the XMLTV feed the
 * apps should read. Republishing `docs/epg.json` moves every installed copy onto
 * a better guide without an APK.
 *
 * The XMLTV channel ids ("GMA.ph") mean nothing to a catalogue row called
 * "GMA HD", so rows are matched on their display names, normalised the same way
 * the mirror search normalises channel names.
 */
object Epg {

    const val INDEX_URL = "https://ppoykulot-lgtm.github.io/zenktx-tv/epg.json"

    /** Used when the published index cannot be read. */
    private const val DEFAULT_GUIDE =
        "https://epgshare01.online/epgshare01/epg_ripper_PH2.xml.gz"

    private const val FILE = "epg.xml"
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    /** Two days back, two days ahead: enough for a live guide, small enough to hold. */
    private const val WINDOW_BEFORE_MS = 2L * 24 * 60 * 60 * 1000
    private const val WINDOW_AFTER_MS = 2L * 24 * 60 * 60 * 1000

    @Volatile
    private var guide: Map<String, List<Program>> = emptyMap()

    @Volatile
    private var loadedAt = 0L

    @Volatile
    private var loadedOnce = false

    /** Channel name as the guide spells it: no quality suffix, no punctuation. */
    fun normalise(name: String): String = name.lowercase()
        .replace(Regex("\\b(hd|fhd|sd|uhd|4k)\\b"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    /** The guide held in memory, or the copy on disk when the app just started. */
    fun cached(context: Context): Map<String, List<Program>> {
        if (loadedOnce) return guide
        synchronized(this) {
            if (loadedOnce) return guide
            val text = runCatching { File(context.applicationContext.filesDir, FILE).readText() }
                .getOrDefault("")
            if (text.isNotBlank()) {
                guide = runCatching { parse(text) }.getOrDefault(emptyMap())
                loadedAt = System.currentTimeMillis()
            }
            loadedOnce = true
            return guide
        }
    }

    /** True while the copy on disk is old enough to be worth replacing. */
    fun stale(context: Context): Boolean {
        val file = File(context.applicationContext.filesDir, FILE)
        return !file.exists() || System.currentTimeMillis() - file.lastModified() > TTL_MS
    }

    /** Blocking: downloads, parses and caches the guide. Background thread only. */
    fun refresh(context: Context): Boolean = synchronized(this) {
        val app = context.applicationContext
        val url = runCatching { JSONObject(Http.getText(INDEX_URL, 10_000)).optString("url") }
            .getOrDefault("")
            .ifBlank { DEFAULT_GUIDE }

        val text = runCatching { String(Http.getBytes(url), Charsets.UTF_8) }
            .getOrNull()
            ?.takeIf { it.contains("<tv") }
            ?: return false

        val parsed = runCatching { parse(text) }.getOrDefault(emptyMap())
        if (parsed.isEmpty()) return false

        runCatching { File(app.filesDir, FILE).writeText(text) }
        guide = parsed
        loadedAt = System.currentTimeMillis()
        loadedOnce = true
        return true
    }

    /** What is on right now, if the guide knows this channel. */
    fun now(channelName: String, at: Long = System.currentTimeMillis()): Program? =
        row(channelName).firstOrNull { at in it.start until it.stop }

    /** The programme after the current one. */
    fun next(channelName: String, at: Long = System.currentTimeMillis()): Program? {
        val rows = row(channelName)
        val current = rows.indexOfFirst { at in it.start until it.stop }
        val from = if (current >= 0) current + 1 else rows.indexOfFirst { it.start > at }
        return if (from >= 0) rows.getOrNull(from) else null
    }

    /** Everything the guide holds for this channel, from now on. */
    fun schedule(channelName: String, limit: Int = 24): List<Program> {
        val at = System.currentTimeMillis()
        return row(channelName).filter { it.stop > at }.take(limit)
    }

    private fun row(channelName: String): List<Program> {
        if (guide.isEmpty()) return emptyList()
        val key = normalise(channelName)
        return guide[key] ?: guide[key.removeSuffix(" tv").trim()] ?: emptyList()
    }

    // ---- XMLTV -------------------------------------------------------------

    private fun parse(xml: String): Map<String, List<Program>> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())

        val names = HashMap<String, String>()
        val pending = ArrayList<Pair<String, Program>>()

        var channelId: String? = null
        var programmeChannel = ""
        var title = ""
        var description = ""
        var start = 0L
        var stop = 0L
        var inProgramme = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> channelId = parser.getAttributeValue(null, "id")

                    "display-name" ->
                        if (channelId != null) {
                            val text = parser.nextText().trim()
                            if (text.isNotEmpty()) names.putIfAbsent(channelId, text)
                            channelId = null
                        }

                    "programme" -> {
                        inProgramme = true
                        programmeChannel = parser.getAttributeValue(null, "channel").orEmpty()
                        start = xmltvTime(parser.getAttributeValue(null, "start"))
                        stop = xmltvTime(parser.getAttributeValue(null, "stop"))
                        title = ""
                        description = ""
                    }

                    "title" -> if (inProgramme) title = parser.nextText()
                    "desc" -> if (inProgramme) description = parser.nextText()
                }

                XmlPullParser.END_TAG -> if (parser.name == "programme") {
                    inProgramme = false
                    if (programmeChannel.isNotBlank() && title.isNotBlank() && stop > start) {
                        pending += programmeChannel to Program(
                            title = title.trim(),
                            start = start,
                            stop = stop,
                            description = description.trim(),
                        )
                    }
                }
            }
            event = parser.next()
        }

        val now = System.currentTimeMillis()
        val from = now - WINDOW_BEFORE_MS
        val to = now + WINDOW_AFTER_MS
        val out = HashMap<String, MutableList<Program>>()

        for ((id, program) in pending) {
            if (program.stop < from || program.start > to) continue
            val display = names[id] ?: continue
            val key = normalise(display)
            if (key.isEmpty()) continue
            out.getOrPut(key) { ArrayList() } += program
        }

        return out.mapValues { (_, rows) -> rows.sortedBy { it.start } }
    }

    /** XMLTV stamps look like "20261110110000 +0000". */
    private fun xmltvTime(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val format = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        return runCatching { format.parse(value.trim())?.time ?: 0L }.getOrDefault(0L)
    }
}
