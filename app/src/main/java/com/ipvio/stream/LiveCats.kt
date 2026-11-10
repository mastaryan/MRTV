package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * MR TV 2 (2.0.6): clean Live TV categories.
 *
 * The provider names live categories like "|NA| USA GENERAL", "|AM| MEXICO" or "|AR| BEIN SPORTS 4K". This maps every
 * category to a country (or region), a clean English label, a small topic icon and an order, so the list reads
 * "United States › General, ABC, News, Sports, ESPN …" with flags instead of tags. The table is a JSON file: bundled in
 * assets/mrtv2-categories.json and refreshed from watchmrtv.com (cached; the bundled copy is the fallback, a missing
 * or broken download changes nothing). A category that is not in the table is parsed from its tag, so a country
 * added to the panel later still shows clean. Virtual groups (ESPN) collect channels from several categories.
 *
 * Display only: the provider's raw category name stays the key everywhere (favorites, hidden categories, last choice).
 */
object LiveCats {
    const val REMOTE_URL = "https://watchmrtv.com/mrtv2-categories.json"
    const val ASSET = "mrtv2-categories.json"
    private const val CACHE = "mrtv2-categories.json"
    private const val REFRESH_MS = 12 * 3600_000L
    const val VIRTUAL_PREFIX = "V:"
    const val HEADER_PREFIX = "H:"
    const val ADULT = "ADULT"

    class Country(val id: String, val name: String, val flag: String?)

    /** One category: [country] id, clean [label], [topic] = icon key, [order] inside its country. */
    class Info(val raw: String, val country: String, val label: String, val topic: String, val order: Int)

    class Virtual(
        val id: String, val country: String, val label: String, val topic: String, val order: Int,
        val from: Set<String>, val include: Regex, val exclude: Regex?, val excludeCats: Set<String>
    ) { val key: String get() = VIRTUAL_PREFIX + id }

    class Model(
        val version: Int,
        val countries: Map<String, Country>,
        val first: List<String>, val last: List<String>,
        val tags: Map<String, String>,
        /** Country words, longest first. */
        val words: List<Pair<String, String>>,
        val labels: Map<String, String>,
        val topicOrder: Map<String, Int>,
        val topics: List<Pair<String, Regex>>,
        val cats: Map<String, Info>,
        val virtual: List<Virtual>
    )

    /** Changes when a newer table was loaded (the category list redraws). */
    var version by mutableIntStateOf(0)
        private set

    @Volatile private var model: Model? = null
    private val memo = ConcurrentHashMap<String, Info>()
    @Volatile private var refreshing = false

    fun norm(raw: String): String = raw.replace('\u00a0', ' ').replace(WS, " ").trim().uppercase(Locale.ROOT)
    private val WS = Regex("""\s+""")

