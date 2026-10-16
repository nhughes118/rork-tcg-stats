package com.rork.tcgstatsandroid.ui.navigation

import com.rork.tcgstatsandroid.data.StatsWindow

/** Navigation callbacks shared by every screen. */
class AppActions(
    val openCard: (cardKey: String) -> Unit,
    val openTournament: (id: String, cardKey: String) -> Unit,
    val openAllTournaments: (cardKey: String, window: StatsWindow) -> Unit,
    val openAllDecks: (cardKey: String, window: StatsWindow) -> Unit,
    val openDeck: (cardKey: String, deckName: String, window: StatsWindow) -> Unit,
    /** Selects a card, records it in history and shows the Results tab. */
    val showResults: (cardKey: String) -> Unit,
    val goToScan: () -> Unit,
    val back: () -> Unit,
)
