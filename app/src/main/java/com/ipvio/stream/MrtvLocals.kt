package com.ipvio.stream

import com.ipvio.stream.locals.DmaCatalog
import com.ipvio.stream.locals.LocalsFeature
import com.ipvio.stream.locals.LocalsStore

/**
 * MR TV 2: the US locals layer is on by default (MR TV is a US service), and cities saved by MR TV 2 2.0.3's
 * "USA Locals" (Nielsen 2025-26 ranks in "mrtv_cityfavs", same playlist scope) carry over as saved markets.
 */
object MrtvLocals {
    fun setup() {
        LocalsFeature.defaultOn = true
        LocalsStore.legacySaved = { c, scope ->
            val ranks = (Profiles.sp(c, "mrtv_cityfavs").getString("c_$scope", null) ?: "").split('\n', ',').mapNotNull { it.trim().toIntOrNull() }
            val cat = DmaCatalog.get(c)
            ranks.mapNotNull { r -> cat.markets.firstOrNull { it.rank == r }?.code }
        }
    }
}