    fun parse(json: String): Model {
        val o = JSONObject(json)
        val countries = LinkedHashMap<String, Country>()
        o.optJSONArray("countries")?.let { a ->
            for (i in 0 until a.length()) {
                val c = a.getJSONObject(i)
                val id = c.getString("id")
                countries[id] = Country(id, c.getString("name"), c.optString("flag").takeIf { it.isNotBlank() })
            }
        }
        fun strList(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        fun strMap(k: String): Map<String, String> = o.optJSONObject(k)?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } } ?: emptyMap()
        val topicOrder = o.optJSONObject("topicOrder")?.let { m -> m.keys().asSequence().associateWith { m.getInt(it) } } ?: emptyMap()
        val topics = ArrayList<Pair<String, Regex>>()
        o.optJSONArray("topics")?.let { a ->
            for (i in 0 until a.length()) {
                val t = a.getJSONObject(i)
                runCatching { topics.add(t.getString("t") to Regex(t.getString("re"), RegexOption.IGNORE_CASE)) }
            }
        }
        val cats = HashMap<String, Info>()
        o.optJSONObject("categories")?.let { m ->
            for (k in m.keys()) {
                val e = m.getJSONObject(k)
                val t = e.optString("t", "general")
                cats[norm(k)] = Info(k, e.getString("c"), e.getString("n"), t, e.optInt("o", topicOrder[t] ?: 90))
            }
        }
        val virtual = ArrayList<Virtual>()
        o.optJSONArray("virtual")?.let { a ->
            for (i in 0 until a.length()) runCatching {
                val v = a.getJSONObject(i)
                val t = v.optString("t", "sports")
                virtual.add(Virtual(
                    v.getString("id"), v.getString("c"), v.getString("n"), t, v.optInt("o", topicOrder[t] ?: 90),
                    v.optJSONArray("from")?.let { f -> (0 until f.length()).map { f.getString(it) }.toSet() } ?: emptySet(),
                    Regex(v.getString("include"), RegexOption.IGNORE_CASE),
                    v.optString("exclude").takeIf { it.isNotBlank() }?.let { Regex(it, RegexOption.IGNORE_CASE) },
                    v.optJSONArray("excludeCats")?.let { f -> (0 until f.length()).map { norm(f.getString(it)) }.toSet() } ?: emptySet()
                ))
            }
        }
        return Model(
            o.optInt("version", 0), countries, strList("first"), strList("last"),
            strMap("tags").mapKeys { it.key.uppercase(Locale.ROOT) },
            strMap("words").map { it.key.uppercase(Locale.ROOT) to it.value }.sortedByDescending { it.first.length },
            strMap("labels").mapKeys { norm(it.key) }, topicOrder, topics, cats, virtual
        )
    }

    /** Uses [m] (tests, or the loaded table). */
    fun use(m: Model) { model = m; memo.clear(); version++ }

    fun current(): Model? = model

    /**
     * Loads the bundled table and the cached download (the newer version wins), then refreshes from watchmrtv.com in
     * the background at most every 12 hours. Never throws.
     */
    fun init(c: Context) {
        if (model == null) synchronized(this) {
            if (model == null) {
                val bundled = runCatching { c.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) } }.mapCatching { parse(it) }.getOrNull()
                val cached = runCatching { File(c.filesDir, CACHE).takeIf { it.exists() }?.readText() }.getOrNull()?.let { runCatching { parse(it) }.getOrNull() }
                val m = listOfNotNull(bundled, cached).maxByOrNull { it.version }
                if (m != null) use(m)
            }
        }
        refresh(c.applicationContext)
    }

    private fun refresh(app: Context) {
        if (refreshing) return
        val f = File(app.filesDir, CACHE)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < REFRESH_MS) return
        refreshing = true
        Thread {
            try {
                val req = okhttp3.Request.Builder().url(REMOTE_URL).header("User-Agent", Rebrand.USER_AGENT).build()
                val body = Net.base.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
                if (body != null) {
                    val m = parse(body)
                    if (m.cats.isNotEmpty() && m.countries.isNotEmpty()) {
                        f.writeText(body)
                        if (m.version >= (model?.version ?: -1)) use(m)
                    }
                } else f.setLastModified(System.currentTimeMillis())
            } catch (_: Throwable) {
            } finally { refreshing = false }
        }.apply { isDaemon = true; name = "mrtv-cats" }.start()
    }

    private val TAG = Regex("""^\|\s*([A-Za-z0-9]{2,4})\s*\|\s*(.*)$""")
    private val LEAD_TAG = Regex("""^([A-Za-z]{2,4})\s*[:|-]\s+(.*)$""")

    /** Country, label, icon and order of a raw provider category. */
    fun info(raw: String): Info {
        memo[raw]?.let { return it }
        val m = model
        val r = if (m == null) Info(raw, "WORLD", strip(raw), "general", 90) else m.cats[norm(raw)] ?: fallback(m, raw)
        if (memo.size > 5000) memo.clear()
        memo[raw] = r
        return r
    }

    /** Raw name without its tag ("|AM| MEXICO" -> "MEXICO"); used only without a table. */
    fun strip(raw: String): String {
        val n = raw.replace('\u00a0', ' ').trim()
        return TAG.find(n)?.groupValues?.get(2)?.trim()?.ifEmpty { null } ?: n
    }

    /** Parses a category the table does not know: tag, country word, translated label, topic. */
    fun fallback(m: Model, raw: String): Info {
        val n = raw.replace('\u00a0', ' ').replace(WS, " ").trim()
        var tag = ""
        var rest = n
        TAG.find(n)?.let { tag = it.groupValues[1].uppercase(Locale.ROOT); rest = it.groupValues[2].trim() }
        if (tag.isEmpty()) LEAD_TAG.find(n)?.let { mm ->
            val t = mm.groupValues[1].uppercase(Locale.ROOT)
            if (m.tags.containsKey(t)) { tag = t; rest = mm.groupValues[2].trim() }
        }
        val up = rest.uppercase(Locale.ROOT)
        var country: String? = null
        for ((w, c) in m.words) {
            if (up == w || up.startsWith("$w ") || up.startsWith("$w|")) {
                country = c
                rest = rest.substring(minOf(w.length, rest.length)).trim(' ', '|', '-', ':')
                break
            }
        }
        if (adultRe.containsMatchIn(up)) country = ADULT
        val cid = (country ?: m.tags[tag] ?: "WORLD").takeIf { m.countries.containsKey(it) } ?: "WORLD"
        val label = niceLabel(m, rest)
        val topic = if (cid == ADULT) "adult" else if (label == "General") "general" else m.topics.firstOrNull { it.second.containsMatchIn(rest) }?.first ?: "general"
        return Info(raw, cid, label, topic, m.topicOrder[topic] ?: 90)
    }

    private val adultRe = Regex("""\b(ADULTS?|XXX|FOR ADULTS)\b|\+18|18\+""", RegexOption.IGNORE_CASE)
    private val KEEP = setOf("TV", "HD", "FHD", "UHD", "SD", "PPV", "VIP", "HEVC", "4K", "8K", "DAZN", "ESPN", "NBA", "NFL", "NHL", "MLB", "MLS", "NCAA", "UFC", "WWE", "BBC", "ITV", "HBO", "MBC", "OSN")

    private fun niceLabel(m: Model, s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return "General"
        m.labels[norm(t)]?.let { return it }
        if (t.any { it.code > 0x24F }) return t   // other scripts: keep as they are
        return t.uppercase(Locale.ROOT).split(' ').filter { it.isNotEmpty() }.joinToString(" ") { w ->
            if (w in KEEP || w.any { it.isDigit() }) w else w.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }

    fun country(id: String): Country = model?.countries?.get(id) ?: Country(id, id, null)

    /** Short display text of a category, e.g. under a channel name: "United States · General". */
    fun title(raw: String): String {
        val i = info(raw)
        val c = country(i.country)
        return if (i.country == ADULT) c.name else c.name + " · " + i.label
    }

    fun isVirtualKey(k: String) = k.startsWith(VIRTUAL_PREFIX)
    fun isHeaderKey(k: String) = k.startsWith(HEADER_PREFIX)

    // ───────── grouping for the Live TV list ─────────

    class Row(val key: String, val label: String, val topic: String, val count: Int, val order: Int)
    class Section(val country: Country, val rows: List<Row>) {
        val total: Int get() = rows.sumOf { it.count }
        val key: String get() = HEADER_PREFIX + country.id
    }

    /** Channels of the virtual groups (ESPN …), deduplicated by name, in the order the include rule lists them. */
    fun virtuals(live: List<Item>): LinkedHashMap<String, List<Item>> {
        val out = LinkedHashMap<String, List<Item>>()
        val m = model ?: return out
        for (v in m.virtual) {
            val l = live.filter { ch ->
                val i = info(ch.group)
                (v.from.isEmpty() || i.country in v.from) && norm(ch.group) !in v.excludeCats &&
                    v.include.containsMatchIn(cleanName(ch.title)) && (v.exclude == null || !v.exclude.containsMatchIn(ch.title))
            }.distinctBy { cleanName(it.title).uppercase(Locale.ROOT) }
            if (l.isNotEmpty()) out[v.key] = l
        }
        return out
    }

    private val NAME_PREFIX = Regex("""^\s*(?:USA|US|EN|UK|CA|SP)\s*[-:|]\s*""", RegexOption.IGNORE_CASE)
    /** Channel name without the provider prefix ("USA - ESPN 2 HD" -> "ESPN 2 HD"). */
    fun cleanName(t: String): String = NAME_PREFIX.replace(t, "")

    fun virtualOf(key: String): Virtual? = model?.virtual?.firstOrNull { it.key == key }

    /**
     * Sections in display order: the table's "first" countries (United States, International), then all others A–Z,
     * the "last" ones (Adults) at the end. Inside a section: by order, then label. [groups] = raw category -> channels.
     */
    fun sections(groups: Map<String, List<Item>>, virtual: Map<String, List<Item>>): List<Section> {
        val m = model
        val by = LinkedHashMap<String, ArrayList<Row>>()
        for ((raw, l) in groups) {
            val i = info(raw)
            by.getOrPut(i.country) { ArrayList() }.add(Row(raw, i.label, i.topic, l.size, i.order))
        }
        if (m != null) for (v in m.virtual) {
            val l = virtual[v.key] ?: continue
            // a virtual group only shows next to real categories of its country
            by[v.country]?.add(Row(v.key, v.label, v.topic, l.size, v.order))
        }
        val first = m?.first ?: listOf("US")
        val last = m?.last ?: listOf(ADULT)
        val ids = by.keys.sortedWith(compareBy<String>({
            val f = first.indexOf(it); val l = last.indexOf(it)
            if (f >= 0) f else if (l >= 0) 10_000 + l else 1000
        }, { country(it).name.lowercase(Locale.ROOT) }))
        return ids.map { id -> Section(country(id), by.getValue(id).sortedWith(compareBy({ it.order }, { it.label.lowercase(Locale.ROOT) }))) }
    }
}

