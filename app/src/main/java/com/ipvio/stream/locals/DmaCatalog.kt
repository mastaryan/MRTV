package com.ipvio.stream.locals

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * US local TV markets (Nielsen DMAs): all 210 markets, the states each one covers, its principal cities and the
 * call signs of its stations. Data: assets/locals/dmas.json (schema "ipvio.locals.dmas/1", built by
 * tools/locals/gen_dmas.py, sources listed in the file). An optional override with the same schema can be placed
 * in the app's files dir ([OVERRIDE_FILE]); it is used only if it parses and validates, otherwise the bundled file is.
 */
enum class LocalNet(val label: String, val words: List<String>) {
    ABC("ABC", listOf("ABC")), NBC("NBC", listOf("NBC")), CBS("CBS", listOf("CBS")), FOX("FOX", listOf("FOX")), CW("CW", listOf("CW")),
    PBS("PBS", listOf("PBS")), MNT("MyNetwork", listOf("MYNETWORK", "MYNETWORKTV", "MNT", "MY TV", "MYTV")),
    TEL("Telemundo", listOf("TELEMUNDO")), UNI("Univision", listOf("UNIVISION")),
    /** Independent stations: matched by call sign only (no network word). */
    IND("Independent", emptyList());
}

data class Station(val call: String, val net: LocalNet, val verify: Boolean = false)

data class Market(
    /** Nielsen DMA code (501 = New York); stable across years, used as the saved key. */
    val code: Int,
    /** Nielsen rank of the current season (changes yearly; display / sorting only). */
    val rank: Int,
    val name: String,
    /** Every state with at least one county in the market (a market is listed under each of them). */
    val states: List<String>,
    /** State of the principal city. */
    val home: String,
    val cities: List<String>,
    /** City names that also exist elsewhere: only count together with the state code or a call sign. */
    val ambiguousCities: List<String>,
    val stations: List<Station>,
    val verify: Boolean = false,
    /** Full names of [states] ("Ohio"): an ambiguous city counts with either the code or the name. */
    val stateNames: List<String> = emptyList(),
)

data class UsState(val code: String, val name: String)

class Catalog(val version: Int, val updated: String, states: Map<String, String>, val markets: List<Market>) {
    /** States (and DC) A–Z by name, only those with at least one market. */
    val states: List<UsState> = states.entries.map { UsState(it.key, it.value) }
        .filter { s -> markets.any { s.code in it.states } }.sortedBy { it.name.lowercase() }
    private val byCode = markets.associateBy { it.code }
    private val byState: Map<String, List<Market>> = this.states.associate { s ->
        s.code to markets.filter { s.code in it.states }.sortedWith(compareBy({ it.name.lowercase() }, { it.rank }))
    }
    fun market(code: Int?): Market? = code?.let { byCode[it] }
    fun state(code: String?): UsState? = states.firstOrNull { it.code == code }
    /** Every market covering [state], A–Z. */
    fun marketsIn(state: String?): List<Market> = byState[state].orEmpty()
}

class CatalogException(msg: String) : IllegalArgumentException(msg)

object DmaCatalog {
    const val SCHEMA = "ipvio.locals.dmas/1"
    const val ASSET = "locals/dmas.json"
    const val OVERRIDE_FILE = "locals/dmas.override.json"

    @Volatile private var cached: Catalog? = null
    /** Message of the last rejected override (null = none or none present). */
    @Volatile var overrideError: String? = null
        private set

    fun get(c: Context): Catalog = cached ?: synchronized(this) {
        cached ?: load(c).also { cached = it }
    }

    /** For tests and hosts that bring their own data. */
    fun set(cat: Catalog?) { cached = cat }

    private fun load(c: Context): Catalog {
        val f = File(c.applicationContext.filesDir, OVERRIDE_FILE)
        overrideError = null
        if (f.isFile) {
            try { return parse(f.readText()) } catch (e: Exception) { overrideError = e.message ?: e.javaClass.simpleName }
        }
        return parse(c.applicationContext.assets.open(ASSET).bufferedReader().use { it.readText() })
    }

    /** Parses and validates; throws [CatalogException] with a readable reason. Unknown station networks are skipped. */
    fun parse(json: String): Catalog {
        val o = try { JSONObject(json) } catch (e: Exception) { throw CatalogException("not JSON: " + e.message) }
        val schema = o.optString("schema")
        if (schema != SCHEMA) throw CatalogException("schema must be $SCHEMA, is '$schema'")
        val version = o.optInt("version", 0)
        if (version < 1) throw CatalogException("version missing")
        val st = o.optJSONObject("states") ?: throw CatalogException("states missing")
        val states = LinkedHashMap<String, String>()
        st.keys().forEach { k -> if (!Regex("[A-Z]{2}").matches(k)) throw CatalogException("bad state code $k"); states[k] = st.getString(k) }
        val arr = o.optJSONArray("markets") ?: throw CatalogException("markets missing")
        if (arr.length() == 0) throw CatalogException("no markets")
        val out = ArrayList<Market>(arr.length())
        val codes = HashSet<Int>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val code = m.optInt("code", -1)
            val name = m.optString("name").trim()
            if (code <= 0) throw CatalogException("market #$i: code missing")
            if (!codes.add(code)) throw CatalogException("market $code: duplicate code")
            if (name.isEmpty()) throw CatalogException("market $code: name missing")
            val ms = strings(m.optJSONArray("states"))
            if (ms.isEmpty()) throw CatalogException("market $code: states missing")
            ms.firstOrNull { it !in states }?.let { throw CatalogException("market $code: unknown state $it") }
            val home = m.optString("home").ifBlank { ms.first() }
            if (home !in ms) throw CatalogException("market $code: home $home not in states")
            val sts = ArrayList<Station>()
            m.optJSONArray("stations")?.let { a ->
                for (j in 0 until a.length()) {
                    val s = a.getJSONObject(j)
                    val call = s.optString("call").trim().uppercase()
                    val net = runCatching { LocalNet.valueOf(s.optString("net").uppercase()) }.getOrNull() ?: continue
                    if (!Regex("[KW][A-Z0-9]{2,4}").matches(call)) throw CatalogException("market $code: bad call sign '$call'")
                    sts.add(Station(call, net, s.optBoolean("verify", false)))
                }
            }
            out.add(Market(code, m.optInt("rank", 0), name, ms, home, strings(m.optJSONArray("cities")), strings(m.optJSONArray("ambiguousCities")), sts, m.optBoolean("verify", false), ms.map { states.getValue(it) }))
        }
        return Catalog(version, o.optString("updated"), states, out)
    }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getString(it).trim() }.filter { it.isNotEmpty() }
}
