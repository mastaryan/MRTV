package com.ipvio.stream

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * MR TV 2 (2.0.6): flag of a country (bundled image, see tools/categories/make_flags.py) or, for regions and
 * countries without a bundled flag, the globe icon. Emoji flags are not used: older Fire TV devices show letters instead.
 */
@Composable
fun CountryFlag(c: LiveCats.Country, height: Dp = 16.dp) {
    val ctx = LocalContext.current
    val id = remember(c.flag) { c.flag?.let { ctx.resources.getIdentifier("flag_$it", "drawable", ctx.packageName) } ?: 0 }
    if (id != 0) Image(painterResource(id), c.name, Modifier.size(height * 4 / 3, height).clip(RoundedCornerShape(2.dp)), contentScale = ContentScale.Fit)
    else if (c.id == LiveCats.ADULT) AppIcon(AIcon.Lock, Color(0xFFB8B8C2), size = height + 2.dp)
    else AppIcon(AIcon.Globe, Color.White, size = height + 2.dp)
}

/** Small outline icon per category topic (same line style as the sidebar and the sports icons). */
@Composable
fun CatTopicIcon(topic: String, tint: Color = Color.White, size: Dp = 17.dp) {
    when (topic) {
        "music" -> { AppIcon(AIcon.Music, tint, size = size); return }
        "adult" -> { AppIcon(AIcon.Lock, Color(0xFFB8B8C2), size = size); return }
        "streaming" -> { AppIcon(AIcon.Play, tint, size = size); return }
        "replay" -> { AppIcon(AIcon.Rewind, tint, size = size); return }
        "twentyfour" -> { AppIcon(AIcon.History, tint, size = size); return }
        "entertainment" -> { AppIcon(AIcon.Sparkle, tint, size = size); return }
        "documentary", "nature" -> { AppIcon(AIcon.Globe, tint, size = size); return }
        "sports", "league" -> { MenuIcon(10, tint, Modifier.size(size)); return }
        "movies" -> { MenuIcon(1, tint, Modifier.size(size)); return }
    }
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val sw = Stroke(width = maxOf(1.3.dp.toPx(), s * 0.09f), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = sw.width
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), strokeWidth = w, cap = StrokeCap.Round)
        fun rect(x: Float, y: Float, rw: Float, rh: Float, r: Float) =
            drawRoundRect(tint, topLeft = Offset(x * s, y * s), size = Size(rw * s, rh * s), cornerRadius = CornerRadius(r * s), style = sw)
        when (topic) {
            "news" -> { // newspaper
                rect(.12f, .18f, .76f, .64f, .08f)
                line(.24f, .34f, .5f, .34f); line(.24f, .5f, .76f, .5f); line(.24f, .64f, .76f, .64f)
                rect(.58f, .28f, .18f, .12f, .02f)
            }
            "kids" -> { // balloon
                drawOval(tint, topLeft = Offset(.26f * s, .08f * s), size = Size(.48f * s, .56f * s), style = sw)
                val p = Path().apply { moveTo(.5f * s, .64f * s); quadraticTo(.42f * s, .78f * s, .54f * s, .92f * s) }
                drawPath(p, tint, style = sw)
            }
            "network" -> { // broadcast tower
                line(.5f, .36f, .3f, .9f); line(.5f, .36f, .7f, .9f); line(.37f, .7f, .63f, .7f)
                drawCircle(tint, radius = .07f * s, center = Offset(.5f * s, .3f * s))
                drawArc(tint, 200f, 140f, false, topLeft = Offset(.24f * s, .06f * s), size = Size(.52f * s, .48f * s), style = sw)
            }
            "ppv" -> { // ticket
                val p = Path().apply {
                    moveTo(.1f * s, .28f * s); lineTo(.9f * s, .28f * s); lineTo(.9f * s, .42f * s)
                    quadraticTo(.8f * s, .5f * s, .9f * s, .58f * s); lineTo(.9f * s, .72f * s); lineTo(.1f * s, .72f * s)
                    lineTo(.1f * s, .58f * s); quadraticTo(.2f * s, .5f * s, .1f * s, .42f * s); close()
                }
                drawPath(p, tint, style = sw)
                line(.62f, .34f, .62f, .66f)
            }
            "uhd" -> { // screen with "4K" bars
                rect(.1f, .2f, .8f, .56f, .08f)
                line(.3f, .36f, .3f, .6f); line(.3f, .52f, .44f, .52f); line(.42f, .36f, .42f, .6f)
                line(.56f, .36f, .56f, .6f); line(.56f, .48f, .72f, .36f); line(.6f, .46f, .72f, .6f)
                line(.36f, .86f, .64f, .86f)
            }
            "language" -> { // speech bubble
                val p = Path().apply {
                    moveTo(.14f * s, .2f * s); lineTo(.86f * s, .2f * s); lineTo(.86f * s, .66f * s); lineTo(.42f * s, .66f * s)
                    lineTo(.26f * s, .84f * s); lineTo(.28f * s, .66f * s); lineTo(.14f * s, .66f * s); close()
                }
                drawPath(p, tint, style = sw)
                line(.3f, .38f, .7f, .38f); line(.3f, .5f, .58f, .5f)
            }
            "religious" -> { // open book
                val p = Path().apply {
                    moveTo(.5f * s, .3f * s); quadraticTo(.3f * s, .18f * s, .1f * s, .24f * s); lineTo(.1f * s, .8f * s)
                    quadraticTo(.3f * s, .74f * s, .5f * s, .86f * s); quadraticTo(.7f * s, .74f * s, .9f * s, .8f * s)
                    lineTo(.9f * s, .24f * s); quadraticTo(.7f * s, .18f * s, .5f * s, .3f * s); close()
                }
                drawPath(p, tint, style = sw)
                line(.5f, .3f, .5f, .86f)
            }
            "regional" -> { // map pin
                val p = Path().apply {
                    moveTo(.5f * s, .9f * s); cubicTo(.2f * s, .56f * s, .2f * s, .12f * s, .5f * s, .12f * s)
                    cubicTo(.8f * s, .12f * s, .8f * s, .56f * s, .5f * s, .9f * s); close()
                }
                drawPath(p, tint, style = sw)
                drawCircle(tint, radius = .09f * s, center = Offset(.5f * s, .4f * s), style = sw)
            }
            else -> { // general: TV set
                rect(.1f, .28f, .8f, .54f, .08f)
                line(.36f, .1f, .5f, .28f); line(.64f, .1f, .5f, .28f)
            }
        }
    }
}
