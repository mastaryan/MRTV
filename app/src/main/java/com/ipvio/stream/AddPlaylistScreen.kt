package com.ipvio.stream

import androidx.compose.ui.graphics.lerp
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun AddPlaylistScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Last entered playlist that has not finished loading (crash, power loss, abort): prefill
    val pend = remember { PlaylistStore.pending(ctx) }
    val pendX = remember { pend?.let { LoadLogic.xtreamParts(it.url) } }
    // MR TV 2: built-in server (see Rebrand.SERVER_URL): only username and password, no server field, no M3U/Stalker
    val locked = Rebrand.SERVER_LOCKED
    var xtream by remember { mutableStateOf(locked || pendX != null) }
    // Runde 20: Stalker-Portal (Portal-Adresse + MAC-Adresse)
    val pendS = remember { pend?.url?.let { StalkerLogic.parse(it) } }
    var stalker by remember { mutableStateOf(pendS != null) }
    var portal by remember { mutableStateOf(pendS?.portal ?: "") }
    var mac by remember { mutableStateOf(pendS?.mac ?: "00:1A:79:") }
    // Optional: some providers also want serial number and device IDs
    var sn by remember { mutableStateOf(pendS?.sn ?: "") }
    var devId by remember { mutableStateOf(pendS?.id1 ?: "") }
    var devId2 by remember { mutableStateOf(pendS?.id2 ?: "") }
    var url by remember { mutableStateOf(if (pend != null && pendX == null && pendS == null) pend.url else "") }
    val pendOk = pendX != null && (!locked || Rebrand.isBuiltInServer(pendX.first))
    var host by remember { mutableStateOf(if (locked) Rebrand.SERVER_URL else pendX?.first ?: "") }
    var user by remember { mutableStateOf(if (pendOk) pendX?.second ?: "" else "") }
    var pass by remember { mutableStateOf(if (pendOk) pendX?.third ?: "" else "") }
    var showPass by remember { mutableStateOf(false) }
    // Send from the phone: local mini server as long as this screen is open
    val ip = remember { PhoneImport.localIp() }
    val receiver = remember {
        PhoneImport { inp ->
            Handler(Looper.getMainLooper()).post {
                if (locked && !inp.isXtream) { PlaylistStore.error = "Bitte Benutzername und Passwort eingeben."; return@post }
                val u = if (inp.isXtream) PlaylistStore.xtreamUrl(inp.host, inp.user, inp.pass) else inp.url
                stalker = false
                if (inp.isXtream) { xtream = true; host = inp.host; user = inp.user; pass = inp.pass } else { xtream = false; url = inp.url }
                if (u == null) PlaylistStore.error = "Angaben unvollständig: Server, Benutzername und Passwort nötig."
                else scope.launch { if (PlaylistStore.load(ctx, u, true)) onDone() }
            }
        }
    }
    var serving by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        // Runde 20: Playlist-Datei vom Handy hochladen, Absturzbericht am Handy lesen
        if (locked) receiver.loginServer = Rebrand.SERVER_URL
        else {
            receiver.uploadDir = ImportFiles.tmpDir(ctx)
            receiver.saveUpload = { name, f -> ImportFiles.save(ctx, name, f) }
        }
        receiver.crash = { ImportFiles.crashText(ctx) }
        // Full transfer from another device (settings, history, playlists with logins): also possible from the phone page
        receiver.restore = { t ->
            val ok = Backup.restoreText(ctx, t) >= 0
            if (ok) Handler(Looper.getMainLooper()).postDelayed({ Profiles.restart(ctx) }, 1500)
            ok
        }
        receiver.onEpg = { u ->
            Handler(Looper.getMainLooper()).post {
                Xmltv.setCustom(ctx, u)
                if (PlaylistStore.channels.isNotEmpty()) Xmltv.start(ctx, PlaylistStore.epgUrls, PlaylistStore.channels)
            }
        }
        receiver.onList = { name, bytes ->
            Handler(Looper.getMainLooper()).post { ListImportApply.run(ctx, name, bytes) }
        }
        serving = receiver.start()
        onDispose { receiver.stop() }
    }
    val canCancel = PlaylistStore.lists.isNotEmpty() || PlaylistStore.channels.isNotEmpty()
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val u = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ImportFiles.fromUri(ctx, uri) }
            if (u == null) PlaylistStore.error = "Das ist keine gültige M3U-Datei."
            else if (PlaylistStore.load(ctx, u, true)) onDone()
        }
    }
    // Backup of another device (with playlists and logins): load the file, the app restarts with everything in place
    val pickBackup = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching -1
                    if (bytes.size > 4 * 1024 * 1024) -1 else Backup.restoreText(ctx, String(bytes, Charsets.UTF_8))
                }.getOrDefault(-1)
            }
            if (n >= 0) Profiles.restart(ctx) else PlaylistStore.error = "Keine gültige Sicherung gefunden."
        }
    }
    BackHandler(enabled = canCancel) { PlaylistStore.clearPending(ctx); onDone() }

    // Interrupted in the middle of loading last time: the entries are prefilled (MR TV 2: no automatic restart)
    LaunchedEffect(Unit) {
        val p = pend ?: return@LaunchedEffect
        if (locked && !pendOk) return@LaunchedEffect
        when {
            // MR TV 2: an interrupted load (crash, app killed) is never restarted by itself – the fields stay filled in and
            // the user taps Sign in again, so a load that crashes the app cannot loop at every start
            p.interrupted && locked -> {}
            p.interrupted -> PlaylistStore.error = "Das Laden wurde mehrmals unterbrochen. Deine Angaben sind gespeichert – drücke „Hinzufügen“, um es erneut zu versuchen."
            p.msg != null -> PlaylistStore.error = p.msg
        }
    }

    // MR TV 2: after a successful sign-in stay on the branded loading step until the TV guide is in (or timeout / Skip)
    var epgGate by remember { mutableStateOf(false) }
    if (epgGate) { EpgGateScreen { MobilePrefs.markFirstLoginDone(ctx); onDone() }; return }
    if (PlaylistStore.loading) { PlaylistLoadingOverlay(); return }

    // Start focus on the M3U/Xtream choice (not on an input field: that would open the on-screen keyboard immediately)
    val startFocus = remember { FocusRequester() }
    // MR TV 2 login (built-in server): Username first. Phone: cursor in Username with the keyboard open;
    // TV: Username highlighted (OK opens the keyboard).
    val mobile = remember { MobilePrefs.isMobile(ctx) }
    val userBox = remember { FocusRequester() }
    val userText = remember { FocusRequester() }
    val passBox = remember { FocusRequester() }
    val passText = remember { FocusRequester() }
    /** TV: which login field is being edited (0 none, 1 username, 2 password). On the phone both are always editable. */
    var editField by remember { mutableIntStateOf(0) }
    val loginKb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(120)
        if (!locked) runCatching { startFocus.requestFocus() }
        else if (mobile) { runCatching { userText.requestFocus() }; loginKb?.show() }
        else runCatching { userBox.requestFocus() }
    }
    fun signInLocked() {
        if (PlaylistStore.loading) return
        val lockedUrl = PlaylistStore.xtreamUrl(Rebrand.SERVER_URL, user, pass)
        if (lockedUrl == null) PlaylistStore.error = "Bitte Benutzername und Passwort eingeben."
        else scope.launch { if (PlaylistStore.load(ctx, lockedUrl, true)) epgGate = true }
    }

    // Phone (narrow or portrait): single column, without the phone card, smaller margins
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val compact = cfg.screenWidthDp < 900 || cfg.screenHeightDp > cfg.screenWidthDp
    // Everything should be visible at a glance: the screen is drawn a little smaller (TV 15 %, phone 8 %)
    // and scrolls without animation (the app-wide soft scrolling made this screen trail behind the cursor).
    val d0 = androidx.compose.ui.platform.LocalDensity.current
    val dense = remember(d0.density, d0.fontScale, compact) { androidx.compose.ui.unit.Density(d0.density * (if (compact) 0.92f else 0.85f), d0.fontScale) }
    CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides dense,
        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides InstantSpec
    ) {
    Row(
        Modifier.fillMaxSize()
            .background(Brush.radialGradient(listOf(lerp(Bg, Accent1, 0.14f), Bg), radius = 1400f))
            .padding(horizontal = if (compact) 10.dp else 28.dp, vertical = if (compact) 6.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 28.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val form: @Composable () -> Unit = {
            if (locked) {
                // MR TV 2 login: logo and name centered above the fields; smaller (never hidden) while the keyboard is open
                val ime = WindowInsets.isImeVisible
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BrandMark(Modifier.size(if (ime) 44.dp else 84.dp))
                    Text(Rebrand.APP_NAME, raw = true, color = Color.White, fontSize = if (ime) 22.sp else 30.sp, fontWeight = FontWeight.ExtraBold)
                }
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrandMark(Modifier.size(36.dp))
                Text(Rebrand.APP_NAME, raw = true, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text("Playlist hinzufügen", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp))
            }
            // Many new users ask which link to enter: say clearly that the app brings no content of its own
            if (!locked) Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusM)).background(Color(0x14FFFFFF)).border(1.dp, Surf2, RoundedCornerShape(DS.RadiusM)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("Wichtig zu wissen", color = Accent2, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("IPVIO Stream ist nur ein Abspielgerät. Die App liefert selbst keine Sender, Filme, Serien und keine Playlist. Den Link oder die Zugangsdaten bekommst du von deinem IPTV-Anbieter oder aus deiner eigenen Quelle. Wir können dir keinen Link nennen.",
                    color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
            }
            // The padding sits inside the scrolling row: an enlarged (focused) button must not be cut off at its edge
            if (!locked) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusButton("M3U-Link", Modifier.focusRequester(startFocus), primary = !xtream && !stalker, compact = true) { xtream = false; stalker = false }
                FocusButton("Xtream-Login", primary = xtream && !stalker, compact = true) { xtream = true; stalker = false }
                FocusButton("Stalker-Portal", primary = stalker, compact = true) { stalker = true; xtream = false }
            }
            if (stalker) {
                Field(portal, { portal = it }, "Portal-Adresse (z. B. http://portal.tld:8080/c/)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(mac, { mac = it }, "MAC-Adresse", Modifier.weight(1f))
                    Field(sn, { sn = it }, "Seriennummer (optional)", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(devId, { devId = it }, "Device ID (optional)", Modifier.weight(1f))
                    Field(devId2, { devId2 = it }, "Device ID 2 (optional)", Modifier.weight(1f))
                }
                Text("Seriennummer und Device IDs nur eintragen, wenn dein Anbieter sie verlangt. Leer = die App erzeugt eigene Werte.", color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
            } else if (!xtream) {
                Field(url, { url = it }, "http://…/playlist.m3u")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FocusButton("Aus Zwischenablage einfügen", compact = true) {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.primaryClip?.getItemAt(0)?.text?.let { url = it.toString() }
                    }
                    // Round 20: M3U file from the device (USB stick, Downloads …); on devices without a file picker via the phone page
                    FocusButton("Datei vom Gerät", compact = true) {
                        try { pickFile.launch(arrayOf("*/*")) }
                        catch (e: Exception) { PlaylistStore.error = "Auf diesem Gerät gibt es keine Dateiauswahl. Lade die Datei über die Handy-Seite rechts hoch." }
                    }
                }
            } else if (locked) {
                // MR TV 2: Username above Password, full width; keyboard Next -> Password, Go -> Sign in
                LoginField(
                    user, { user = it }, "Benutzername", password = false, mobile = mobile,
                    editing = editField == 1, onEditing = { on -> if (on) editField = 1 else if (editField == 1) editField = 0 },
                    boxReq = userBox, textReq = userText, last = false,
                    onNext = { if (mobile) runCatching { passText.requestFocus() } else editField = 2 },
                    onGo = {}
                )
                LoginField(
                    pass, { pass = it }, "Passwort", password = !showPass, mobile = mobile,
                    editing = editField == 2, onEditing = { on -> if (on) editField = 2 else if (editField == 2) editField = 0 },
                    boxReq = passBox, textReq = passText, last = true,
                    onNext = {},
                    onGo = { loginKb?.hide(); editField = 0; signInLocked() }
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
                    LoginButton(if (showPass) "Passwort verbergen" else "Passwort anzeigen") { showPass = !showPass }
                }
            } else {
                Field(host, { host = it }, "Server (z. B. http://server.tld:8080)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(user, { user = it }, "Benutzername", Modifier.weight(1f))
                    Field(pass, { pass = it }, "Passwort", Modifier.weight(1f), password = !showPass)
                }
                Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                    FocusButton(if (showPass) "Passwort verbergen" else "Passwort anzeigen", compact = true) { showPass = !showPass }
                }
            }
            PlaylistStore.error?.let { Text(it, color = DS.Danger, fontSize = 14.sp, lineHeight = 18.sp) }
            // MR TV 2 login: Sign in, then (below it) Cancel / Continue without playlist
            if (locked) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
                    LoginButton(if (PlaylistStore.loading) "Lade…" else "Anmelden", primary = true) { signInLocked() }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
                    if (canCancel) LoginButton("Abbrechen") { PlaylistStore.clearPending(ctx); onDone() }
                    else LoginButton("Weiter ohne Playlist") { PlaylistStore.clearPending(ctx); PlaylistStore.error = null; onDone() }
                }
            }
            if (!locked) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusButton(if (PlaylistStore.loading) "Lade…" else "Hinzufügen", primary = true, compact = true) {
                    if (PlaylistStore.loading) return@FocusButton
                    val finalUrl = if (stalker) StalkerLogic.source(portal, mac, sn, devId, devId2)?.encode() else if (xtream) PlaylistStore.xtreamUrl(host, user, pass) else url
                    if (finalUrl == null) PlaylistStore.error = if (stalker) "Angaben unvollständig: Portal-Adresse und MAC-Adresse (z. B. 00:1A:79:12:34:56) nötig." else "Angaben unvollständig: Server, Benutzername und Passwort nötig."
                    else scope.launch { if (PlaylistStore.load(ctx, finalUrl, true)) onDone() }
                }
                if (canCancel) FocusButton("Abbrechen", compact = true) { PlaylistStore.clearPending(ctx); onDone() }
                // Nothing to add yet (first start): just look around the app; the home screen then offers "Playlist hinzufügen"
                else FocusButton("Weiter ohne Playlist", compact = true) { PlaylistStore.clearPending(ctx); PlaylistStore.error = null; onDone() }
            }
            // Second device? Take everything over in one step
            if (!locked) Text("Schon ein anderes Gerät mit IPVIO Stream? Spiele die Sicherung ein: Playlists, Zugangsdaten, Verlauf, Favoriten und Einstellungen sind danach sofort da.", color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
            if (!locked) Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                FocusButton("Sicherung von anderem Gerät einspielen", compact = true) {
                    try { pickBackup.launch(arrayOf("*/*")) }
                    catch (e: Exception) { PlaylistStore.error = "Auf diesem Gerät gibt es keine Dateiauswahl. Spiele die Sicherung über die Handy-Seite rechts ein." }
                }
            }
        }
        if (locked) BoxWithConstraints(Modifier.weight(1.1f).fillMaxHeight().imePadding()) {
            // Centered block with a maximum width (does not stretch on the Fold's inner screen); scrolls if the keyboard needs the room
            val minH = maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = minH).padding(if (compact) 6.dp else 10.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(Modifier.widthIn(max = 460.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { form() }
                }
            }
        } else Column(
            // Padding in the scroll area: the focused button is enlarged and must not be cut off
            Modifier.weight(1.1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(if (compact) 6.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) { form() }
        // Right: instructions for input on the phone
        if (!compact) PhoneCard(Modifier.weight(1f), if (serving && ip != null) "http://" + ip + ":" + receiver.port else null, receiver.code, locked)
    }
    }
}

