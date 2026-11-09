package com.ipvio.stream

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Title information (cover, description, rating, genres …).
 *
 * MR TV 2: everything comes from the provider's own panel (Xtream player_api.php, see [ProviderInfo]).
 * The third-party metadata services of upstream IPVIO were removed completely; [metaId] is always 0.
 */
data class Meta(
    val metaId: Int,
    val isTv: Boolean,
    val poster: String?,
    val backdrop: String?,
    val overview: String?,
    val rating: Double?,
    val year: String?,
    val genres: List<String> = emptyList(),
    val runtime: Int? = null,
    val trailerKey: String? = null,
    val extended: Boolean = false,
    val genreIds: List<Int> = emptyList(),
    val lang: String? = null,
    val votes: Int = 0,
    val countries: List<String> = emptyList(),
    /** Language in which description and genres were loaded */
    val uiLang: String = "de",
    val checked: Boolean = false
) {
    /** Rating for sorting and filtering (0 to 10): the provider's value. */
    fun score(): Double? = rating
}

data class EpMeta(
    val num: Int, val name: String?, val overview: String?, val still: String?,
    val rating: Double?, val runtime: Int?, val air: String?
)

private fun JSONObject.str(k: String): String? =
    if (!has(k) || isNull(k)) null else getString(k).ifBlank { null }

private fun JSONObject.dbl(k: String): Double? =
    if (!has(k) || isNull(k)) null else getDouble(k)

/** Provider-only title information, remembered in memory and on the device. */
object MetaRepo {
    private const val TTL = 30L * 24 * 3600 * 1000

    /** MR TV 2: there is no outside metadata service; kept so shared upstream screens compile unchanged. */
    fun enabled() = false

    /** Info exists for this title directly from the Xtream provider. */
    fun usable(i: Item): Boolean = ProviderLogic.supports(i)

    private val provMem = ConcurrentHashMap<String, Meta>()
    private val provFail = ConcurrentHashMap<String, Long>()

    /** Info from the provider. Remembered on the device. */
    suspend fun providerMeta(ctx: Context, i: Item): Meta? {
        if (i.kind == Kind.LIVE || !ProviderLogic.supports(i)) return null
        val k = "P|" + key(i)
        provMem[k]?.let { return it }
        provFail[k]?.let { if (System.currentTimeMillis() - it < 120_000L) return null }
        load(ctx, k)?.let { provMem[k] = it; return it }
        val m = ProviderInfo.fetch(ctx, i)
        if (m == null) { provFail[k] = System.currentTimeMillis(); return null }
        provMem[k] = m
        save(ctx, k, m)
        return m
    }

    private fun key(i: Item) = i.mk
    /** When memory is tight: empty the cache in working memory (data still lies on the device). */
    fun trim() { provMem.clear() }

    fun cached(i: Item): Meta? = provMem["P|" + key(i)]
    fun missed(i: Item): Boolean = false

    private fun dir(c: Context): File = File(c.filesDir, "meta3").also { it.mkdirs() }

    private suspend fun save(c: Context, k: String, m: Meta) = withContext(Dispatchers.IO) {
        try {
            val j = JSONObject()
            j.put("ts", System.currentTimeMillis())
            j.put("isTv", m.isTv)
            j.put("poster", m.poster); j.put("backdrop", m.backdrop); j.put("overview", m.overview)
            j.put("rating", m.rating); j.put("year", m.year)
            j.put("genres", m.genres.joinToString("|")); j.put("runtime", m.runtime)
            j.put("trailer", m.trailerKey); j.put("ext", m.extended)
            j.put("ui", m.uiLang); j.put("chk", m.checked)
            File(dir(c), k.hashCode().toString()).writeText(j.toString())
        } catch (e: Exception) {
        }
    }

    private suspend fun load(c: Context, k: String): Meta? = withContext(Dispatchers.IO) {
        try {
            val f = File(dir(c), k.hashCode().toString())
            if (!f.exists()) return@withContext null
            val j = JSONObject(f.readText())
            if (System.currentTimeMillis() - j.optLong("ts") > TTL) return@withContext null
            Meta(
                0, j.getBoolean("isTv"), j.str("poster"), j.str("backdrop"), j.str("overview"),
                j.dbl("rating"), j.str("year"),
                (j.str("genres") ?: "").split("|").filter { it.isNotBlank() },
                if (j.has("runtime") && !j.isNull("runtime")) j.getInt("runtime") else null,
                j.str("trailer"), j.optBoolean("ext"),
                uiLang = j.optString("ui", "de"),
                checked = j.optBoolean("chk")
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Short info for cards: whatever the provider already delivered. */
    suspend fun basic(ctx: Context, i: Item): Meta? = cached(i)

    /** Full info for the detail page, from the provider. */
    suspend fun extended(ctx: Context, i: Item): Meta? = providerMeta(ctx, i)
}
