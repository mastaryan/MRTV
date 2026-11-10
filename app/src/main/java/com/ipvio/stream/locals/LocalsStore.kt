package com.ipvio.stream.locals

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ipvio.stream.PlaylistStore
import com.ipvio.stream.Profiles

/** Feature switch (Settings > Live TV > "US local channels"). Off unless the app turns [defaultOn] on. */
object LocalsFeature {
    /** Default when the user never touched the setting; a build/brand can set this before the first [init]. */
    @Volatile var defaultOn = false
    var enabled by mutableStateOf(false)
        private set
    private var loaded = false
    private fun prefs(c: Context) = Profiles.sp(c, LocalsStore.PREFS)
    fun init(c: Context) { if (loaded) return; loaded = true; enabled = prefs(c).getBoolean("on", defaultOn) }
    fun set(c: Context, v: Boolean) { enabled = v; loaded = true; prefs(c).edit().putBoolean("on", v).apply() }
    internal fun resetForTest() { loaded = false; enabled = false }
}

/** Pure encode / decode / toggle of saved market codes (unit-tested). */
object LocalsCodes {
    fun decode(s: String?, valid: (Int) -> Boolean): List<Int> = (s ?: "").split('\n', ',').mapNotNull { it.trim().toIntOrNull() }.filter(valid).distinct()
    fun encode(codes: List<Int>): String = codes.distinct().joinToString("\n")
    /** Saved first in the list (newest first), removed if already saved. */
    fun toggle(codes: List<Int>, code: Int): List<Int> = if (code in codes) codes - code else listOf(code) + codes
}

/**
 * Per playlist (keyed by a hash of the playlist address, nothing secret stored): the user's own market ("My Locals")
 * and further saved markets (shown in Live TV > Favorites). Kept across restarts and playlist reloads.
 */
object LocalsStore {
    const val PREFS = "ipvio_locals"
    var myMarket by mutableStateOf<Int?>(null)
        private set
    var saved by mutableStateOf<List<Int>>(emptyList())
        private set
    private var loadedFor: String? = null
    /** Host hook: markets saved by an older version of the app for this playlist scope, migrated once. */
    @Volatile var legacySaved: ((Context, String) -> List<Int>)? = null
    /** Playlist scope; replaceable for tests. */
    @Volatile var scopeOf: () -> String = { PlaylistStore.lists.getOrNull(PlaylistStore.active)?.url?.trim()?.hashCode()?.toString() ?: "0" }

    private fun prefs(c: Context) = Profiles.sp(c, PREFS)

    fun init(c: Context) {
        val s = scopeOf()
        if (loadedFor == s) return
        loadedFor = s
        val cat = DmaCatalog.get(c)
        val p = prefs(c)
        myMarket = p.getInt("my_$s", -1).takeIf { cat.market(it) != null }
        val raw = p.getString("saved_$s", null)
        saved = if (raw != null) LocalsCodes.decode(raw) { cat.market(it) != null } else {
            val old = legacySaved?.invoke(c, s).orEmpty().filter { cat.market(it) != null }.distinct()
            if (old.isNotEmpty()) p.edit().putString("saved_$s", LocalsCodes.encode(old)).apply()
            old
        }
    }

    fun setMyMarket(c: Context, code: Int?) {
        init(c); myMarket = code
        prefs(c).edit().apply { if (code == null) remove("my_" + scopeOf()) else putInt("my_" + scopeOf(), code) }.apply()
    }

    fun isSaved(code: Int) = code in saved
    /** Saves / removes a market in Favorites; returns true when it is saved now. */
    fun toggleSaved(c: Context, code: Int): Boolean {
        init(c)
        saved = LocalsCodes.toggle(saved, code)
        prefs(c).edit().putString("saved_" + scopeOf(), LocalsCodes.encode(saved)).apply()
        return code in saved
    }

    internal fun resetForTest() { loadedFor = null; myMarket = null; saved = emptyList() }
}

/** Live TV category keys: the picker and one market ("MKT:<DMA code>"). */
object LocalsKeys {
    const val PICKER = "LOCALS"
    const val MARKET = "MKT:"
    fun market(code: Int) = MARKET + code
    fun codeOf(key: String?): Int? = key?.takeIf { it.startsWith(MARKET) }?.removePrefix(MARKET)?.toIntOrNull()
    fun isLocals(key: String?) = key == PICKER || codeOf(key) != null
}
