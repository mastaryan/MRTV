package com.ipvio.stream

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ipvio.stream.locals.*
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusable

object ZapContext {
    var list: List<Item> = emptyList()
    /** Category from which playback was started (e.g. own channel list "L:3") – the player shows it when switching channels. */
    var key: String? = null
}

/** Remembers the last chosen category / list in Live TV (also across a restart). */
object LiveMemory {
    fun sel(c: android.content.Context): String? = Profiles.sp(c, "ipvio_live").getString("sel", null)
    fun setSel(c: android.content.Context, k: String) { Profiles.sp(c, "ipvio_live").edit().putString("sel", k).apply() }
}

/** Eingabe eines Namens (neue Senderliste, Umbenennen). */
private class NameAsk(val title: String, val initial: String, val onSave: (String) -> Unit)

/** Chosen source in Live TV with several playlists (-1 = all), valid for the running session. */
object LiveSource {
    var k by mutableIntStateOf(-1)
}

/** Held Up/Down key in the channel list: time of the last repeat event (plain field, not state, so writing it never recomposes). */
object LiveHold {
    @Volatile var lastRepeat = 0L
    /** True while the key is held (a repeat arrived within the last 300 ms). */
    fun active(): Boolean = android.os.SystemClock.uptimeMillis() - lastRepeat < 300
}

/** Reads the focused channel only here, so a focus change recomposes the backdrop and not the whole Live TV tab. */
@Composable
private fun FocusedBackdrop(focused: State<Item?>, modifier: Modifier) { LiveBackdrop(focused.value, modifier) }

@Composable
private fun FocusedNowPanel(focused: State<Item?>, fallback: Item?) { LiveNowPanel(focused.value ?: fallback) }

