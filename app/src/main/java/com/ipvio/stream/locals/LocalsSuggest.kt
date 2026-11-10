package com.ipvio.stream.locals

import java.util.TimeZone

/**
 * Suggests likely states from the device time zone (no location permission, no network). Only a hint shown on top of
 * the state list: the user always picks the state and the market. Zones that cover many states suggest the most
 * populous ones first; more precise zone IDs (America/Detroit, America/Indiana/…, America/Boise …) win.
 */
object LocalsSuggest {
    private val EXACT = mapOf(
        "America/Detroit" to listOf("MI"), "America/Menominee" to listOf("MI"),
        "America/Boise" to listOf("ID"), "America/Phoenix" to listOf("AZ"), "Pacific/Honolulu" to listOf("HI"),
        "America/Anchorage" to listOf("AK"), "America/Juneau" to listOf("AK"), "America/Sitka" to listOf("AK"),
        "America/Nome" to listOf("AK"), "America/Yakutat" to listOf("AK"), "America/Metlakatla" to listOf("AK"), "America/Adak" to listOf("AK"),
        "America/Los_Angeles" to listOf("CA", "WA", "OR", "NV"),
        "America/Denver" to listOf("CO", "UT", "NM", "MT", "ID", "WY"),
        "America/Chicago" to listOf("TX", "IL", "MN", "MO", "WI", "AL", "LA", "TN"),
        "America/New_York" to listOf("NY", "FL", "PA", "OH", "GA", "NC", "MI", "NJ", "VA"),
        "US/Eastern" to listOf("NY", "FL", "PA", "OH", "GA", "NC", "MI", "NJ", "VA"), "US/Central" to listOf("TX", "IL", "MN", "MO", "WI", "AL", "LA", "TN"),
        "US/Mountain" to listOf("CO", "UT", "NM", "MT", "ID", "WY"), "US/Pacific" to listOf("CA", "WA", "OR", "NV"),
        "US/Arizona" to listOf("AZ"), "US/Hawaii" to listOf("HI"), "US/Alaska" to listOf("AK"), "US/Michigan" to listOf("MI"),
    )

    fun states(zoneId: String = TimeZone.getDefault().id, max: Int = 4): List<String> {
        val z = zoneId.trim()
        val hit = EXACT[z] ?: when {
            z.startsWith("America/Indiana/") -> listOf("IN")
            z.startsWith("America/Kentucky/") || z == "America/Louisville" -> listOf("KY")
            z.startsWith("America/North_Dakota/") -> listOf("ND")
            else -> emptyList()
        }
        return hit.take(max)
    }
}
