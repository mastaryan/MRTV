package com.ipvio.stream.locals

/**
 * Finds a market's local stations among the provider's live channel names (pure logic, unit-tested).
 * A channel belongs to a market when its name carries one of the market's call signs ("WLEX", "WGN-TV", "KTLA 5"),
 * or a network word together with one of the market's cities ("Chicago ABC 7", "ABC Houston TX").
 * Ported from an earlier app version; the national/cable feed filter keeps "FOX Sports", "NBC News Now" … out.
 */
object LocalMatcher {
    data class Group(val net: LocalNet, val channelIdx: List<Int>)

    private val ORDER = LocalNet.entries.toList()
    /** Names that carry a network word but are national / cable feeds, not locals. */
    private val NOT_LOCAL = Regex("""\b(SPORTS?|SPORTSNET|NEWS ?NOW|NEWS CHANNEL|NEWS NETWORK|BUSINESS|DEPORTES|LIFE|KIDS|COMEDY|MOVIES?|ACTION|CRIME|SOAP|NUMEROS|NOW|HEARTLAND|BET|CNBC|MSNBC|CBSN|FS1|FS2|FX|NFL|NBA|MLB|NHL|NCAA|WWE|PPV|EVENT)\b""")
    private val NEWS_ONLY = Regex("""^\W*(ABC|CBS|NBC|FOX)\s+NEWS\W*$""")
    /** Leading provider tags ("US:", "USA |", "[US]") that say nothing about the station. */
    private val PREFIX = setOf("US", "USA", "UNITED STATES", "LOCAL", "LOCALS", "HD", "FHD", "SD", "4K", "UHD", "TV", "CH", "CHANNEL")
    private val SUFFIX = setOf("TV", "DT", "HD", "LD", "CD", "CA", "FHD", "SD", "UHD", "4K")

    fun norm(s: String) = " " + s.uppercase().replace(Regex("[^A-Z0-9]+"), " ").trim() + " "
    private fun hasWord(n: String, w: String) = norm(w).trim().let { it.isNotEmpty() && n.contains(" $it ") }
    private fun netFromName(n: String): LocalNet? = ORDER.firstOrNull { net -> net.words.any { hasWord(n, it) } }
    private fun city(m: Market, n: String) = m.cities.any { hasWord(n, it) } ||
        m.ambiguousCities.any { a -> hasWord(n, a) && (m.states.any { s -> hasWord(n, s) } || m.stateNames.any { s -> hasWord(n, s) }) }

    /** Call signs that are also English words: alone they need more proof ("King of Queens" is not Seattle's KING). */
    private val WORDLIKE = setOf("KING", "WAVE", "WEAR", "WEEK", "WHO", "WILL", "WISE", "WISH", "WOOD", "WHA", "WIS", "KAKE")

    /**
     * True when [call] appears as a word in [n] (also "WGN-TV", "KTLA5", "WABC-DT"). A word-like call sign counts only
     * with a TV suffix or channel number next to it, a network word, one of the market's cities, or on its own.
     */
    private fun callIn(m: Market, n: String, call: String): Boolean {
        val hit = Regex("""\s$call((\s?(TV|DT|HD|LD|CD|CA))?(\s?\d{1,2})?)\s""").find(n) ?: return false
        if (call !in WORDLIKE || hit.groupValues[1].isNotBlank()) return true
        val rest = n.trim().split(' ').filter { it.isNotEmpty() && it != call && it !in PREFIX && it !in SUFFIX }
        return rest.isEmpty() || netFromName(n) != null || city(m, n)
    }

    /** Network of one channel name in [m], or null if it is not one of the market's locals. */
    fun matchOne(m: Market, rawName: String): LocalNet? {
        val n = norm(rawName)
        m.stations.firstOrNull { callIn(m, n, it.call) }?.let { st ->
            val named = netFromName(n)
            return if (named != null && m.stations.any { it.call == st.call && it.net == named }) named else st.net
        }
        val net = netFromName(n) ?: return null
        if (NOT_LOCAL.containsMatchIn(n) || NEWS_ONLY.containsMatchIn(rawName.uppercase())) return null
        return if (city(m, n)) net else null
    }

    /**
     * All of the market's channels, grouped by network in ABC, NBC, CBS, FOX, CW, PBS, MyNetwork, Telemundo, Univision,
     * Independent order; duplicates of a station (HD / SD / backup) stay next to each other, best quality first.
     */
    fun <T> group(m: Market, channels: List<T>, name: (T) -> String, category: ((T) -> String)? = null): List<Group> {
        val hits = channels.withIndex().mapNotNull { (i, c) -> val nm = name(c); matchIn(m, nm, category?.invoke(c))?.let { Triple(it, i, nm) } }
        return ORDER.mapNotNull { net ->
            hits.filter { it.first == net }.takeIf { it.isNotEmpty() }
                ?.sortedWith(compareBy({ stationKey(m, it.third) }, { quality(it.third) }, { norm(it.third).length }))
                ?.map { it.second }?.distinct()?.let { Group(net, it) }
        }
    }

    /** Flat list in [group] order. */
    fun <T> channels(m: Market, all: List<T>, name: (T) -> String, category: ((T) -> String)? = null): List<T> =
        group(m, all, name, category).flatMap { g -> g.channelIdx.map { all[it] } }

    /**
     * Like [matchOne]; a provider category that names the market's city as locals ("US Locals | Chicago") also counts as
     * the city for channels in it that only carry the network ("ABC 7 HD").
     */
    fun matchIn(m: Market, name: String, category: String?): LocalNet? {
        matchOne(m, name)?.let { return it }
        if (category.isNullOrBlank()) return null
        val c = norm(category)
        if (!(hasWord(c, "LOCAL") || hasWord(c, "LOCALS")) || !city(m, c)) return null
        return matchOne(m, "$name $category")
    }

    /**
     * Favorites grouped by saved market: each market (in saved order) with the favorites that are its locals, then the
     * remaining favorites under null. Empty groups are left out.
     */
    fun <T> groupFavorites(markets: List<Market>, favs: List<T>, name: (T) -> String): List<Pair<Market?, List<T>>> {
        val left = favs.toMutableList()
        val out = ArrayList<Pair<Market?, List<T>>>()
        for (m in markets) {
            val hit = left.filter { matchOne(m, name(it)) != null }
            if (hit.isNotEmpty()) { out.add(m to hit); left.removeAll(hit.toSet()) }
        }
        if (left.isNotEmpty()) out.add(null to left)
        return out
    }

    private fun stationKey(m: Market, name: String): String { val n = norm(name); return m.stations.firstOrNull { callIn(m, n, it.call) }?.call ?: "~" }
    private fun quality(s: String) = norm(s).let { when { hasWord(it, "4K") || hasWord(it, "UHD") -> 0; hasWord(it, "FHD") || hasWord(it, "HD") -> 1; hasWord(it, "SD") -> 3; hasWord(it, "BACKUP") -> 4; else -> 2 } }
}