@Composable
fun LiveTab(liveAll: List<Item>, groupsAll: Map<String, List<Item>>, favsAll: List<Item>, onOpen: (Item) -> Unit, onPlayNow: (Item) -> Unit = onOpen) {
    val ctx = LocalContext.current
    // Several playlists: switch source with one press (All → Playlist 1 → Playlist 2 → …)
    val srcNames = PlaylistStore.sourceNames
    var plOpen by remember { mutableStateOf(false) }
    var plBack by remember { mutableStateOf(false) }
    val plFocus = remember { FocusRequester() }
    // After closing the selection bring the focus back to the row (otherwise it gets lost)
    LaunchedEffect(plBack) { if (plBack) { plBack = false; runCatching { plFocus.requestFocus() } } }
    val plScope = rememberCoroutineScope()
    val srcK = if (LiveSource.k in srcNames.indices && srcNames.size > 1) LiveSource.k else -1
    val live = remember(liveAll, srcK) { if (srcK < 0) liveAll else liveAll.filter { it.src == srcK } }
    val groups = remember(groupsAll, srcK) {
        if (srcK < 0) groupsAll else LinkedHashMap<String, List<Item>>().also { m ->
            for ((g, l) in groupsAll) { val f = l.filter { it.src == srcK }; if (f.isNotEmpty()) m[g] = f }
        }
    }
    // MR TV 2 (2.0.6): clean categories by country (flags, English labels, icons) and virtual groups such as ESPN,
    // from the category table (LiveCats); replaces the six guessed genres of 1.2
    LiveCats.init(ctx)
    val catVer = LiveCats.version
    val genres = remember(live, catVer) { LiveCats.virtuals(live) }
    val favs = remember(favsAll, srcK) { if (srcK < 0) favsAll else favsAll.filter { it.src == srcK } }
    CatFavs.init(ctx)
    // US local channels by Nielsen market (locals package; off unless turned on in the settings / by the build)
    LocalsFeature.init(ctx)
    val localsOn = LocalsFeature.enabled
    val catalog = if (localsOn) remember { DmaCatalog.get(ctx) } else null
    if (localsOn) LocalsStore.init(ctx)
    val savedMkts = if (catalog != null) LocalsStore.saved.mapNotNull { catalog.market(it) } else emptyList()
    ChannelLists.init(ctx)
    remember { LiveQuality.init(ctx) }
    // Last chosen category / list (round 14): the same one again after Back from the player and after a restart
    var sel by remember {
        val saved = LiveMemory.sel(ctx)?.takeIf { k ->
            (k == FAV_CAT && (favs.isNotEmpty() || savedMkts.isNotEmpty())) || (localsOn && (k == LocalsKeys.PICKER || catalog?.market(LocalsKeys.codeOf(k)) != null)) || k == "ALL" || k == "HOME" || k in groups || (LiveCats.isVirtualKey(k) && genres.containsKey(k)) || ChannelLists.byKey(k) != null || k == "REC"
        }
        mutableStateOf(saved ?: if (favs.isNotEmpty() || savedMkts.isNotEmpty()) FAV_CAT else "ALL")
    }
    HiddenCats.init(ctx)
    // Channel name search ("SEARCH" entry): text is kept while the tab is open
    var query by remember { mutableStateOf("") }
    var showMulti by remember { mutableStateOf(false) }
    if (showMulti) MultiViewScreen(live) { showMulti = false }
    val searchReq = remember { FocusRequester() }
    // First key of the on-screen keyboard shown in the search (right side)
    val kbFirst = remember { FocusRequester() }
    val favCats = CatFavs.list.filter { it in groups && it !in HiddenCats.list }
    val myLists = ChannelLists.lists.toList()
    // Only built when an own channel list needs it (thousands of channels, not needed for opening the tab)
    val byIdLazy = remember(live) { lazy { live.associateBy { it.id } } }
    // Order: favorites, own channel lists, "New channel list", all channels, categories
    // Aufnahmen (Runde 20): eigene Kategorie, sobald es welche gibt
    val recs = remember(Recorder.active.size, sel == "REC") { Recorder.list(ctx).map { Recorder.itemOf(it) } }
    // Country sections: United States first, the rest A–Z, Adults last. A section with one category is a single row with
    // its flag; bigger ones get a header row (flag, country, channel count) that opens or closes them.
    val hiddenNow = HiddenCats.list.toSet()
    val sections = remember(groups, genres, catVer, hiddenNow, favCats) {
        LiveCats.sections(groups.filterKeys { it !in favCats && it !in hiddenNow }, genres)
    }
    var expanded by remember { mutableStateOf(LiveCatsUi.initialExpanded(sections, sel)) }
    val sectionNames = LiveCatsUi.flatten(sections, expanded)
    val names = (if (PlaylistStore.lists.size > 1) listOf("PL") else emptyList()) + listOf("HOME", "GUIDE", "SEARCH", "MULTI", FAV_CAT) + (if (localsOn) listOf(LocalsKeys.PICKER) + savedMkts.map { LocalsKeys.market(it.code) } else emptyList()) + (if (recs.isNotEmpty()) listOf("REC") else emptyList()) + myLists.map { ChannelListLogic.key(it) } + listOf("NEW", "ALL") + favCats + sectionNames + (if (HiddenCats.list.any { it in groups }) listOf("HIDDEN") else emptyList())
    val curList = ChannelLists.byKey(sel)
    // Own list currently being filled (OK on a channel adds it); declared early because bundling depends on it
    var addTo by remember { mutableStateOf<Int?>(null) }
    // Adding channels opens the name search (stays open: add several channels, change the search text in between)
    var addFocusTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(addFocusTick) { if (addFocusTick > 0) { kotlinx.coroutines.delay(300); runCatching { kbFirst.requestFocus() } } }
    // Name search in the background: typing stays smooth, the previous hits stay visible until the new ones are there
    val searchOn = sel == "SEARCH"
    val searchRes by produceState(if (addTo != null && query.isBlank()) live else emptyList(), live, query, addTo, searchOn) {
        if (!searchOn) return@produceState
        value = if (addTo != null && query.isBlank()) live
            else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ChannelSearch.filter(live, query) }
    }
    // US locals: the chosen market's local channels, matched in the background (name, or a "Locals | City" category)
    val market = catalog?.market(LocalsKeys.codeOf(sel))
    var marketLoading by remember { mutableStateOf(false) }
    val marketList by produceState(emptyList<Item>(), market, live) {
        value = emptyList()
        val m = market ?: return@produceState
        marketLoading = true
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { LocalMatcher.channels(m, live, { it.title }, { it.group }) }
        marketLoading = false
    }
    // Favorites with saved markets: favorite channels grouped under their market, the rest below
    val favGroups = remember(favs, savedMkts) { if (savedMkts.isEmpty()) null else LocalMatcher.groupFavorites(savedMkts, favs) { it.title } }
    // Picker level: null = states, otherwise the state whose markets are listed
    var localsState by remember { mutableStateOf(market?.home) }
    fun openMarket(m: Market) { LocalsStore.setMyMarket(ctx, m.code); sel = LocalsKeys.market(m.code) }
    fun mktLabel(m: Market) = Lang.tr("Lokalsender") + " – " + m.name
    fun savedToast(m: Market, now: Boolean) = Toast.makeText(ctx, Lang.tr(if (now) "Markt zu Favoriten hinzugefügt" else "Markt aus Favoriten entfernt") + ": " + m.name, Toast.LENGTH_SHORT).show()
    val rawList = when {
        sel == FAV_CAT -> favGroups?.flatMap { it.second } ?: favs
        sel == LocalsKeys.PICKER -> emptyList()
        market != null -> marketList
        sel == "ALL" -> live
        sel == "SEARCH" -> searchRes
        sel == "HOME" -> emptyList()
        LiveCats.isVirtualKey(sel) -> genres[sel].orEmpty()
        sel == "REC" -> recs
        curList != null -> ChannelListLogic.resolve(curList, byIdLazy.value)
        else -> groups[sel].orEmpty()
    }
    // Favorites grouped by saved city: list index -> heading ("Locals – City" / "Weitere Favoriten")
    val favHeads: Map<Int, String> = if (sel != FAV_CAT || favGroups == null) emptyMap() else {
        val m = HashMap<Int, String>(); var at = 0
        for ((r, l) in favGroups) { m[at] = if (r != null) mktLabel(r) else Lang.tr("Weitere Favoriten"); at += l.size }
        m
    }
    // Quality variants of one channel as a single row (not in favorites, own lists, recordings or while filling a list)
    val bundleOn = General.bundleLive && sel != FAV_CAT && sel != "REC" && curList == null && addTo == null
    val bundled = remember(rawList, bundleOn) { if (bundleOn) LiveBundleLogic.bundle(rawList) else null }
    val list = bundled?.list ?: rawList
    val variantsOf = bundled?.variants.orEmpty()
    val firstChan = remember { FocusRequester() }
    // Back from the player: the last watched channel gets the focus (not the sidebar)
    val chanState = remember { LazyListState() }
    val restoreReq = remember { FocusRequester() }
    val restoreId = remember { LastFocus.pending }
    LaunchedEffect(Unit) {
        val id = restoreId ?: return@LaunchedEffect
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return@LaunchedEffect
        runCatching { chanState.scrollToItem(maxOf(i - 2, 0)) }
        repeat(15) {
            kotlinx.coroutines.delay(80)
            if (runCatching { restoreReq.requestFocus() }.isSuccess) { LastFocus.pending = null; return@LaunchedEffect }
        }
    }
    var askDelete by remember { mutableStateOf(false) }
    val catState = remember { LazyListState() }
    var ask by remember { mutableStateOf<NameAsk?>(null) }
    SideEffect { ContentFocus.tab = 3 }
    // Selection points to a deleted list: back to "All channels"
    LaunchedEffect(sel, myLists.size) { if (ChannelListLogic.idOfKey(sel) != null && curList == null) sel = "ALL" }

    // Add a channel to an own list: in "All channels"/categories OK adds the channel (instead of starting it)
    val addList = addTo?.let { id -> myLists.firstOrNull { it.id == id } }
    fun finishAdd() { val id = addTo; addTo = null; if (id != null) sel = ChannelListLogic.PREFIX + id }
    androidx.activity.compose.BackHandler(enabled = addList != null) { finishAdd() }

    // Without a window and without a keyboard: the list is created right away with a default name (renaming works any time)
    fun newList(first: String?) {
        val id = ChannelLists.create(ctx, Lang.tr("Senderliste " + (myLists.size + 1)), first)
        sel = ChannelListLogic.PREFIX + id
        if (first == null) Toast.makeText(ctx, Lang.tr("Liste angelegt. Mit „Sender hinzufügen“ füllen."), Toast.LENGTH_SHORT).show()
    }

    // Round 17b: the selected channel determines the background and the big display on the right ("What is on now")
    // Kept as State and read only in FocusedBackdrop / FocusedNowPanel (see there)
    val focusedState = remember { mutableStateOf<Item?>(null) }
    var lastRepeat by remember { mutableStateOf(0L) }
    // 17c: switch only after a short dwell (no loading of logo and program while paging quickly)
    var focusTarget by remember { mutableStateOf<Item?>(null) }
    // snapshotFlow instead of LaunchedEffect(focusTarget): reading the state in composition would recompose the whole tab on every focus move
    // (held Down key = dozens of recompositions per second on a Fire TV stick)
    LaunchedEffect(Unit) {
        snapshotFlow { focusTarget }.collectLatest { t -> if (focusedState.value != null) kotlinx.coroutines.delay(300); focusedState.value = t }
    }
    // Channel list to the top when another category is chosen (not on the first frame: Back from the player restores the position)
    var selSeen by remember { mutableStateOf(sel) }
    LaunchedEffect(sel) { if (sel != selSeen) { selSeen = sel; runCatching { chanState.scrollToItem(0) } } }
    // MR TV 2: narrow screens (phone in portrait, Fold cover screen): categories and channels as two pages instead of side by side
    var showContent by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 700.dp
        // Phones (also the Fold's screens in landscape): no "what is on" panel on the right, the channel names need the width
        val noPanel = narrow || (MobilePrefs.mobile && maxWidth < 1300.dp && sel != "SEARCH")
        androidx.activity.compose.BackHandler(enabled = narrow && showContent) { showContent = false }
        fun opened() { if (narrow) showContent = true }
        // US locals: Back goes up one level (market → the state's markets → state list), then as usual
        // Remote: the focused row disappears when the level changes, so the focus is parked on an invisible target until the
        // new level's first row (or the Back button) can take it; otherwise it would fall back to the category list.
        var localsFocus by remember { mutableIntStateOf(0) }
        var parking by remember { mutableStateOf(false) }
        var parkFocused by remember { mutableStateOf(false) }
        val parkReq = remember { FocusRequester() }
        val localsBackReq = remember { FocusRequester() }
        val inputMgr = androidx.compose.ui.platform.LocalInputModeManager.current
        fun park() {
            if (inputMgr.inputMode == androidx.compose.ui.input.InputMode.Touch) return
            parking = true; runCatching { parkReq.requestFocus() }; localsFocus++
        }
        LaunchedEffect(localsFocus) {
            if (localsFocus == 0) return@LaunchedEffect
            // requestFocus() does not fail on a target that is not laid out yet, so success = the parking spot lost the focus
            repeat(200) {
                kotlinx.coroutines.delay(50)
                if (!parkFocused) { parking = false; return@LaunchedEffect }
                // a market's channels are still being matched (big playlists: a few seconds): wait so its first channel gets
                // the focus; a market without matches gets the Back button
                if (catalog?.market(LocalsKeys.codeOf(sel)) != null && marketList.isEmpty() && ((marketLoading && it < 199) || it < 4)) return@repeat
                if (runCatching { firstChan.requestFocus() }.isFailure || parkFocused) runCatching { localsBackReq.requestFocus() }
            }
            if (parkFocused) runCatching { ContentFocus.req.requestFocus() }
            parking = false
        }
        Box(
            Modifier.size(1.dp)
                .then(Modifier.focusProperties { canFocus = parking })
                .focusRequester(parkReq).onFocusChanged { parkFocused = it.isFocused }.focusable()
        )
        androidx.activity.compose.BackHandler(enabled = market != null) { park(); localsState = localsState ?: market?.home; sel = LocalsKeys.PICKER }
        androidx.activity.compose.BackHandler(enabled = sel == LocalsKeys.PICKER && localsState != null) { park(); localsState = null }
        FocusedBackdrop(focusedState, Modifier.fillMaxSize())
        Row(Modifier.fillMaxSize().padding(top = 20.dp)) {
            if (!narrow || !showContent) LazyColumn((if (narrow) Modifier.fillMaxWidth() else Modifier.width(270.dp)).fillMaxHeight().padding(start = 24.dp, end = 8.dp).testTag("live-cats"), state = catState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(names) { _, n ->
                    val ml = ChannelLists.byKey(n)
                    if (n == "GUIDE") {
                        // TV guide as in the design: starts the last watched (otherwise first) channel of the selection and opens the guide
                        CatRow("TV-Guide", -1, false, Modifier, onFocus = {}, onRight = { runCatching { firstChan.requestFocus() } }, onFav = {}, icon = AIcon.Grid) {
                            val src = list.ifEmpty { live }
                            val ch = History.ids.firstNotNullOfOrNull { id -> src.firstOrNull { it.id == id } } ?: src.firstOrNull()
                            if (ch != null) { ZapContext.list = src; ZapContext.key = sel; AppNav.openGuide = true; onOpen(ch) }
                        }
                        return@itemsIndexed
                    }
                    if (n == "MULTI") {
                        CatRow(Lang.tr("Mehrfachansicht"), -1, false, Modifier, onFocus = {}, onRight = { runCatching { firstChan.requestFocus() } }, onFav = {}, icon = AIcon.Layers, raw = true) { showMulti = true }
                        return@itemsIndexed
                    }
                    if (n == "SEARCH") {
                        CatRow(
                            Lang.tr("Sender suchen") + if (query.isNotBlank()) ": $query" else "", -1, sel == "SEARCH",
                            if (sel == "SEARCH") Modifier.focusRequester(ContentFocus.req) else Modifier,
                            onFocus = { sel = "SEARCH" },
                            onRight = { runCatching { kbFirst.requestFocus() } },
                            onFav = {}, icon = AIcon.Edit, raw = true
                        ) { opened(); runCatching { kbFirst.requestFocus() } }
                        return@itemsIndexed
                    }
                    if (n == "PL") {
                        // Choose playlist (round 22): "All" (with several shown), filter one of the shown sources
                        // or activate and load another playlist – without going into the settings.
                        val pls = PlaylistStore.lists.toList()
                        val multiView = srcNames.size > 1
                        val opts = (if (multiView) listOf(Lang.tr("Alle Playlists")) else emptyList()) + pls.map { it.name }
                        val off = if (multiView) 1 else 0
                        val curIdx = if (multiView && srcK < 0) 0 else {
                            val si = if (multiView) srcK else 0
                            val pi = pls.indices.firstOrNull { PlaylistStore.shownIndex(it) == si } ?: PlaylistStore.active
                            pi + off
                        }
                        val lbl = Lang.tr("Playlist") + ": " + (opts.getOrNull(curIdx) ?: "")
                        Box {
                            CatRow(lbl, -1, false, Modifier.focusRequester(plFocus), onFocus = {}, onRight = { runCatching { firstChan.requestFocus() } }, onFav = {}, icon = AIcon.Download, raw = true) { plOpen = true }
                            if (plOpen) OptionPopup(opts, curIdx, { plOpen = false; plBack = true }) { pick ->
                                plOpen = false; plBack = true
                                if (multiView && pick == 0) LiveSource.k = -1
                                else {
                                    val pi = pick - off
                                    val si = PlaylistStore.shownIndex(pi)
                                    if (si >= 0) { if (multiView) LiveSource.k = si }
                                    else {
                                        LiveSource.k = -1
                                        plScope.launch { PlaylistStore.switchTo(ctx, pi) }
                                    }
                                }
                                // The chosen category may not exist in the other playlist: then "All channels"
                                sel = "ALL"
                            }
                        }
                        return@itemsIndexed
                    }
                    if (n == "SRC") {
                        val lbl = Lang.tr("Quelle") + ": " + (if (srcK < 0) Lang.tr("Alle") else srcNames[srcK])
                        CatRow(lbl, -1, false, Modifier, onFocus = {}, onRight = { runCatching { firstChan.requestFocus() } }, onFav = {}, icon = AIcon.Sliders, raw = true) {
                            LiveSource.k = if (srcK + 1 >= srcNames.size) -1 else srcK + 1
                            // The chosen category may not exist in the other source: then "All channels"
                            if (sel != FAV_CAT && sel != "ALL" && ChannelLists.byKey(sel) == null) sel = "ALL"
                        }
                        return@itemsIndexed
                    }
                    if (n == "HIDDEN") {
                        var hidMenu by remember { mutableStateOf(false) }
                        val hid = HiddenCats.list.filter { it in groups }
                        Box {
                            CatRow(Lang.tr("Ausgeblendete") + " (" + hid.size + ")", -1, false, Modifier, onFocus = {}, onRight = {}, onFav = {}, icon = AIcon.Sliders, raw = true) { hidMenu = true }
                            if (hidMenu) OptionPopup(listOf("Alle wieder einblenden") + hid, -1, { hidMenu = false }) { i ->
                                hidMenu = false
                                if (i == 0) HiddenCats.showAll(ctx) else HiddenCats.show(ctx, hid[i - 1])
                            }
                        }
                        return@itemsIndexed
                    }
                    if (n == "NEW") {
                        CatRow("Neue Senderliste", -1, false, Modifier, onFocus = {}, onRight = { runCatching { firstChan.requestFocus() } }, onFav = {}, icon = AIcon.Plus) { newList(null) }
                        return@itemsIndexed
                    }
                    if (n == LocalsKeys.PICKER) {
                        // "My Locals": OK / tap opens the saved market (Back goes up to its state and the state list), else the picker
                        val localsSel = sel == n || (market != null && !LocalsStore.isSaved(market.code))
                        val my = catalog?.market(LocalsStore.myMarket)
                        CatRow(
                            Lang.tr("Meine Lokalsender") + (if (my != null) ": " + my.name else ""), -1, localsSel,
                            if (localsSel) Modifier.focusRequester(ContentFocus.req) else Modifier,
                            onFocus = { if (market == null || LocalsStore.isSaved(market.code)) { if (sel != n) localsState = null; sel = n } },
                            onRight = { runCatching { firstChan.requestFocus() } }, onFav = {}, icon = AIcon.Globe, raw = true
                        ) {
                            if (my != null) { localsState = my.home; sel = LocalsKeys.market(my.code) } else sel = n
                            opened(); runCatching { firstChan.requestFocus() }
                        }
                        return@itemsIndexed
                    }
                    val mkt = catalog?.market(LocalsKeys.codeOf(n))
                    if (mkt != null) {
                        var mktMenu by remember { mutableStateOf(false) }
                        Box {
                            CatRow(
                                mktLabel(mkt), -1, n == sel,
                                if (n == sel) Modifier.focusRequester(ContentFocus.req) else Modifier,
                                onFocus = { sel = n; localsState = mkt.home }, onRight = { runCatching { firstChan.requestFocus() } },
                                onFav = { mktMenu = true }, icon = AIcon.Star, raw = true
                            ) { opened(); runCatching { firstChan.requestFocus() } }
                            if (mktMenu) OptionPopup(listOf("Markt aus Favoriten entfernen"), -1, { mktMenu = false }, listOf(AIcon.Close)) {
                                mktMenu = false
                                savedToast(mkt, LocalsStore.toggleSaved(ctx, mkt.code))
                            }
                        }
                        return@itemsIndexed
                    }
                    if (LiveCats.isHeaderKey(n)) {
                        val sec = sections.firstOrNull { it.key == n }
                        if (sec != null) {
                            val open = sec.country.id in expanded
                            CatRow(sec.country.name, sec.total, false, Modifier.testTag("cat-head-" + sec.country.id), onFocus = {}, onRight = { runCatching { firstChan.requestFocus() } }, onFav = {},
                                raw = true, lead = { CountryFlag(sec.country) }, caret = if (open) AIcon.Up else AIcon.Down, heading = true) {
                                expanded = if (open) expanded - sec.country.id else expanded + sec.country.id
                            }
                        }
                        return@itemsIndexed
                    }
                    val ci = if (groups.containsKey(n) || LiveCats.isVirtualKey(n)) LiveCatsUi.rowOf(sections, n) else null
                    val label = when {
                        ci != null -> ci.second
                        n == FAV_CAT -> "Favoriten"
                        n == "HOME" -> "Start"
                        n == "ALL" -> "Alle Sender"
                        n == "REC" -> "Aufnahmen"
                        ml != null -> ml.name
                        groups.containsKey(n) -> LiveCats.title(n)
                        else -> n
                    }
                    val icon = when {
                        n == FAV_CAT || n in CatFavs.list -> AIcon.Star
                        n == "REC" -> AIcon.Download
                        n == "HOME" -> AIcon.Live
                        ml != null -> AIcon.Lists
                        else -> null
                    }
                    val count = when {
                        n == FAV_CAT -> favs.size
                        n == "ALL" -> live.size
                        n == "HOME" -> 0
                        n == "REC" -> recs.size
                        LiveCats.isVirtualKey(n) -> genres[n]?.size ?: 0
                        ml != null -> ChannelListLogic.resolve(ml, byIdLazy.value).size
                        else -> groups[n]?.size ?: 0
                    }
                    var listMenu by remember { mutableStateOf(false) }
                    var catMenu by remember { mutableStateOf(false) }
                    val plainCat = ml == null && n != FAV_CAT && n != "ALL" && n != "REC" && n != "HOME" && !LiveCats.isVirtualKey(n) && groups.containsKey(n)
                    Box {
                        CatRow(
                            label, count, n == sel,
                            if (n == sel) Modifier.focusRequester(ContentFocus.req) else Modifier,
                            onFocus = { sel = n }, onRight = { runCatching { firstChan.requestFocus() } },
                            onFav = { if (ml != null) listMenu = true else if (plainCat) catMenu = true else if (n != FAV_CAT && n != "ALL" && n != "REC" && !LiveCats.isVirtualKey(n)) CatFavs.toggle(ctx, n) },
                            icon = if (n in CatFavs.list) AIcon.Star else if (ci != null) null else icon, raw = ml != null || ci != null || groups.containsKey(n),
                            lead = when {
                                n in CatFavs.list -> null
                                ci != null && ci.third != null -> { { CountryFlag(ci.third!!) } }
                                ci != null -> { { CatTopicIcon(ci.first) } }
                                else -> null
                            },
                            indent = ci != null && ci.third == null && LiveCatsUi.inSection(sections, n)
                        ) { opened(); runCatching { firstChan.requestFocus() } }
                        // Menu of a provider category: favorite on/off, hide
                        if (catMenu && plainCat) OptionPopup(listOf(if (n in CatFavs.list) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen", "Kategorie ausblenden"), -1, { catMenu = false }) { i ->
                            catMenu = false
                            if (i == 0) CatFavs.toggle(ctx, n)
                            else { if (sel == n) sel = "ALL"; HiddenCats.hide(ctx, n) }
                        }
                        // Menu of an own list: rename, delete
                        if (listMenu && ml != null) OptionPopup(listOf("Umbenennen", "Liste löschen"), -1, { listMenu = false }) { i ->
                            listMenu = false
                            if (i == 0) ask = NameAsk("Liste umbenennen", ml.name) { ChannelLists.rename(ctx, ml.id, it) }
                            else { ChannelLists.delete(ctx, ml.id); sel = "ALL" }
                        }
                    }
                }
            }
            if (!narrow || showContent) NarrowPage(narrow, onBack = { showContent = false }) {
            if (sel == "HOME") Box(Modifier.weight(2f).fillMaxHeight().padding(end = 24.dp)) {
                LiveHome(live, genres, favs, firstChan, onOpen = { ch, l -> ZapContext.list = l; ZapContext.key = "ALL"; onOpen(ch) })
            } else {
            Column(Modifier.weight(0.9f).fillMaxHeight().padding(end = 24.dp)) {
                // Own list: rename and delete clearly visible above the channels (before only via long OK on the list)
                if (curList != null) Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AppIcon(AIcon.Lists, Accent1, size = 22.dp)
                    Text(raw = true, text = curList.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    PillButton("Sender hinzufügen", if (list.isEmpty()) Modifier.focusRequester(firstChan) else Modifier, icon = AIcon.Plus) { addTo = curList.id; query = ""; sel = "SEARCH"; addFocusTick++ }
                    PillButton("Umbenennen", icon = AIcon.Edit) { ask = NameAsk("Liste umbenennen", curList.name) { ChannelLists.rename(ctx, curList.id, it) } }
                    Box {
                        PillButton("Liste löschen", icon = AIcon.Close) { askDelete = true }
                        if (askDelete) OptionPopup(listOf("Löschen", "Abbrechen"), -1, { askDelete = false }, listOf(AIcon.Close, AIcon.Left)) { i ->
                            askDelete = false
                            if (i == 0) { ChannelLists.delete(ctx, curList.id); sel = "ALL"; LiveMemory.setSel(ctx, "ALL") }
                        }
                    }
                }
                if (addList != null) Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AppIcon(AIcon.Plus, Accent1, size = 22.dp)
                    Text(raw = true, text = Lang.tr("Zu „") + addList.name + Lang.tr("“ hinzufügen: OK auf einem Sender fügt ihn hinzu bzw. entfernt ihn wieder"),
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    PillButton("Fertig", light = true) { finishAdd() }
                }
                // A market (US locals): Back up to the state's markets, name, save the market to Favorites
                if (market != null) {
                    val saved = LocalsStore.isSaved(market.code)
                    // Buttons on top, the market below: long names ("Greenville–Spartanburg–Asheville–Anderson") get the full width
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        PillButton("Zurück", Modifier.heightIn(min = touchMin()).testTag("locals-back").focusRequester(localsBackReq), icon = AIcon.Left) { park(); localsState = localsState ?: market.home; sel = LocalsKeys.PICKER }
                        PillButton(if (saved) "Markt gespeichert" else "Markt speichern", Modifier.heightIn(min = touchMin()), light = !saved, icon = if (saved) AIcon.Star else AIcon.StarOutline) {
                            savedToast(market, LocalsStore.toggleSaved(ctx, market.code))
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 12.dp)) {
                        Text(raw = true, text = market.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(raw = true, text = market.states.joinToString(", ") + " · " + Lang.tr("Rang") + " " + market.rank, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (sel == "SEARCH") Field(query, { query = it.take(40) }, "Sendername eingeben", Modifier.padding(bottom = 12.dp), inner = Modifier.focusRequester(searchReq))
                val favCities = if (sel == FAV_CAT) savedMkts else emptyList()
                if (sel == LocalsKeys.PICKER && catalog != null) LocalsPicker(catalog, localsState, { park(); localsState = it }, { park(); openMarket(it) }, firstChan, onSaved = { m, now -> savedToast(m, now) })
                else if (list.isEmpty() && favCities.isEmpty()) EmptyState(
                    if (sel == FAV_CAT) AIcon.Heart else AIcon.Live,
                    if (sel == FAV_CAT) "Noch keine Favoriten" else if (sel == "SEARCH") (if (query.isBlank()) "Sender suchen" else "Keine Treffer") else if (curList != null) "Diese Liste ist noch leer"
                        else if (market != null) (if (marketLoading) "Lokalsender werden gesucht …" else "Keine Lokalsender gefunden") else "Keine Sender",
                    when {
                        market != null -> "Deine Playlist hat für diesen Markt keine Lokalsender (ABC, NBC, CBS, FOX …). Wähle mit „Zurück“ einen anderen Markt."
                        sel == "SEARCH" -> "Tippe rechts mit der Tastatur. Gesucht wird im Sendernamen und in der Kategorie."
                        sel == FAV_CAT -> "Auf einem Sender OK gedrückt halten (am Handy: lange tippen oder den Stern) und „Zu Favoriten“ wählen. Ganze Märkte speicherst du unter „Meine Lokalsender“."
                        curList != null -> "In „Alle Sender“ oder einer Kategorie auf einem Sender OK gedrückt halten und „Zu …“ wählen."
                        else -> "In dieser Kategorie gibt es keine Sender. Links eine andere Kategorie wählen."
                    }
                ) else LazyColumn(
                    state = chanState,
                    // Held Down/Up key: at most ~9 steps per second, so a weak box is not flooded with scroll animations and rebinds
                    modifier = Modifier.testTag("live-channels").onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && (e.key == Key.DirectionDown || e.key == Key.DirectionUp) && e.nativeKeyEvent.repeatCount > 0) {
                            val now = android.os.SystemClock.uptimeMillis()
                            LiveHold.lastRepeat = now
                            if (now - lastRepeat < 110) true else { lastRepeat = now; false }
                        } else false
                    },
                    verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)
                ) {
                    // Favorites: saved cities first (open = all its local channels), then favorite channels grouped by city
                    if (favCities.isNotEmpty()) {
                        item(key = "cities-h", contentType = "h") { SectionHead("Gespeicherte Märkte") }
                        itemsIndexed(favCities, key = { _, r -> "mkt:" + r.code }, contentType = { _, _ -> "city" }) { ci, r ->
                            var cm by remember { mutableStateOf(false) }
                            Box {
                                CatRow(
                                    mktLabel(r), -1, false,
                                    (if (ci == 0) Modifier.focusRequester(firstChan) else Modifier).clip(RoundedCornerShape(DS.RadiusS)).background(Color(0x0FFFFFFF)),
                                    onFocus = {}, onRight = {}, onFav = { cm = true }, icon = AIcon.Star, raw = true
                                ) { localsState = r.home; sel = LocalsKeys.market(r.code) }
                                if (cm) OptionPopup(listOf("Öffnen", "Markt aus Favoriten entfernen"), -1, { cm = false }, listOf(AIcon.Right, AIcon.Close)) { i ->
                                    cm = false
                                    if (i == 0) { localsState = r.home; sel = LocalsKeys.market(r.code) } else savedToast(r, LocalsStore.toggleSaved(ctx, r.code))
                                }
                            }
                        }
                    }
                    // In own lists the focus moves along with the channel when moving it (key = channel)
                    itemsIndexed(list, key = if (curList != null) { ix, ch -> ch.id + "#" + ix } else null, contentType = { _, _ -> "ch" }) { i, ch ->
                      Column {
                        favHeads[i]?.let { SectionHead(it, raw = true) }
                        ChannelLine(
                            ch, (if (i == 0 && favCities.isEmpty()) Modifier.focusRequester(firstChan) else Modifier)
                                .then(if (ch.id == restoreId) Modifier.focusRequester(restoreReq) else Modifier), curList,
                            onNewList = { newList(ch.id) },
                            onFocus = { focusTarget = ch },
                            variants = variantsOf[ch.id].orEmpty(),
                            onVariant = { v -> ZapContext.list = list; ZapContext.key = sel; LiveMemory.setSel(ctx, sel); onOpen(v) }
                        ) {
                            if (addList != null) {
                                val has = ch.id in addList.ids
                                if (has) ChannelLists.remove(ctx, addList.id, ch.id) else ChannelLists.add(ctx, addList.id, ch.id)
                                Toast.makeText(ctx, (if (has) Lang.tr("Entfernt: ") else Lang.tr("Hinzugefügt: ")) + ch.title, Toast.LENGTH_SHORT).show()
                            } else if (sel == "REC") onPlayNow(ch)   // Aufnahme: wie ein Film abspielen (spulbar)
                            else { ZapContext.list = list; ZapContext.key = sel; LiveMemory.setSel(ctx, sel); onOpen(ch) }
                        }
                      }
                    }
                }
            }
            // Right: what is on the selected channel (not on narrow screens: the channels need the width)
            if (!noPanel) Box(Modifier.weight(1.1f).fillMaxHeight().padding(start = dpx(30), end = dpx(60), top = dpx(150))) {
                if (sel == "SEARCH") Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { FocusedNowPanel(focusedState, list.firstOrNull()) }
                    Spacer(Modifier.height(10.dp))
                    SearchKeyboard(
                        Modifier.fillMaxWidth().padding(bottom = dpx(24)),
                        onKey = { query = (query + it).take(40) },
                        onSpace = { if (query.isNotEmpty() && !query.endsWith(" ")) query += " " },
                        onDelete = { query = query.dropLast(1) },
                        onClear = { query = "" },
                        onPhone = null, onVoice = null,
                        firstKey = kbFirst, onFocusChange = {}, onRight = { true }
                    )
                } else FocusedNowPanel(focusedState, list.firstOrNull())
            }
                    }
            }
}
        ask?.let { a -> NameDialog(a.title, a.initial, onDismiss = { ask = null }) { a.onSave(it); ask = null } }
    }
}

