package com.ipvio.stream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MR TV 2: upstream credit and notice of modifications (GPL-3.0 section 5), shown in Settings > About.
 * Plain English on purpose (raw = true): license credits are not translated.
 */
@Composable
internal fun MrtvCredits() {
    SectionTitle("Credits")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(Rebrand.UPSTREAM_CREDIT, raw = true, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(Rebrand.UPSTREAM_URL, raw = true, color = Accent2, fontSize = 15.sp)
        Text(
            "MR TV 2 is a modified version of IPVIO Stream, changed by MR TV (watchmrtv.com) in 2026: new name, logo, " +
                "colors and app ID, outside movie-info and rating services removed (all info comes from the provider), " +
                "login locked to the MR TV server, own update address. " +
                "It is free software under the GNU General Public License v3 (GPL-3.0) and comes with no warranty.",
            raw = true, color = Muted, fontSize = 14.sp, lineHeight = 20.sp
        )
        // 2.0.6: no QR code here any more; the source address stays as text (GPL-3.0)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Source code of MR TV 2:", raw = true, color = Muted, fontSize = 14.sp)
            Text(Rebrand.SOURCE_URL, raw = true, color = Accent2, fontSize = 15.sp)
        }
    }
}
