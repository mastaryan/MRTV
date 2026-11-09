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
    // Genres (sports, movies, documentary, kids, news, music) – detected by category and channel name
    val genres = remember(live) { LiveGenre.split(live) }
    val favs = remember(favsAll, srcK) { if (srcK < 0) favsAll else favsAll.filter { it.src == srcK } }
    CatFavs.init(ctx)
    CityFavs.init(ctx)
    ChannelLists.init(ctx)
    remember { LiveQuality.init(ctx) }
    // Last chosen category / list (round 14): the same one again after Back from the player and after a restart
    var sel by remember {
        val saved = LiveMemory.sel(ctx)?.takeIf { k ->
            (k == FAV_CAT && (favs.isNotEmpty() || CityFavs.list.isNotEmpty())) || k == UsaLocals.KEY || UsaLocals.isCity(k) || k == "ALL" || k == "HOME" || k in groups || (LiveGenre.isGenreKey(k) && genres.containsKey(k)) || ChannelLists.byKey(k) != null || k == "REC"
        }
        mutableStateOf(saved ?: if (favs.isNotEmpty() || CityFavs.list.isNotEmpty()) FAV_CAT else "ALL")
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
    val names = (if (PlaylistStore.lists.size > 1) listOf("PL") else emptyList()) + listOf("HOME", "GUIDE", "SEARCH", "MULTI", FAV_CAT, UsaLocals.KEY) + CityFavs.list.map { UsaLocals.cityKey(it) } + (if (recs.isNotEmpty()) listOf("REC") else emptyList()) + myLists.map { ChannelListLogic.key(it) } + listOf("NEW", "ALL") + genres.keys + favCats + groups.keys.filter { it !in favCats && it !in HiddenCats.list } + (if (HiddenCats.list.any { it in groups }) listOf("HIDDEN") else emptyList())
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
    // MR TV 2 USA Locals (ported from MR TV 1.3): a city's local stations, matched in the background
    val cityRank = UsaLocals.rankOf(sel)
    var cityLoading by remember { mutableStateOf(false) }
    val cityList by produceState(emptyList<Item>(), cityRank, live) {
        value = emptyList()
        val m = cityRank?.let { LocalMarkets.byKey(it) } ?: return@produceState
        cityLoading = true
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { UsaLocals.channels(m, live, { it.id }, { it.title }) }
        cityLoading = false
    }
    // Favorites with saved cities: favorite channels grouped under their city, the rest below
    val favGroups = remember(favs, CityFavs.list) { if (CityFavs.list.isEmpty()) null else UsaLocals.groupFavorites(CityFavs.list, favs) { it.title } }
    var localsState by remember { mutableStateOf(UsaLocals.stateOf(cityRank ?: -1)) }
    val rawList = when {
        sel == FAV_CAT -> favGroups?.flatMap { it.second } ?: favs
        sel == UsaLocals.KEY -> emptyList()
        cityRank != null -> cityList
        sel == "ALL" -> live
        sel == "SEARCH" -> searchRes
        sel == "HOME" -> emptyList()
        LiveGenre.isGenreKey(sel) -> genres[sel].orEmpty()
        sel == "REC" -> recs
        curList != null -> ChannelListLogic.resolve(curList, byIdLazy.value)
        else -> groups[sel].orEmpty()
    }
    // Favorites grouped by saved city: list index -> heading ("Locals – City" / "Weitere Favoriten")
    val favHeads: Map<Int, String> = if (sel != FAV_CAT || favGroups == null) emptyMap() else {
        val m = HashMap<Int, String>(); var at = 0
        for ((r, l) in favGroups) { m[at] = if (r != null) UsaLocals.cityLabel(r) else Lang.tr("Weitere Favoriten"); at += l.size }
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
                    if (n == UsaLocals.KEY) {
                        val localsSel = sel == n || (cityRank != null && !CityFavs.isSaved(cityRank))
                        CatRow(
                            "US-Lokalsender", -1, localsSel,
                            if (localsSel) Modifier.focusRequester(ContentFocus.req) else Modifier,
                            onFocus = { if (UsaLocals.rankOf(sel) == null || CityFavs.isSaved(UsaLocals.rankOf(sel)!!)) sel = n },
                            onRight = { runCatching { firstChan.requestFocus() } }, onFav = {}, icon = AIcon.Globe
                        ) { sel = n; opened(); runCatching { firstChan.requestFocus() } }
                        return@itemsIndexed
                    }
                    val cityR = UsaLocals.rankOf(n)
                    if (cityR != null) {
                        var cityMenu by remember { mutableStateOf(false) }
                        Box {
                            CatRow(
                                UsaLocals.cityLabel(cityR), -1, n == sel,
                                if (n == sel) Modifier.focusRequester(ContentFocus.req) else Modifier,
                                onFocus = { sel = n }, onRight = { runCatching { firstChan.requestFocus() } },
                                onFav = { cityMenu = true }, icon = AIcon.Star, raw = true
                            ) { opened(); runCatching { firstChan.requestFocus() } }
                            if (cityMenu) OptionPopup(listOf("Stadt aus Favoriten entfernen"), -1, { cityMenu = false }, listOf(AIcon.Close)) {
                                cityMenu = false
                                CityFavs.toggle(ctx, cityR)
                                Toast.makeText(ctx, Lang.tr("Stadt aus Favoriten entfernt") + ": " + UsaLocals.cityLabel(cityR), Toast.LENGTH_SHORT).show()
                            }
                        }
                        return@itemsIndexed
                    }
                    val label = when {
                        n == FAV_CAT -> "Favoriten"
                        n == "HOME" -> "Start"
                        n == "ALL" -> "Alle Sender"
                        n == "REC" -> "Aufnahmen"
                        LiveGenre.isGenreKey(n) -> LiveGenre.byKey(n)?.label ?: n
                        ml != null -> ml.name
                        else -> n
                    }
                    val icon = when {
                        n == FAV_CAT || n in CatFavs.list -> AIcon.Star
                        n == "REC" -> AIcon.Download
                        n == "HOME" -> AIcon.Live
                        LiveGenre.isGenreKey(n) -> AIcon.Live
                        ml != null -> AIcon.Lists
                        else -> null
                    }
                    val count = when {
                        n == FAV_CAT -> favs.size
                        n == "ALL" -> live.size
                        n == "HOME" -> 0
                        n == "REC" -> recs.size
                        LiveGenre.isGenreKey(n) -> genres[n]?.size ?: 0
                        ml != null -> ChannelListLogic.resolve(ml, byIdLazy.value).size
                        else -> groups[n]?.size ?: 0
                    }
                    var listMenu by remember { mutableStateOf(false) }
                    var catMenu by remember { mutableStateOf(false) }
                    val plainCat = ml == null && n != FAV_CAT && n != "ALL" && n != "REC" && n != "HOME" && !LiveGenre.isGenreKey(n) && groups.containsKey(n)
                    Box {
                        CatRow(
                            label, count, n == sel,
                            if (n == sel) Modifier.focusRequester(ContentFocus.req) else Modifier,
                            onFocus = { sel = n }, onRight = { runCatching { firstChan.requestFocus() } },
                            onFav = { if (ml != null) listMenu = true else if (plainCat) catMenu = true else if (n != FAV_CAT && n != "ALL" && n != "REC" && !LiveGenre.isGenreKey(n)) CatFavs.toggle(ctx, n) },
                            icon = icon, raw = ml != null
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
                // A city (USA Locals): back to its state, name, save the whole city to Favorites
                if (cityRank != null) {
                    val saved = CityFavs.isSaved(cityRank)
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        PillButton("Zurück", Modifier.heightIn(min = touchMin()), icon = AIcon.Left) { localsState = UsaLocals.stateOf(cityRank); sel = UsaLocals.KEY }
                        Text(raw = true, text = LocalMarkets.byKey(cityRank)?.name ?: "", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        PillButton(if (saved) "Stadt gespeichert" else "Stadt speichern", Modifier.heightIn(min = touchMin()), light = !saved, icon = if (saved) AIcon.Star else AIcon.StarOutline) {
                            val now = CityFavs.toggle(ctx, cityRank)
                            Toast.makeText(ctx, Lang.tr(if (now) "Stadt zu Favoriten hinzugefügt" else "Stadt aus Favoriten entfernt") + ": " + (LocalMarkets.byKey(cityRank)?.name ?: ""), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                if (sel == "SEARCH") Field(query, { query = it.take(40) }, "Sendername eingeben", Modifier.padding(bottom = 12.dp), inner = Modifier.focusRequester(searchReq))
                val favCities = if (sel == FAV_CAT) CityFavs.list else emptyList()
                if (sel == UsaLocals.KEY) LocalsBrowser(localsState, { localsState = it }, firstChan) { r -> sel = UsaLocals.cityKey(r) }
                else if (list.isEmpty() && favCities.isEmpty()) EmptyState(
                    if (sel == FAV_CAT) AIcon.Heart else AIcon.Live,
                    if (sel == FAV_CAT) "Noch keine Favoriten" else if (sel == "SEARCH") (if (query.isBlank()) "Sender suchen" else "Keine Treffer") else if (curList != null) "Diese Liste ist noch leer"
                        else if (cityRank != null) (if (cityLoading) "Lokalsender werden gesucht …" else "Keine Lokalsender gefunden") else "Keine Sender",
                    when {
                        cityRank != null -> "Für diese Stadt wurden bei deinem Anbieter keine Lokalsender (ABC, NBC, CBS, FOX …) gefunden."
                        sel == "SEARCH" -> "Tippe rechts mit der Tastatur. Gesucht wird im Sendernamen und in der Kategorie."
                        sel == FAV_CAT -> "Auf einem Sender OK gedrückt halten (am Handy: lange tippen oder den Stern) und „Zu Favoriten“ wählen. Ganze Städte speicherst du unter „US-Lokalsender“."
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
                        item(key = "cities-h", contentType = "h") { SectionHead("Gespeicherte Städte") }
                        itemsIndexed(favCities, key = { _, r -> "city:$r" }, contentType = { _, _ -> "city" }) { ci, r ->
                            var cm by remember { mutableStateOf(false) }
                            Box {
                                CatRow(
                                    UsaLocals.cityLabel(r), -1, false,
                                    (if (ci == 0) Modifier.focusRequester(firstChan) else Modifier).clip(RoundedCornerShape(DS.RadiusS)).background(Color(0x0FFFFFFF)),
                                    onFocus = {}, onRight = {}, onFav = { cm = true }, icon = AIcon.Star, raw = true
                                ) { sel = UsaLocals.cityKey(r) }
                                if (cm) OptionPopup(listOf("Öffnen", "Stadt aus Favoriten entfernen"), -1, { cm = false }, listOf(AIcon.Right, AIcon.Close)) { i ->
                                    cm = false
                                    if (i == 0) sel = UsaLocals.cityKey(r) else CityFavs.toggle(ctx, r)
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
    onFocus: () -> Unit, onRight: () -> Unit, onFav: () -> Unit, icon: AIcon? = null, raw: Boolean = false, onClick: () -> Unit
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
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) AppIcon(icon, if (icon == AIcon.Star) DS.Gold else Color.White, size = 17.dp)
            Text(label, color = Color.White, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, raw = raw)
        }
        if (count >= 0) Text("$count", color = Muted, fontSize = 13.sp)
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
            } else Text(raw = true, text = item.group, color = Muted, fontSize = 13.sp, maxLines = 1)
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
    for ((k, l) in genres) rows.add((LiveGenre.byKey(k)?.label ?: k) to l.take(40))
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

/**
 * USA Locals browser (ported from MR TV 1.3 UsaLocalsPanel): states A–Z → the state's Nielsen top-5 markets → a city's
 * channels. Tap / OK opens; long-press, Menu, long OK or the star saves / removes a whole city in Favorites.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LocalsBrowser(state: String?, onState: (String?) -> Unit, firstReq: FocusRequester, onCity: (Int) -> Unit) {
    val ctx = LocalContext.current
    val st = UsaLocals.state(state)
    val ls = remember(state) { LazyListState() }
    fun toggle(r: Int) {
        val now = CityFavs.toggle(ctx, r)
        Toast.makeText(ctx, Lang.tr(if (now) "Stadt zu Favoriten hinzugefügt" else "Stadt aus Favoriten entfernt") + ": " + (LocalMarkets.byKey(r)?.name ?: ""), Toast.LENGTH_SHORT).show()
    }
    if (st != null) androidx.activity.compose.BackHandler { onState(null) }
    LazyColumn(Modifier.fillMaxSize().testTag("live-locals"), state = ls, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        if (st == null) {
            item(key = "h") { SectionHead("Bundesstaat wählen") }
            itemsIndexed(UsaLocals.states, key = { _, s -> s.code }) { i, s ->
                BrowseRow(s.name, null, if (i == 0) Modifier.focusRequester(firstReq) else Modifier, onStar = null) { onState(s.code) }
            }
        } else {
            item(key = "back") { BrowseRow("‹ " + st.name, null, Modifier.focusRequester(firstReq), onStar = null) { onState(null) } }
            itemsIndexed(UsaLocals.markets(st.code), key = { _, m -> m.rank }) { _, m ->
                BrowseRow(st.label(m), CityFavs.isSaved(m.rank), Modifier, onStar = { toggle(m.rank) }) { onCity(m.rank) }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BrowseRow(label: String, saved: Boolean?, modifier: Modifier, onStar: (() -> Unit)?, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusS)
    Row(
        modifier.fillMaxWidth().heightIn(min = maxOf(touchMin(), 48.dp)).clip(shape)
            .background(if (f) Color(0x33FFFFFF) else Color(0x0AFFFFFF))
            .onFocusChanged { f = it.isFocused }
            .then(if (onStar != null) Modifier.favKeys { onStar() } else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onStar?.let { touchOnly(it) })
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(raw = true, text = label, color = Color.White, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        if (saved != null && onStar != null) Box(
            // Touch: the star saves / removes the whole city (not focusable: D-pad uses Menu / long OK)
            Modifier.size(maxOf(touchMin(), 44.dp)).clip(RoundedCornerShape(50)).pointerInput(label) { detectTapGestures(onTap = { onStar() }) },
            contentAlignment = Alignment.Center
        ) { AppIcon(if (saved) AIcon.Star else AIcon.StarOutline, if (saved) DS.Gold else Muted, size = 24.dp) }
    }
}
