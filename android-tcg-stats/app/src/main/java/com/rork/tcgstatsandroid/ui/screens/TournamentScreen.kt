package com.rork.tcgstatsandroid.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rork.tcgstatsandroid.data.CardText
import com.rork.tcgstatsandroid.data.PlayerEntry
import com.rork.tcgstatsandroid.ui.AppViewModel
import com.rork.tcgstatsandroid.ui.components.CanvasBackground
import com.rork.tcgstatsandroid.ui.components.Dates
import com.rork.tcgstatsandroid.ui.components.DetailTopBar
import com.rork.tcgstatsandroid.ui.components.FinisherRow
import com.rork.tcgstatsandroid.ui.components.EmptyRow
import com.rork.tcgstatsandroid.ui.components.RowDivider
import com.rork.tcgstatsandroid.ui.components.SegmentedPicker
import com.rork.tcgstatsandroid.ui.components.TrophyIcon
import com.rork.tcgstatsandroid.ui.components.UsageRing
import com.rork.tcgstatsandroid.ui.components.cardSurface
import com.rork.tcgstatsandroid.ui.components.pressable
import com.rork.tcgstatsandroid.ui.navigation.AppActions
import com.rork.tcgstatsandroid.ui.theme.Palette

@Composable
fun TournamentScreen(vm: AppViewModel, tournamentId: String, cardKey: String, actions: AppActions, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tournament = remember(state.dataset, tournamentId) { state.dataset?.tournament(tournamentId) }
    val card = vm.identity(cardKey)
    // Saveable so the decklist sheet reopens when returning from a card opened inside it.
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = remember(tournament, selectedId) { selectedId?.let { id -> tournament?.entries?.firstOrNull { it.id == id } } }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var scope by rememberSaveable { mutableStateOf(ResultsScope.WithCard) }
    var query by rememberSaveable { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current

    CanvasBackground(modifier) {
        Column(Modifier.fillMaxSize()) {
            DetailTopBar(tournament?.name ?: "Tournament", onBack = actions.back)
            if (tournament == null) {
                Column(
                    Modifier.fillMaxSize().padding(top = 80.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(44.dp))
                    Text("Tournament unavailable", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                    Text("Pull to refresh tournament data.", fontSize = 15.sp, color = Palette.Muted)
                }
                return@Column
            }
            val users = remember(tournament, cardKey) {
                tournament.entries.filter { cardKey in it.cardKeys }.sortedBy { it.placing ?: Int.MAX_VALUE }
            }
            val usage = users.size.toDouble() / maxOf(tournament.entries.size, 1)
            val everyone = remember(tournament) { tournament.entries.sortedBy { it.placing ?: Int.MAX_VALUE } }
            val base = if (scope == ResultsScope.WithCard) users else everyone
            val filtered = remember(base, query) {
                val tokens = CardText.key(query).split(" ").filter { it.isNotEmpty() }
                if (tokens.isEmpty()) base
                else base.filter { e -> val hay = CardText.key("${e.name} ${e.deckName}"); tokens.all { hay.contains(it) } }
            }
            val visible = if (showAll || query.isNotBlank()) filtered else filtered.take(16)

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TrophyIcon(users.firstOrNull()?.placing, Modifier.size(48.dp), size = 40.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(tournament.name, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                        Text(Dates.medium(tournament.date), fontSize = 15.sp, color = Palette.Muted)
                        Text("${tournament.source.label} · Standard", fontSize = 15.sp, color = Palette.Muted)
                    }
                }

                Row(
                    Modifier.fillMaxWidth().cardSurface().padding(vertical = 16.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Groups, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(26.dp))
                        Column {
                            Text("${tournament.players}", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                            Text("players", fontSize = 15.sp, color = Palette.Muted)
                        }
                    }
                    VerticalDivider(Modifier.height(60.dp), color = Palette.Hairline)
                    Row(
                        Modifier.weight(1f).padding(start = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        UsageRing(usage, Modifier.size(84.dp), lineWidth = 8.dp, caption = null, fontSize = 15.sp)
                        Column(Modifier.weight(1f)) {
                            Text(card?.name ?: "This card", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, maxLines = 2)
                            Text("in ${users.size} of ${tournament.entries.size} decks", fontSize = 12.sp, color = Palette.Muted)
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (scope == ResultsScope.WithCard) "Top Finishers Using This Card" else "All Results",
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Ink,
                    )
                    SegmentedPicker(
                        options = ResultsScope.entries.toList(),
                        selected = scope,
                        label = { if (it == ResultsScope.WithCard) "With this card · ${users.size}" else "All decks · ${everyone.size}" },
                        onSelect = { scope = it; showAll = false },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (scope == ResultsScope.All) {
                        ResultsSearchField(query, onQuery = { query = it })
                        if (tournament.players > everyone.size) {
                            Text(
                                "${everyone.size} of ${tournament.players} players published a decklist.",
                                fontSize = 12.sp, color = Palette.Muted,
                            )
                        }
                    }
                }

                Column(Modifier.fillMaxWidth().cardSurface().padding(horizontal = 16.dp, vertical = 6.dp).animateContentSize()) {
                    if (visible.isEmpty()) {
                        EmptyRow(if (query.isNotBlank()) "No player or deck matches “$query”." else "No decklists for this event.")
                    }
                    visible.forEachIndexed { index, entry ->
                        if (index > 0) RowDivider(start = 56.dp)
                        FinisherRow(entry, Modifier.pressable { selectedId = entry.id }, copies = entry.copies(cardKey))
                    }
                    if (filtered.size > visible.size) {
                        RowDivider()
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).pressable { showAll = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (scope == ResultsScope.WithCard) "Show all ${filtered.size} players" else "Show all ${filtered.size} decks",
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Blue,
                            )
                        }
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .pressable { runCatching { uriHandler.openUri(tournament.url) } },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = Palette.Blue, modifier = Modifier.size(18.dp))
                    Text("  Full standings on Limitless", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Blue)
                }
            }
        }
    }

    selected?.let { entry ->
        DecklistSheet(entry, highlightKey = cardKey, tournamentName = tournament?.name, onOpenCard = actions.openCard, onDismiss = { selectedId = null })
    }
}

private enum class ResultsScope { WithCard, All }

@Composable
private fun ResultsSearchField(query: String, onQuery: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Color(0x1F767680), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Palette.Red, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search player or deck", fontSize = 16.sp, color = Palette.Muted)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, color = Palette.Ink),
                cursorBrush = SolidColor(Palette.Red),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Clear",
                tint = Palette.Muted,
                modifier = Modifier.size(20.dp).pressable { onQuery("") },
            )
        }
    }
}
