package com.ipvio.stream.locals

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ipvio.stream.AIcon
import com.ipvio.stream.Accent2
import com.ipvio.stream.AppIcon
import com.ipvio.stream.DS
import com.ipvio.stream.Lang
import com.ipvio.stream.Muted
import com.ipvio.stream.Text
import com.ipvio.stream.favKeys

/**
 * "My Locals" picker: states A–Z (time-zone suggestions on top, never auto-picked) → every market covering the
 * state, A–Z. Tap / OK opens; the star, long-press, Menu or long OK saves a market to Favorites.
 * Back is handled by the host (state list ← market list ← channels), see [LocalsNav].
 */
@Composable
fun LocalsPicker(
    catalog: Catalog,
    state: String?,
    onState: (String?) -> Unit,
    onMarket: (Market) -> Unit,
    firstReq: FocusRequester,
    modifier: Modifier = Modifier,
    suggested: List<String> = remember { LocalsSuggest.states() },
    /** After the star / Menu saved (true) or removed (false) a market in Favorites. */
    onSaved: (Market, Boolean) -> Unit = { _, _ -> },
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun onStar(m: Market) { onSaved(m, LocalsStore.toggleSaved(ctx, m.code)) }
    val st = catalog.state(state)
    val ls = remember(state) { LazyListState() }
    val my = catalog.market(LocalsStore.myMarket)
    // A new level: the host moves the focus to [firstReq] (D-pad), see LiveTab
    fun go(s: String?) = onState(s)
    LazyColumn(modifier.fillMaxSize().testTag("locals-picker"), state = ls, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        if (st == null) {
            var first = true
            fun req(): Modifier = if (first) { first = false; Modifier.focusRequester(firstReq) } else Modifier
            if (my != null) {
                item(key = "my-h") { Head(Lang.tr("Mein Markt")) }
                val m0 = req()
                item(key = "my") { PickRow(my.name, sub = stateNames(catalog, my), saved = null, modifier = m0, icon = AIcon.Live) { onMarket(my) } }
            }
            val sug = suggested.mapNotNull { catalog.state(it) }
            if (sug.isNotEmpty()) {
                item(key = "sug-h") { Head(Lang.tr("Vorschlag für deine Zeitzone")) }
                sug.forEach { s -> val m0 = req(); item(key = "sug-" + s.code) { PickRow(s.name, modifier = m0, icon = AIcon.Globe) { go(s.code) } } }
            }
            item(key = "h") { Head(Lang.tr(if (sug.isNotEmpty() || my != null) "Alle Bundesstaaten" else "Bundesstaat wählen")) }
            val m0 = req()
            itemsIndexed(catalog.states, key = { _, s -> s.code }) { i, s -> PickRow(s.name, modifier = if (i == 0) m0 else Modifier) { go(s.code) } }
        } else {
            item(key = "back") { PickRow("‹ " + st.name, modifier = Modifier.focusRequester(firstReq), icon = AIcon.Left) { go(null) } }
            item(key = "h") { Head(Lang.tr("Markt wählen")) }
            items(catalog.marketsIn(st.code), key = { it.code }) { m ->
                val others = m.states.filter { it != st.code }
                PickRow(
                    m.name, sub = if (others.isEmpty()) null else stateNames(catalog, m),
                    saved = LocalsStore.isSaved(m.code), onStar = { onStar(m) }, icon = if (m.code == LocalsStore.myMarket) AIcon.Live else null
                ) { onMarket(m) }
            }
        }
    }
}

private fun stateNames(c: Catalog, m: Market): String = m.states.joinToString(", ")

@Composable
private fun Head(text: String) {
    Text(text, raw = true, color = Accent2, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 4.dp))
}

/** One row: 48 dp or more (touch), focus highlight (D-pad); optional star for saving (touch: tap it; remote: Menu / long OK). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PickRow(label: String, modifier: Modifier = Modifier, sub: String? = null, saved: Boolean? = null, onStar: (() -> Unit)? = null, icon: AIcon? = null, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val im = LocalInputModeManager.current
    val shape = RoundedCornerShape(DS.RadiusS)
    val touch = touch48()
    Row(
        modifier.fillMaxWidth().heightIn(min = maxOf(52.dp, touch)).clip(shape)
            .background(if (f) Color(0x33FFFFFF) else Color(0x0AFFFFFF))
            .onFocusChanged { f = it.isFocused }
            .then(if (onStar != null) Modifier.favKeys { onStar() } else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onStar?.let { s -> { if (im.inputMode == InputMode.Touch) s() } })
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { AppIcon(icon, if (f) Color.White else Accent2, size = 20.dp); Box(Modifier.size(10.dp)) }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(raw = true, text = label, color = Color.White, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(raw = true, text = sub, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (saved != null && onStar != null) Box(
            Modifier.size(maxOf(44.dp, touch)).clip(RoundedCornerShape(50)).testTag("star").pointerInput(label) { detectTapGestures(onTap = { onStar() }) },
            contentAlignment = Alignment.Center
        ) { AppIcon(if (saved) AIcon.Star else AIcon.StarOutline, if (saved) DS.Gold else Muted, size = 24.dp) }
    }
}

/** 48 real dp in the current density (apps that scale their UI down on phones still get finger-sized rows). */
@Composable
private fun touch48(): androidx.compose.ui.unit.Dp {
    val scaled = androidx.compose.ui.platform.LocalDensity.current.density
    val real = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics.density
    return (48f * real / scaled).dp
}