/** Scrolls to a focused element at once (no animation, small margin) instead of the app-wide soft scrolling. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
val InstantSpec = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> = androidx.compose.animation.core.snap()
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val peek = 40f
        val leading = offset
        val trailing = offset + size
        return when {
            leading >= 0f && trailing <= containerSize -> 0f
            leading < 0f && trailing > containerSize -> 0f
            leading < 0f -> leading - peek
            else -> trailing - containerSize + peek
        }
    }
}

/** Card with instructions: enter the playlist conveniently on the phone instead of with the remote. */
@Composable
private fun PhoneCard(modifier: Modifier, address: String?, code: String, locked: Boolean = false) {
    val shape = RoundedCornerShape(DS.RadiusXl)
    var bigQr by remember { mutableStateOf(false) }
    // The card scrolls (the focused QR code brings the rest into view), so nothing is cut off on small screens
    Column(
        modifier.fillMaxHeight().clip(shape).background(Surf).border(2.dp, Surf2, shape).verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Einfacher mit dem Handy", color = Accent2, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Mit der Fernbedienung zu tippen ist mühsam. Schick die Zugangsdaten lieber vom Handy:",
            color = Muted, fontSize = 15.sp, lineHeight = 21.sp
        )
        if (address == null) {
            Text(
                "Die Übertragung vom Handy ist gerade nicht verfügbar. Das Gerät muss mit dem WLAN verbunden sein.",
                color = Color(0xFFFFB86B), fontSize = 17.sp, lineHeight = 24.sp
            )
        } else {
            Step(1, "Handy mit demselben WLAN verbinden wie dieses Gerät.")
            Step(2, "Im Browser des Handys diese Adresse öffnen:")
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(DS.RadiusM)).background(Bg).padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    Text(address, color = Accent2, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, raw = true)
                }
                var qf by remember { mutableStateOf(false) }
                Column(
                    Modifier.clip(RoundedCornerShape(DS.RadiusM)).border(if (qf) 3.dp else 0.dp, Color.White, RoundedCornerShape(DS.RadiusM))
                        .onFocusChanged { qf = it.isFocused }.clickable { bigQr = true }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    QrImage(address, 110.dp)
                    Text("OK = groß", color = Muted, fontSize = 12.sp)
                }
            }
            Step(3, "Den Code von hier eingeben:")
            Row(
                Modifier.clip(RoundedCornerShape(DS.RadiusM)).background(Bg).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Code", color = Muted, fontSize = 18.sp)
                Text(code, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 4.sp)
            }
            Step(4, if (locked) "Benutzername und Passwort eintragen und senden. Die Playlist wird danach automatisch geladen." else "Zugangsdaten eintragen oder eine M3U-Datei hochladen und senden. Die Playlist wird danach automatisch geladen.")
        }
    }
    if (bigQr && address != null) Dialog(onDismissRequest = { bigQr = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val close = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); runCatching { close.requestFocus() } }
        Box(Modifier.fillMaxSize().background(Color(0xE6000000)).clickable { bigQr = false }.focusRequester(close).focusable(), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
                QrImage(address, 440.dp)
                Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Mit der Handy-Kamera scannen", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Handy und dieses Gerät müssen im selben WLAN sein. Halte die Kamera auf das Bild. Es klappt auch aus einiger Entfernung.", color = Muted, fontSize = 18.sp, lineHeight = 26.sp)
                    Text("Oder Adresse im Browser eintippen:", color = Muted, fontSize = 16.sp)
                    Text(address, color = Accent2, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, raw = true)
                    Text("Code: $code", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, raw = true)
                    Text("Zurück oder OK schließt das Bild.", color = Muted, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(50)).background(Accent1),
            contentAlignment = Alignment.Center
        ) {
            Text(n.toString(), color = OnAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Text(text, color = Color.White, fontSize = 17.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * MR TV 2 login field (built-in server): label above a full-width box.
 * Phone: a normal text field, a tap goes straight in. TV: the box is highlighted first, OK starts editing and opens the
 * keyboard; Up/Down leave the field. Keyboard action: Next (Username → Password) or Go (Password → Sign in).
 */
@Composable
private fun LoginField(
    value: String, onChange: (String) -> Unit, label: String, password: Boolean, mobile: Boolean,
    editing: Boolean, onEditing: (Boolean) -> Unit, boxReq: FocusRequester, textReq: FocusRequester,
    last: Boolean, onNext: () -> Unit, onGo: () -> Unit
) {
    var f by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    var returnToBox by remember { mutableStateOf(false) }
    val active = mobile || editing
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(editing) {
        if (mobile) return@LaunchedEffect
        if (editing) { runCatching { textReq.requestFocus() }; kb?.show() }
        else if (returnToBox) { returnToBox = false; runCatching { boxReq.requestFocus() } }
    }
    // Focus look copied from MR TV 1.3's login (TvTextField): 3 dp red outline, 6 dp corners, lighter fill, red bold label
    val shape = RoundedCornerShape(LOGIN_RADIUS)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = if (f) LOGIN_RED_LABEL else LOGIN_SILVER, fontSize = 15.sp,
            fontWeight = if (f) FontWeight.Bold else FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
        Box(
            Modifier.fillMaxWidth().height(62.dp)
                .clip(shape).background(if (f) LOGIN_FOCUS_FILL else Surf)
                .border(if (f) LOGIN_FOCUS_WIDTH else 1.dp, if (f) LOGIN_RED else LOGIN_DIM, shape)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            // Placeholder under the text (drawn first, so taps reach the text field)
            if (value.isEmpty()) Text(label, color = LOGIN_DIM, fontSize = 20.sp)
            // Phone: a tap anywhere in the box (not only on the text line) goes into the field
            if (mobile) Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null
                ) { runCatching { textReq.requestFocus() }; kb?.show() }
            )
            // TV: focus target while not editing; OK starts editing
            if (!mobile) Box(
                Modifier.fillMaxSize()
                    .focusRequester(boxReq)
                    .onFocusChanged { f = it.isFocused || it.hasFocus }
                    .clickable { onEditing(true) }
            )
            androidx.compose.foundation.text.BasicTextField(
                value = value, onValueChange = onChange, singleLine = true, enabled = active,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 20.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
                    else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (password) androidx.compose.ui.text.input.KeyboardType.Password else androidx.compose.ui.text.input.KeyboardType.Text,
                    autoCorrectEnabled = false,
                    imeAction = if (last) androidx.compose.ui.text.input.ImeAction.Go else androidx.compose.ui.text.input.ImeAction.Next
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onNext = { onNext() },
                    onGo = { if (!mobile) returnToBox = true; onGo() },
                    onDone = { if (!mobile) returnToBox = true; onGo() }
                ),
                modifier = Modifier.fillMaxWidth()
                    .focusRequester(textReq)
                    .onFocusChanged {
                        if (it.isFocused) { hadFocus = true; f = true }
                        else if (hadFocus) { hadFocus = false; if (mobile) f = false else if (editing) onEditing(false) }
                    }
                    .then(if (mobile) Modifier else Modifier.onPreviewKeyEvent { e ->
                        val up = e.key == androidx.compose.ui.input.key.Key.DirectionUp
                        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && (up || e.key == androidx.compose.ui.input.key.Key.DirectionDown)) {
                            kb?.hide()
                            fm.moveFocus(if (up) androidx.compose.ui.focus.FocusDirection.Up else androidx.compose.ui.focus.FocusDirection.Down)
                            true
                        } else false
                    })
            )
        }
    }
}

