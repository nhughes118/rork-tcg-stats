package com.rork.tcgstatsandroid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rork.tcgstatsandroid.data.CardText
import com.rork.tcgstatsandroid.data.DeckShare
import com.rork.tcgstatsandroid.data.PlayerEntry
import com.rork.tcgstatsandroid.data.TournamentAppearance
import com.rork.tcgstatsandroid.ui.theme.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Dates {
    fun short(millis: Long): String = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
    fun medium(millis: Long): String = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))
}

@Composable
fun Chevron(modifier: Modifier = Modifier) {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = Palette.Muted.copy(alpha = 0.6f),
        modifier = modifier.size(20.dp),
    )
}

@Composable
fun DeckRow(rank: Int?, deck: DeckShare, modifier: Modifier = Modifier, showChevron: Boolean = true) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (rank != null) {
            Text("$rank", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted, modifier = Modifier.width(18.dp))
        }
        DeckIcon(deck.icons, size = 46.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(deck.name, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${deck.count} decks · best ${CardText.ordinal(deck.bestPlacing)}", fontSize = 12.sp, color = Palette.Muted)
        }
        Text(CardText.percent(deck.share), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        if (showChevron) Chevron()
    }
}

@Composable
fun TournamentRow(appearance: TournamentAppearance, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TrophyIcon(appearance.best?.placing)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(appearance.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(CardText.ordinal(appearance.best?.placing), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink.copy(alpha = 0.8f))
                Text(appearance.best?.deckName.orEmpty(), fontSize = 15.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "${Dates.short(appearance.date)} · ${appearance.players} players · ${CardText.percent(appearance.usage, 0)} usage",
                fontSize = 12.sp,
                color = Palette.Muted.copy(alpha = 0.85f),
            )
        }
        Spacer(Modifier.width(4.dp))
        Chevron()
    }
}

@Composable
fun FinisherRow(entry: PlayerEntry, modifier: Modifier = Modifier, copies: Int? = null, subtitle: String? = null, showChevron: Boolean = true) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlacingBadge(entry.placing)
        DeckIcon(entry.deckIcons, size = 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle ?: entry.deckName, fontSize = 15.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (copies != null && copies > 0) {
                Text("$copies× copies", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.Red)
            }
        }
        Text(entry.recordText, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        if (showChevron) Chevron()
    }
}