/** Small dialog: enter a name (on-screen keyboard), save or cancel. */
@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val fr = remember { FocusRequester() }
    androidx.activity.compose.BackHandler { onDismiss() }
    // Focus on "Save", not in the text field: otherwise the on-screen keyboard opens immediately and "Back" first closes it, only then the window
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(100); runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(520.dp).clip(RoundedCornerShape(DS.RadiusL)).background(Surf).border(1.dp, DS.Overlay3, RoundedCornerShape(DS.RadiusL)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Field(text, { text = it.take(ChannelListLogic.MAX_NAME) }, "Name", onDone = { onSave(text) })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Speichern", Modifier.focusRequester(fr), light = true) { onSave(text) }
                PillButton("Abbrechen") { onDismiss() }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CatRow(
    label: String, count: Int, selected: Boolean, modifier: Modifier,
    onFocus: () -> Unit, onRight: () -> Unit, onFav: () -> Unit, icon: AIcon? = null, raw: Boolean = false,
    lead: (@Composable () -> Unit)? = null, caret: AIcon? = null, heading: Boolean = false, indent: Boolean = false, onClick: () -> Unit
) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusS)
    Row(
        modifier.fillMaxWidth().heightIn(min = touchMin()).clip(shape)
            .background(if (f) Color(0x33FFFFFF) else if (selected) Accent1.copy(alpha = 0.16f) else Color.Transparent)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && e.nativeKeyEvent.repeatCount == 0) { onRight(); true } else false
            }
            .favKeys { onFav() }
            // MR TV 2: a tap selects the row too (touch does not move focus, so onFocus alone never fired on a phone);
            // long-press = the same as Menu / long OK. D-pad: OK on the focused row behaves as before.
            .combinedClickable(onClick = { onFocus(); onClick() }, onLongClick = touchOnly(onFav))
            .padding(start = if (indent) 26.dp else 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (lead != null) lead()
            else if (icon != null) AppIcon(icon, if (icon == AIcon.Star) DS.Gold else Color.White, size = 17.dp)
            Text(label, color = Color.White, fontSize = if (heading) 17.sp else 16.sp, fontWeight = if (selected || heading) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, raw = raw)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (count >= 0) Text("$count", color = Muted, fontSize = 13.sp)
            if (caret != null) AppIcon(caret, Muted, size = 14.dp)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChannelLine(item: Item, modifier: Modifier, inList: ChList?, onNewList: () -> Unit, onFocus: () -> Unit = {}, variants: List<Item> = emptyList(), onVariant: (Item) -> Unit = {}, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Box {
    if (menu) ChannelMenu(item, inList, onNewList, variants, onVariant) { menu = false }
    val shape = RoundedCornerShape(DS.RadiusM)
    // Wait briefly before loading the guide data: rows that only fly past while scrolling are cancelled and cost nothing
    // While Up/Down is held no guide data is loaded: the row would grow by two lines and the list would re-measure while it scrolls
    val epg by produceState(emptyList<EpgEntry>(), item.id) {
        kotlinx.coroutines.delay(150)
        while (LiveHold.active()) kotlinx.coroutines.delay(120)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Epg.now(item) }   // off the main thread: the guide lookup must not block scrolling
    }
    val nowE = Epg.current(epg)
    val nextE = epg.firstOrNull { nowE != null && it.start >= nowE.end }
    // Round 17b: the selected channel floats forward as light glass with a shadow (instead of a frame)
    val sc by androidx.compose.animation.core.animateFloatAsState(if (f) focusScale(1.03f) else 1f, label = "chl")
    Row(
        modifier.fillMaxWidth().height(100.dp)   // fixed height: rows no longer grow when guide data arrives, so the list never re-measures while scrolling
            .graphicsLayer { scaleX = sc; scaleY = sc; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
            .clip(shape)
            .background(if (f) Color(0x29FFFFFF) else Color(0x0AFFFFFF))
            .border(1.dp, if (f) Color(0x40FFFFFF) else Color.Transparent, shape)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            .menuKeys(onMenu = { menu = true }, onLong = { menu = true })
            // MR TV 2: long-press opens the channel menu on touch (same as Menu / long OK)
            .combinedClickable(onClick = onClick, onLongClick = touchOnly { menu = true })
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(DS.RadiusS)).background(DS.Overlay2), contentAlignment = Alignment.Center) {
            if (item.logo != null) AsyncImage(item.logo, null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit)
            else Text(raw = true, text = item.title.take(2).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(raw = true, text = item.title, color = Tok.TextHi, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                val lq = remember(item.id) { LiveQuality.get(item.id) ?: item.quality }
                if (lq != null) Box(Modifier.clip(RoundedCornerShape(DS.RadiusXs)).background(Color(0x26FFFFFF)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                    Text(raw = true, text = lq, color = Accent2, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                // Bundled channel: show how many quality variants are behind this row
                if (variants.size > 1) Box(Modifier.clip(RoundedCornerShape(DS.RadiusXs)).background(Color(0x26FFFFFF)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                    Text(raw = true, text = "+" + (variants.size - 1), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            if (nowE != null) {
                val frac = ((System.currentTimeMillis() / 1000 - nowE.start).toFloat() / (nowE.end - nowE.start)).coerceIn(0f, 1f)
                Text(raw = true, text = "${hhmm(nowE.start)}–${hhmm(nowE.end)}  ${nowE.title}", color = Accent2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(DS.Overlay3)) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Accent2))
                }
                if (nextE != null) Text(raw = true, text = "${hhmm(nextE.start)}  ${nextE.title}", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Text(raw = true, text = if (item.kind == Kind.LIVE) LiveCats.title(item.group) else item.group, color = Muted, fontSize = 13.sp, maxLines = 1)
        }
        if (Catchup.version >= 0 && Catchup.has(item)) AppIcon(AIcon.Catchup, Accent2, size = 20.dp)
        if (DeadLinks.isDead(item.url)) AppIcon(AIcon.Warning, DS.Gold, size = 20.dp)
        if (MobilePrefs.mobile) {
            // Touch: a visible star (48 dp target) adds / removes the channel from Favorites. Not focusable: D-pad unchanged.
            val ctx = LocalContext.current
            val fav = Library.isFav(item.id)
            Box(
                Modifier.size(maxOf(touchMin(), 44.dp)).clip(RoundedCornerShape(50))
                    .pointerInput(item.id) { detectTapGestures(onTap = {
                        Library.toggleFav(ctx, item.id)
                        Toast.makeText(ctx, Lang.tr(if (Library.isFav(item.id)) "Zu Favoriten hinzugefügt" else "Aus Favoriten entfernt") + ": " + item.title, Toast.LENGTH_SHORT).show()
                    }) },
                contentAlignment = Alignment.Center
            ) { AppIcon(if (fav) AIcon.Star else AIcon.StarOutline, if (fav) DS.Gold else Muted, size = 24.dp) }
        } else if (Library.isFav(item.id)) AppIcon(AIcon.Star, DS.Gold, size = 20.dp)
    }
    }
}

/**
 * Menu of a channel (menu key or hold OK): favorite, own lists (add/remove),
 * move within the list, new list. [onNewList] = null hides "New channel list" (player).
 */
@Composable
fun ChannelMenu(item: Item, inList: ChList?, onNewList: (() -> Unit)?, variants: List<Item> = emptyList(), onVariant: (Item) -> Unit = {}, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val acts = ArrayList<Triple<String, AIcon?, () -> Unit>>()
    // Bundled quality variants: pick one to play (best first)
    if (variants.size > 1) for (v in variants) acts.add(Triple(Lang.tr("Qualität") + ": " + (v.quality ?: v.title), AIcon.Play) { onVariant(v) })
    acts.add(Triple(if (Library.isFav(item.id)) "Aus Favoriten entfernen" else "Zu Favoriten", AIcon.Star) { Library.toggleFav(ctx, item.id) })
    if (inList != null) {
        acts.add(Triple("Nach oben", AIcon.Up) { ChannelLists.move(ctx, inList.id, item.id, -1) })
        acts.add(Triple("Nach unten", AIcon.Down) { ChannelLists.move(ctx, inList.id, item.id, 1) })
        acts.add(Triple("Aus „${inList.name}“ entfernen", AIcon.Close) { ChannelLists.remove(ctx, inList.id, item.id) })
    }
    for (l in ChannelLists.lists.toList()) {
        if (l.id == inList?.id) continue
        if (item.id in l.ids) acts.add(Triple("In „${l.name}“ – entfernen", AIcon.Check) { ChannelLists.remove(ctx, l.id, item.id) })
        else acts.add(Triple("Zu „${l.name}“", AIcon.Plus) { ChannelLists.add(ctx, l.id, item.id) })
    }
    if (onNewList != null) acts.add(Triple("Neue Senderliste …", AIcon.Plus, onNewList))
    OptionPopup(acts.map { it.first }, -1, onClose, acts.map { it.second }) { i -> onClose(); acts[i].third() }
}

/**
 * Reminder while the app is open (also in the player): "In 5 min: Tagesschau · Das Erste" with Switch/Close.
 * Disappears by itself after a minute.
 */
@Composable
fun ReminderCard(r: Reminder, modifier: Modifier, onClose: () -> Unit, onSwitch: () -> Unit) {
    val fr = remember { FocusRequester() }
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    androidx.activity.compose.BackHandler { onClose() }
    LaunchedEffect(r.key) {
        kotlinx.coroutines.delay(150)
        runCatching { fr.requestFocus() }
        repeat(4) { kotlinx.coroutines.delay(15_000); nowSec = System.currentTimeMillis() / 1000 }
        onClose()
    }
    val shape = RoundedCornerShape(DS.RadiusL)
    Row(
        modifier.widthIn(max = 760.dp).clip(shape).background(Tok.GlassStrong).border(1.dp, Accent2, shape).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AppIcon(AIcon.Bell, Accent2, size = 30.dp)
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(raw = true, text = Reminders.whenText(r, nowSec), color = Accent2, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(raw = true, text = r.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(raw = true, text = r.chTitle, color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PillButton("Umschalten", Modifier.focusRequester(fr), light = true) { onSwitch() }
        PillButton("Schließen") { onClose() }
    }
}


/** Start in the Live TV area: last watched, favorites and a row of channels per genre. */
@Composable
private fun LiveHome(live: List<Item>, genres: Map<String, List<Item>>, favs: List<Item>, firstReq: FocusRequester, onOpen: (Item, List<Item>) -> Unit) {
    val byId = remember(live) { live.associateBy { it.id } }
    val recent = remember(live, History.ids.size) { History.ids.mapNotNull { byId[it] }.take(24) }
    val rows = ArrayList<Pair<String, List<Item>>>()
    if (recent.isNotEmpty()) rows.add("Zuletzt gesehen" to recent)
    if (favs.isNotEmpty()) rows.add("Favoriten" to favs.take(40))
    for ((k, l) in genres) rows.add((LiveCats.virtualOf(k)?.label ?: k) to l.take(40))
    if (rows.isEmpty()) rows.add("Alle Sender" to live.take(40))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(rows, key = { i, r -> "$i|" + r.first }) { ri, r ->
            Column {
                Text(r.first, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, top = 12.dp))
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(r.second, key = { i, c -> c.id + "#" + i }) { i, c ->
                        LiveCard(c, { onOpen(c, r.second) }, Modifier.width(260.dp).then(if (ri == 0 && i == 0) Modifier.focusRequester(firstReq) else Modifier))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHead(text: String, raw: Boolean = false) {
    Text(text, raw = raw, color = Accent2, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 4.dp))
}

/** MR TV 2 narrow screens: one page (categories or content) with a back row above the content. */
@Composable
private fun RowScope.NarrowPage(narrow: Boolean, onBack: () -> Unit, content: @Composable RowScope.() -> Unit) {
    if (!narrow) { content(); return }
    Column(Modifier.weight(1f).fillMaxHeight()) {
        PillButton("Kategorien", Modifier.padding(start = 12.dp, bottom = 10.dp).heightIn(min = touchMin()), icon = AIcon.Left) { onBack() }
        Row(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp)) { content() }
    }
}
