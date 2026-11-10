package com.ipvio.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val RenewRed = Color(0xFFE53935)
private val CardBg = Color(0xFF17191C)
private val CardLine = Color(0xFF2A2E34)

/** The payment tiles: QR codes made in the app from the links (Cash App, Venmo), Zelle as a big number. */
@Composable
fun PayTiles(info: PayInfo.Info = PayInfo.info, tile: Dp = 150.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.Top) {
        for (o in info.options) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (o.qr != null) Box(Modifier.size(tile).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp)) { QrImage(o.qr, tile - 16.dp) }
            else Column(
                Modifier.size(tile).clip(RoundedCornerShape(12.dp)).background(Color(o.color)),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
            ) {
                androidx.compose.foundation.text.BasicText(o.name, style = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = (tile.value / 4.6f).sp, fontWeight = FontWeight.Black))
                androidx.compose.foundation.text.BasicText(o.big ?: "", style = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = (tile.value / 8.6f).sp, fontWeight = FontWeight.ExtraBold))
            }
            Text(o.label, raw = true, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PayWarning(info: PayInfo.Info) {
    val w = info.warning
    Box(Modifier.widthIn(max = 640.dp).clip(RoundedCornerShape(12.dp)).background(RenewRed.copy(alpha = 0.12f)).border(1.dp, RenewRed, RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 10.dp)) {
        // Bold parts as in the approved mockup ("only your name", "Never write "TV"")
        val t = buildAnnotatedString {
            var rest = w
            for (b in listOf("only your name", "Never write \"TV\"")) {
                val i = rest.indexOf(b)
                if (i < 0) continue
                append(rest.substring(0, i)); withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.ExtraBold)) { append(b) }; rest = rest.substring(i + b.length)
            }
            append(rest)
        }
        androidx.compose.foundation.text.BasicText(t, style = androidx.compose.ui.text.TextStyle(color = Color(0xFFFFD7D6), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, lineHeight = 21.sp))
    }
}

/** The whole renewal block: title, tiles, warning, hint (popup, ended screen, Settings > About). */
@Composable
fun RenewBlock(title: @Composable () -> Unit, compact: Boolean = false) {
    val info = PayInfo.info
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)) {
        title()
        PayTiles(info, if (compact) 120.dp else 150.dp)
        PayWarning(info)
        Text(info.hint, raw = true, color = Color(0xFFD3D7E2), fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun EndsTitle(expSec: Long) {
    val d = RenewLogic.shortDate(expSec, General.zone())
    val t = buildAnnotatedString { append(RenewLogic.TITLE); withStyle(SpanStyle(color = RenewRed)) { append(d) } }
    androidx.compose.foundation.text.BasicText(t, style = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center))
}

@Composable
private fun Kicker(s: String) = Text(s, raw = true, color = Color(0xFFA0A6B8), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)

/** Startup reminder (last 5 days). One button: "OK, watch TV". Back closes it too. */
@Composable
fun RenewPopup(expSec: Long, onClose: () -> Unit) {
    val fr = remember { FocusRequester() }
    androidx.activity.compose.BackHandler { onClose() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(150); runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xB8000000)).testTag("renew-popup"), contentAlignment = Alignment.Center) {
        BoxWithConstraints {
            val compact = maxHeight < 560.dp || maxWidth < 700.dp
            Column(
                Modifier.widthIn(max = 880.dp).padding(16.dp).clip(RoundedCornerShape(20.dp)).background(CardBg).border(1.dp, CardLine, RoundedCornerShape(20.dp))
                    .verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Kicker("SUBSCRIPTION REMINDER")
                RenewBlock({ EndsTitle(expSec) }, compact)
                PillButton(RenewLogic.BUTTON, Modifier.focusRequester(fr).testTag("renew-ok"), light = true, raw = true) { onClose() }
            }
        }
    }
}

/** After the end date: payment info, "Change login" and "Retry". Nothing opens a website. */
@Composable
fun SubscriptionEnded(onChangeLogin: () -> Unit, onActive: () -> Unit) {
    val ctx = LocalContext.current
    val fr = remember { FocusRequester() }
    var msg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(150); runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xF20D0E10)).testTag("renew-ended"), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 880.dp).padding(16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Kicker("MR TV")
            RenewBlock({
                Text("Your MR TV subscription has ended", raw = true, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            }, compact = true)
            if (msg.isNotBlank()) Text(msg, raw = true, color = Accent2, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PillButton("Retry", Modifier.focusRequester(fr), light = true, icon = AIcon.Refresh, raw = true) {
                    msg = "Checking…"
                    scope.launch { if (Renewal.recheck(ctx)) { msg = ""; onActive() } else msg = "Still not active. Contact MR TV to renew, then press Retry." }
                }
                PillButton("Change login", icon = AIcon.Person, raw = true) { onChangeLogin() }
            }
        }
    }
}
