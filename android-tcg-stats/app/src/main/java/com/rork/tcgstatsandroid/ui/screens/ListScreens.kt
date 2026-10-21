package com.rork.tcgstatsandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rork.tcgstatsandroid.data.PlayerEntry
import com.rork.tcgstatsandroid.data.StatsWindow
import com.rork.tcgstatsandroid.ui.AppViewModel
import com.rork.tcgstatsandroid.ui.components.CanvasBackground
import com.rork.tcgstatsandroid.ui.components.DeckRow
import com.rork.tcgstatsandroid.ui.components.DetailTopBar
import com.rork.tcgstatsandroid.ui.components.EmptyRow
import com.rork.tcgstatsandroid.ui.components.FinisherRow
import com.rork.tcgstatsandroid.ui.components.RowDivider
import com.rork.tcgstatsandroid.ui.components.TournamentRow
import com.rork.tcgstatsandroid.ui.components.cardSurface
import com.rork.tcgstatsandroid.ui.components.pressable
import com.rork.tcgstatsandroid.ui.navigation.AppActions
import com.rork.tcgstatsandroid.ui.theme.Palette

private val listPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)

/** Groups list rows into one white tile, rounding only the outer corners. */
private fun Modifier.groupedRow(index: Int, count: Int): Modifier {
    val top = if (index == 0) 20.dp else 0.dp
    val bottom = if (index == count - 1) 20.dp else 0.dp
    return this
        .fillMaxWidth()
        .background(Palette.Surface, RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
        .padding(horizontal = 16.dp)
}

@Composable
fun AllTournamentsScreen(vm: AppViewModel, cardKey: String, window: StatsWindow, actions: AppActions, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val appearances = remember(state.dataset, cardKey, window) {
        vm.stats(cardKey, window)?.appearances.orEmpty().sortedByDescending { it.date }
    }
    CanvasBackground(modifier) {
        Column(Modifier.fillMaxSize()) {
            DetailTopBar("Tournaments", onBack = actions.back)
            LazyColumn(Modifier.navigationBarsPadding(), contentPadding = listPadding) {
                itemsIndexed(appearances, key = { _, a -> a.tournamentId }) { index, appearance ->
                    Column(Modifier.groupedRow(index, appearances.size)) {
                        if (index > 0) RowDivider()
                        TournamentRow(appearance, Modifier.pressable { actions.openTournament(appearance.tournamentId, cardKey) })
                    }
                }
                item {
                    Text(
                        if (appearances.isEmpty()) "No tournaments in this period"
                        else "${appearances.size} tournaments where this card made a decklist",
                        fontSize = 13.sp,
                        color = Palette.Muted,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun AllDecksScreen(vm: AppViewModel, cardKey: String, window: StatsWindow, actions: AppActions, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val decks = remember(state.dataset, cardKey, window) { vm.stats(cardKey, window)?.topDecks.orEmpty() }
    CanvasBackground(modifier) {
        Column(Modifier.fillMaxSize()) {
            DetailTopBar("Decks", onBack = actions.back)
            LazyColumn(Modifier.navigationBarsPadding(), contentPadding = listPadding) {
                itemsIndexed(decks, key = { _, d -> d.name }) { index, deck ->
                    Column(Modifier.groupedRow(index, decks.size)) {
                        if (index > 0) RowDivider()
                        DeckRow(index + 1, deck, Modifier.pressable { actions.openDeck(cardKey, deck.name, window) })
                    }
                }
                if (decks.isEmpty()) item { EmptyRow("No decks in this period") }
            }
        }
    }
}

@Composable
fun DeckEntriesScreen(
    vm: AppViewModel,
    cardKey: String,
    deckName: String,
    window: StatsWindow,
    actions: AppActions,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val rows = remember(state.dataset, cardKey, deckName, window) {
        state.dataset?.entries(cardKey, deckName, window).orEmpty()
    }
    // Saveable so the decklist sheet reopens when returning from a card opened inside it.
    var selectedIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val selected = selectedIndex?.let { rows.getOrNull(it) }?.let { (tournament, entry) -> tournament.name to entry }
    CanvasBackground(modifier) {
        Column(Modifier.fillMaxSize()) {
            DetailTopBar(deckName, onBack = actions.back)
            LazyColumn(Modifier.navigationBarsPadding(), contentPadding = listPadding) {
                item {
                    Column(Modifier.fillMaxWidth().cardSurface().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        if (rows.isEmpty()) EmptyRow("No results")
                        rows.forEachIndexed { index, (tournament, entry) ->
                            if (index > 0) RowDivider(start = 56.dp)
                            FinisherRow(
                                entry,
                                Modifier.pressable { selectedIndex = index },
                                subtitle = tournament.name,
                            )
                        }
                    }
                }
            }
        }
    }
    selected?.let { (tournamentName, entry) ->
        DecklistSheet(entry, highlightKey = cardKey, tournamentName = tournamentName, onOpenCard = actions.openCard, onDismiss = { selectedIndex = null })
    }
}