// MR TV 1.3 login focus look (app/tv MainActivity.kt TvTextField, Ui.kt object C)
private val LOGIN_RED = Color(0xFFFF3030)        // focused outline
private val LOGIN_RED_LABEL = Color(0xFFFF4040)  // focused label
private val LOGIN_DIM = Color(0xFF7D8188)        // unfocused outline / placeholder (C.Dim)
private val LOGIN_SILVER = Color(0xFFC9CCD1)     // unfocused label (C.Silver)
private val LOGIN_FOCUS_FILL = Color(0xFF3A3D42) // focused field background (C.Focus)
private val LOGIN_FOCUS_WIDTH = 3.dp
private val LOGIN_RADIUS = 6.dp

/** MR TV 2 login button: the normal MR TV 2 button, plus 1.3's 3 dp red outline while focused (D-pad or keyboard). */
@Composable
private fun LoginButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    // MR TV 2: full width of the login block and at least 48 real dp tall (phone and TV, whatever the app's UI scaling)
    val minH = maxOf(realDp(48f), 48.dp)
    var f by remember { mutableStateOf(false) }
    val s by androidx.compose.animation.core.animateFloatAsState(if (f) focusScale(1.07f) else 1f, label = "loginBtn")
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .fillMaxWidth().heightIn(min = minH)
            .graphicsLayer { scaleX = s; scaleY = s }
            .clip(shape)
            .background(if (f) DS.FocusFill else if (primary) lerp(DS.Overlay3, Look.accent1, 0.32f) else DS.Overlay3)
            .border(if (f) LOGIN_FOCUS_WIDTH else 0.dp, if (f) LOGIN_RED else Color.Transparent, shape)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (f) DS.FocusInk else Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}
