package com.ipvio.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.TimeZone

/** MR TV 2 (2.0.6): logic of the Sports Hub side rail (Android-free, unit tested). */
object SportRailLogic {
    /** Rail entries: null = "All", then the sports A–Z. */
    fun entries(): List<String?> = listOf<String?>(null) + SportData.SPORTS.map { it.key }

    /** Team sports (Teams tab rail: no "All", no F1/Golf/Tennis/UFC). */
    fun teamSports(): List<String> = SportData.SPORTS.map { it.key }.filter { k -> SportData.LEAGUES.any { it.sport == k && it.kind == SportData.KIND_TEAM } }

    fun label(key: String?): String = if (key == null) "All" else SportData.SPORTS.firstOrNull { it.key == key }?.label ?: key

    fun matches(g: SportData.Game, sport: String?): Boolean = sport == null || SportData.sportOf(g) == sport

    fun filter(games: List<SportData.Game>, sport: String?): List<SportData.Game> = if (sport == null) games else games.filter { matches(it, sport) }

    /** Games per rail entry; key null = all. */
    fun counts(games: List<SportData.Game>): Map<String?, Int> {
        val m = HashMap<String?, Int>()
        for (g in games) { val s = SportData.sportOf(g); m[s] = (m[s] ?: 0) + 1 }
        m[null] = games.size
        return m
    }

    /** Schedule days: today plus the next six, never past days. */
    val DAYS = 0..6

    fun clampDay(d: Int): Int = d.coerceIn(DAYS)

    /** "Today", then "Wed 11/11", "Thu 11/12" … */
    fun dayLabel(offset: Int, tz: TimeZone, nowMs: Long = System.currentTimeMillis()): String {
        if (offset == 0) return "Today"
        val cal = Calendar.getInstance(tz).apply { timeInMillis = nowMs; add(Calendar.DAY_OF_YEAR, offset) }
        return UsDates.format(UsDates.DAY, cal.timeInMillis / 1000, tz)
    }

    /** Heading of the schedule: "Tuesday, 11/10 · All sports" */
    fun dayHeading(offset: Int, sport: String?, tz: TimeZone, nowMs: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance(tz).apply { timeInMillis = nowMs; add(Calendar.DAY_OF_YEAR, offset) }
        return UsDates.format("EEEE, M/d", cal.timeInMillis / 1000, tz) + " · " + (if (sport == null) "All sports" else SportData.sportLabel(sport))
    }
}

/** Narrow screens (phone held upright): the rail becomes a row of chips. */
internal val RAIL_NARROW = 700.dp

/**
 * The side rail plus the page content. Wide screens (TV, landscape): vertical list of sports on the left with game
 * counts ("–" = none). Narrow screens: horizontal chips above the content. No logos, on purpose.
 */
@Composable
internal fun SportRailFrame(
    entries: List<String?>, sel: String?, counts: Map<String?, Int>?, onSel: (String?) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth < RAIL_NARROW) Column(Modifier.fillMaxSize()) {
            Text("SPORT · swipe for more", color = Muted, fontSize = spx(12), fontWeight = FontWeight.SemiBold, raw = true,
                modifier = Modifier.padding(start = DS.PageX, top = dpx(4)))
            LazyRow(Modifier.testTag("sport-chips"), horizontalArrangement = Arrangement.spacedBy(dpx(8)), contentPadding = PaddingValues(horizontal = DS.PageX, vertical = dpx(6))) {
                items(entries, key = { it ?: "all" }) { k ->
                    val n = counts?.get(k) ?: 0
                    FilterPill(SportRailLogic.label(k) + (if (n > 0) " $n" else ""), sel == k, Modifier.trackFocus(), raw = true) { onSel(k) }
                }
            }
            content()
        } else Row(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.padding(start = DS.PageX).width(dpx(180)).fillMaxHeight().padding(end = dpx(10)).testTag("sport-rail"),
                verticalArrangement = Arrangement.spacedBy(dpx(2)), contentPadding = PaddingValues(vertical = dpx(4))
            ) {
                item(key = "head") { Text("SPORT", color = Muted, fontSize = spx(12), fontWeight = FontWeight.SemiBold, raw = true, modifier = Modifier.padding(start = dpx(10), bottom = dpx(4))) }
                items(entries, key = { it ?: "all" }) { k -> RailRow(SportRailLogic.label(k), counts?.get(k) ?: 0, sel == k) { onSel(k) } }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0x14FFFFFF)))
            Column(Modifier.weight(1f).fillMaxHeight(), content = content)
        }
    }
}

@Composable
private fun RailRow(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusS)
    Row(
        Modifier.fillMaxWidth().height(dpx(40)).clip(shape)
            .background(if (f) DS.FocusFill else if (selected) Color.White else Color.Transparent)
            .border(if (selected) 2.dp else 0.dp, if (selected) Accent1 else Color.Transparent, shape)
            .onFocusChanged { f = it.isFocused }
            .trackFocus()
            .clickable(onClick = onClick)
            .padding(horizontal = dpx(10)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val ink = if (f || selected) DS.FocusInk else Color.White
        Text(label, color = ink, fontSize = spx(15), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, raw = true,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = dpx(6)))
        Text(if (count > 0) "$count" else "–", color = if (f || selected) DS.FocusInk else Muted, fontSize = spx(13), raw = true)
    }
}
