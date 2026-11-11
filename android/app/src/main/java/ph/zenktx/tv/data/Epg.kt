package ph.zenktx.tv.data

import android.content.Context
import org.json.JSONArray
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
 * covers them is published elsewhere, so [INDEX_URL] names the XMLTV feeds the
 * apps should read. Republishing `docs/epg.json` moves every installed copy onto
 * a better guide without an APK.
 *
 * Two feeds are read and merged. One feed alone is not enough: epgshare01 knows
 * GMA and TV5 but its per-channel coverage comes and goes, while iptv-epg has
 * eighty channels with a full day each but has never heard of GMA. Merged, a
 * channel is covered when either feed has it.
 *
 * What was fetched is merged with what the device already had rather than
 * replacing it, and the merged rows are cached as JSON. A feed that ships a
 * truncated or half-generated file can therefore only add rows, never erase a
 * schedule that was already on the device.
 */
object Epg {

    const val INDEX_URL = "https://ppoykulot-lgtm.github.io/zenktx-tv/epg.json"

    /** Used when the published index cannot be read. */
    private val DEFAULT_GUIDES = listOf(
        "https://epgshare01.online/epgshare01/epg_ripper_PH2.xml.gz",
        "https://iptv-epg.org/files/epg-ph.xml.gz",
    )

    /** Pre-merged-XML cache, kept only to absorb a guide downloaded by 1.5.11. */
    private const val LEGACY_FILE = "epg.xml"

    private const val FILE = "epg-cache.json"
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

    /**
     * Channel name as the guide spells it: no quality suffix, no country prefix
     * ("PH - A2Z"), no punctuation.
     */
    fun normalise(name: String): String = name.lowercase()
        .replace(Regex("^\\s*[a-z]{2}\\s*-\\s*"), "")
        .replace(Regex("\\b(hd|fhd|sd|uhd|4k)\\b"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    /** The guide held in memory, or the copy on disk when the app just started. */
    fun cached(context: Context): Map<String, List<Program>> {
        if (loadedOnce) return guide
        synchronized(this) {
            if (loadedOnce) return guide
            val app = context.applicationContext
            guide = runCatching { readCache(app) }.getOrDefault(emptyMap())
            loadedAt = System.currentTimeMillis()
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
        val existing = cached(context)

        val fetched = ArrayList<Map<String, List<Program>>>()
        for (url in guides(app)) {
            val text = runCatching { String(Http.getBytes(url), Charsets.UTF_8) }
                .getOrNull()
                ?.takeIf { it.contains("<tv") }
                ?: continue
            val parsed = runCatching { parse(text) }.getOrDefault(emptyMap())
            if (parsed.isNotEmpty()) fetched += parsed
        }
        if (fetched.isEmpty()) return false

        val merged = merge(existing, *fetched.toTypedArray())
        if (merged.isEmpty()) return false

        runCatching { File(app.filesDir, FILE).writeText(serialise(merged)) }
        runCatching { File(app.filesDir, LEGACY_FILE).delete() }
        guide = merged
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

    // ---- feeds -------------------------------------------------------------

    /**
     * The guides the published index names. `urls` is the current shape; `url`
     * is what 1.5.11 and older published, and it still has to work.
     */
    private fun guides(context: Context): List<String> {
        val index = runCatching { JSONObject(Http.getText(INDEX_URL, 10_000)) }.getOrNull()
            ?: return DEFAULT_GUIDES
        val list = ArrayList<String>()
        val urls = index.optJSONArray("urls")
        if (urls != null) {
            for (i in 0 until urls.length()) {
                val url = urls.optString(i).trim()
                if (url.startsWith("http")) list += url
            }
        }
        if (list.isEmpty()) {
            val url = index.optString("url").trim()
            if (url.startsWith("http")) list += url
        }
        return if (list.isEmpty()) DEFAULT_GUIDES else list
    }

    private fun readCache(app: Context): Map<String, List<Program>> {
        val json = File(app.filesDir, FILE)
        if (json.exists()) {
            val parsed = runCatching { parseCache(json.readText()) }.getOrDefault(emptyMap())
            if (parsed.isNotEmpty()) return parsed
        }
        // A 1.5.11 cache is one merged XML file; read it once so the guide is
        // not empty on the first launch after updating.
        val legacy = File(app.filesDir, LEGACY_FILE)
        if (!legacy.exists()) return emptyMap()
        val text = runCatching { legacy.readText() }.getOrDefault("")
        return if (text.isBlank()) emptyMap() else runCatching { parse(text) }.getOrDefault(emptyMap())
    }

    /** [{"key":[[title,start,stop,desc],…],…}] laid out as one flat JSON object. */
    private fun serialise(rows: Map<String, List<Program>>): String {
        val channels = JSONObject()
        for ((key, list) in rows) {
            val array = JSONArray()
            for (program in list) {
                array.put(
                    JSONArray()
                        .put(program.title)
                        .put(program.start)
                        .put(program.stop)
                        .put(program.description),
                )
            }
            channels.put(key, array)
        }
        return JSONObject()
            .put("at", System.currentTimeMillis())
            .put("rows", channels)
            .toString()
    }

    private fun parseCache(text: String): Map<String, List<Program>> {
        val root = JSONObject(text)
        val channels = root.optJSONObject("rows") ?: return emptyMap()
        val now = System.currentTimeMillis()
        val from = now - WINDOW_BEFORE_MS
        val to = now + WINDOW_AFTER_MS

        val out = HashMap<String, List<Program>>()
        for (key in channels.keys()) {
            val list = channels.optJSONArray(key) ?: continue
            val rows = ArrayList<Program>(list.length())
            for (i in 0 until list.length()) {
                val row = list.optJSONArray(i) ?: continue
                val program = Program(
                    title = row.optString(0),
                    start = row.optLong(1),
                    stop = row.optLong(2),
                    description = row.optString(3),
                )
                if (program.title.isBlank() || program.stop <= program.start) continue
                if (program.stop < from || program.start > to) continue
                rows += program
            }
            if (rows.isNotEmpty()) out[key] = rows.sortedBy { it.start }
        }
        return out
    }

    // ---- merging -----------------------------------------------------------

    /**
     * Union of every guide, keyed the way a channel is looked up, then by start
     * time: the same programme arriving from two feeds is stored once.
     */
    private fun merge(vararg parts: Map<String, List<Program>>): Map<String, List<Program>> {
        val now = System.currentTimeMillis()
        val from = now - WINDOW_BEFORE_MS
        val to = now + WINDOW_AFTER_MS

        val out = HashMap<String, MutableList<Program>>()
        for (part in parts) {
            for ((key, list) in part) {
                val rows = out.getOrPut(key) { ArrayList() }
                val starts = rows.mapTo(HashSet()) { it.start }
                for (program in list) {
                    if (program.stop < from || program.start > to) continue
                    if (starts.add(program.start)) rows += program
                }
            }
        }
        return out.mapValues { (_, rows) -> rows.sortedBy { it.start } }
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

        val out = HashMap<String, MutableList<Program>>()
        for ((id, program) in pending) {
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
