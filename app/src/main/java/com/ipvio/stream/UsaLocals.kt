package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * MR TV 2: "USA Locals" ported from MR TV 1.3 (app/core LocalMarkets.kt, UsaLocals.kt, DmaData.kt; same author).
 * Nielsen DMA table (top 5 markets per state, 2025-26 ranks) and a matcher that finds a market's local affiliates among
 * the provider's live channel names, by call sign (WLEX, WKYT…) or by city alias + network word ("Chicago ABC 7").
 */
object LocalMarkets {
    enum class Net(val label: String, val words: List<String>) {
        ABC("ABC", listOf("ABC")), NBC("NBC", listOf("NBC")), CBS("CBS", listOf("CBS")), FOX("FOX", listOf("FOX")), CW("CW", listOf("CW")),
        PBS("PBS", listOf("PBS")), MNT("MyNetwork", listOf("MYNETWORK", "MYNETWORKTV", "MNT", "MY TV", "MYTV")),
        TEL("Telemundo", listOf("TELEMUNDO")), UNI("Univision", listOf("UNIVISION"));
        companion object { fun code(c: String) = when (c) { "A" -> ABC; "N" -> NBC; "C" -> CBS; "F" -> FOX; "W" -> CW; "P" -> PBS; "M" -> MNT; "T" -> TEL; "U" -> UNI; else -> null } }
    }
    data class Alias(val text: String, val ambiguous: Boolean)
    data class Market(val rank: Int, val name: String, val state: String, val aliases: List<Alias>, val calls: Map<String, List<Net>>)
    data class State(val code: String, val name: String, val home: List<Market>, val nearby: List<Market> = emptyList()) {
        /** Picker entries: the state's own top-5 DMAs, then neighboring markets for states with none (DE, NH, NJ). */
        val markets: List<Market> get() = home + nearby
        fun label(m: Market) = if (m in nearby && m !in home) "${m.name} (nearby)" else m.name
    }
    data class Group(val net: Net, val channelIds: List<Int>)

    val markets: Map<Int, Market> by lazy {
        DmaData.MARKETS.lines().filter { it.isNotBlank() }.associate { l -> val f = l.split('|')
            val calls = HashMap<String, MutableList<Net>>()
            f.getOrElse(4) { "" }.split(' ').filter { '=' in it }.forEach { kv -> val (k, v) = kv.split('='); Net.code(k)?.let { calls.getOrPut(v.uppercase()) { ArrayList() }.add(it) } }
            f[0].toInt() to Market(f[0].toInt(), f[1], f[2], f[3].split(';').filter { it.isNotBlank() }.map { Alias(it.removePrefix("?"), it.startsWith("?")) }, calls) }
    }
    val states: List<State> by lazy {
        DmaData.STATES.lines().filter { it.isNotBlank() }.map { l -> val f = l.split('|'); fun ids(s: String?) = (s ?: "").split(',').mapNotNull { markets[it.trim().toIntOrNull()] }
            State(f[0], f[1], ids(f.getOrNull(2)), ids(f.getOrNull(3))) }
    }
    /** Pref key "rank" ("" or 0 = None). */
    fun byKey(key: Int): Market? = markets[key]

    private val ORDER = Net.values().toList()   // ABC, NBC, CBS, FOX, CW, then PBS, MyNetwork, Telemundo, Univision
    /** Names that carry a network word but are national/cable feeds, not locals. */
    private val NOT_LOCAL = Regex("""\b(SPORTS?|SPORTSNET|NEWS ?NOW|NEWS CHANNEL|NEWS NETWORK|BUSINESS|DEPORTES|LIFE|KIDS|COMEDY|MOVIES?|ACTION|CRIME|SOAP|NUMEROS|NOW|HEARTLAND|BET|CNBC|MSNBC|CBSN|FS1|FS2|FX|NFL|NBA|MLB|NHL|COWBOYS|BEARS|BULLS|CUBS|SOX|BLACKHAWKS|MAVERICKS|STARS|RANGERS|MAGIC|LIGHTNING|BUCCANEERS|WILDCATS)\b""")
    private val NEWS_ONLY = Regex("""^\W*(ABC|CBS|NBC|FOX)\s+NEWS\W*$""")

    private fun norm(s: String) = " " + s.uppercase().replace(Regex("[^A-Z0-9]+"), " ").trim() + " "
    private fun hasWord(n: String, w: String) = n.contains(" " + norm(w).trim() + " ")
    private fun netFromName(n: String): Net? = ORDER.firstOrNull { net -> net.words.any { hasWord(n, it) } }
    private fun callIn(n: String, call: String) = Regex("""\s${call}(\s?(TV|DT|HD|LD|CD|CA))?\d*\s""").containsMatchIn(n)

    /** Network for one channel name in [m], or null if it isn't one of the market's locals. */
    fun matchOne(m: Market, rawName: String): Net? {
        val n = norm(rawName)
        m.calls.entries.firstOrNull { callIn(n, it.key) }?.let { (_, nets) -> return netFromName(n)?.takeIf { it in nets } ?: nets.first() }
        val net = netFromName(n) ?: return null
        if (NOT_LOCAL.containsMatchIn(n) || NEWS_ONLY.containsMatchIn(rawName.uppercase())) return null
        val city = m.aliases.any { a -> hasWord(n, a.text) && (!a.ambiguous || hasWord(n, m.state) || hasWord(n, a.text + " " + m.state)) }
        return if (city) net else null
    }