/** MR TV 2 (2.0.6): how the Live TV list shows the country sections (Android-free, unit tested). */
object LiveCatsUi {
    /** Open at the start: the United States, every section when there are only a few, and the one holding [sel]. */
    fun initialExpanded(sections: List<LiveCats.Section>, sel: String?): Set<String> {
        val multi = sections.filter { it.rows.size > 1 }
        val s = HashSet<String>()
        s.add("US")
        if (multi.size <= 3) multi.forEach { s.add(it.country.id) }
        if (sel != null) sections.firstOrNull { sec -> sec.rows.any { it.key == sel } }?.let { s.add(it.country.id) }
        return s
    }

    /** List keys: a one-category section is one row; bigger sections are a header plus (when open) their rows. */
    fun flatten(sections: List<LiveCats.Section>, expanded: Set<String>): List<String> {
        val out = ArrayList<String>()
        for (sec in sections) {
            if (sec.rows.size == 1) { out.add(sec.rows[0].key); continue }
            out.add(sec.key)
            if (sec.country.id in expanded) sec.rows.forEach { out.add(it.key) }
        }
        return out
    }

    /** Topic, label and (for one-row sections) the country whose flag and name the row shows. */
    fun rowOf(sections: List<LiveCats.Section>, key: String): Triple<String, String, LiveCats.Country?>? {
        for (sec in sections) {
            val r = sec.rows.firstOrNull { it.key == key } ?: continue
            return if (sec.rows.size == 1) Triple(r.topic, sec.country.name, sec.country)
            else Triple(r.topic, r.label, null)
        }
        return null
    }

    fun inSection(sections: List<LiveCats.Section>, key: String): Boolean = sections.any { sec -> sec.rows.size > 1 && sec.rows.any { it.key == key } }
}

/** MR TV 2 (2.0.6): countries of the Live TV categories for the hide switches (in the Live TV list order). */
object CountryHide {
    fun countries(items: List<Item>): List<Pair<LiveCats.Country, Int>> {
        val groups = LinkedHashMap<String, MutableList<Item>>()
        for (i in items) if (i.kind == Kind.LIVE) groups.getOrPut(i.group) { ArrayList() }.add(i)
        return LiveCats.sections(groups, emptyMap()).map { it.country to it.total }
    }
}