    /** Groups in ABC, NBC, CBS, FOX, CW, PBS, MyNetwork, Telemundo, Univision order; duplicates (HD/SD/backup) kept next to each other, HD first. */
    fun <T> group(m: Market, channels: List<T>, id: (T) -> Int, name: (T) -> String): List<Group> {
        val hits = channels.mapNotNull { c -> matchOne(m, name(c))?.let { Triple(it, c, name(c)) } }
        return ORDER.mapNotNull { net -> hits.filter { it.first == net }.takeIf { it.isNotEmpty() }
            ?.sortedWith(compareBy({ quality(it.third) }, { norm(it.third).length }))?.map { id(it.second) }?.distinct()?.let { Group(net, it) } }
    }
    private fun quality(s: String) = norm(s).let { when { hasWord(it, "4K") || hasWord(it, "UHD") -> 0; hasWord(it, "FHD") || hasWord(it, "HD") -> 1; hasWord(it, "SD") -> 3; hasWord(it, "BACKUP") -> 4; else -> 2 } }
    fun emptyText(m: Market) = "No local channels found for ${m.name} on your service"
}

/** Pure navigation, lookup and save-list logic for the USA Locals browser in Live TV (no Android, unit-tested). */
object UsaLocals {
    /** Category key of the browser entry in the Live TV list. */
    const val KEY = "LOCALS"
    /** Category key of one city: "CITY:<Nielsen rank>". */
    const val CITY = "CITY:"
    fun cityKey(rank: Int) = CITY + rank
    fun rankOf(key: String?): Int? = key?.takeIf { it.startsWith(CITY) }?.removePrefix(CITY)?.toIntOrNull()?.takeIf { LocalMarkets.byKey(it) != null }
    fun isCity(key: String?) = rankOf(key) != null

    val states get() = LocalMarkets.states.sortedBy { it.name }
    fun state(code: String?) = states.firstOrNull { it.code == code }
    /** A state's own top-5 markets (A–Z), then neighbouring markets for states without one (DE, NH, NJ). */
    fun markets(state: String): List<LocalMarkets.Market> = state(state)?.let { st ->
        st.home.sortedBy { it.name.lowercase() } + (st.nearby - st.home.toSet()).sortedBy { it.name.lowercase() }
    }.orEmpty()
    /** State a market is listed under (its home state first). */
    fun stateOf(rank: Int): String? = LocalMarkets.byKey(rank)?.let { m -> states.firstOrNull { m in it.home }?.code ?: states.firstOrNull { m in it.markets }?.code }
    fun cityLabel(rank: Int) = "Locals – " + (LocalMarkets.byKey(rank)?.name ?: rank.toString())

    /** The market's stations only, in ABC, NBC, CBS, FOX, CW, then others order (duplicates HD first). */
    fun <T> channels(m: LocalMarkets.Market, all: List<T>, id: (T) -> String, name: (T) -> String): List<T> {
        val keyed = all.mapIndexed { i, c -> i to c }
        val byIdx = keyed.toMap()
        return LocalMarkets.group(m, keyed, { it.first }, { name(it.second) }).flatMap { g -> g.channelIds.mapNotNull { byIdx[it] } }.distinctBy(id)
    }

    /** Saved cities (Nielsen ranks): stored as "rank" lines, newest first; unknown ranks are dropped. */
    fun decode(s: String?): List<Int> = (s ?: "").split('\n', ',').mapNotNull { it.trim().toIntOrNull() }.filter { LocalMarkets.byKey(it) != null }.distinct()
    fun encode(ranks: List<Int>): String = ranks.filter { LocalMarkets.byKey(it) != null }.distinct().joinToString("\n")
    fun toggle(ranks: List<Int>, rank: Int): List<Int> = if (rank in ranks) ranks - rank else if (LocalMarkets.byKey(rank) == null) ranks else listOf(rank) + ranks

    /**
     * Favorites grouped by saved city: each saved city (in saved order) with the favorite channels that are its locals,
     * then the remaining favorites under null. Empty groups are left out.
     */
    fun <T> groupFavorites(saved: List<Int>, favs: List<T>, name: (T) -> String): List<Pair<Int?, List<T>>> {
        val left = favs.toMutableList()
        val out = ArrayList<Pair<Int?, List<T>>>()
        for (r in saved) {
            val m = LocalMarkets.byKey(r) ?: continue
            val hit = left.filter { LocalMarkets.matchOne(m, name(it)) != null }
            if (hit.isNotEmpty()) { out.add(r to hit); left.removeAll(hit.toSet()) }
        }
        if (left.isNotEmpty()) out.add(null to left)
        return out
    }
}

/** Saved cities per playlist (Live TV > Favorites, "★ Locals – City"); kept across restarts and playlist reloads. */
object CityFavs {
    var list by mutableStateOf<List<Int>>(emptyList())
        private set
    private var loadedFor: String? = null
    private fun prefs(c: Context) = Profiles.sp(c, "mrtv_cityfavs")
    /** Stable per playlist: the playlist address (no secrets stored, only its hash). */
    private fun scope(): String = PlaylistStore.lists.getOrNull(PlaylistStore.active)?.url?.trim()?.hashCode()?.toString() ?: "0"

    fun init(c: Context) {
        val s = scope()
        if (loadedFor == s) return
        loadedFor = s
        list = UsaLocals.decode(prefs(c).getString("c_" + s, null))
    }
    fun isSaved(rank: Int) = rank in list
    fun toggle(c: Context, rank: Int): Boolean {
        init(c)
        list = UsaLocals.toggle(list, rank)
        prefs(c).edit().putString("c_" + scope(), UsaLocals.encode(list)).apply()
        return rank in list
    }
}
