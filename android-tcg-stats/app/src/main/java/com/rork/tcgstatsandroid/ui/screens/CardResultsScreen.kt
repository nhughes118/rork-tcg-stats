package com.rork.tcgstatsandroid.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rork.tcgstatsandroid.data.CardIdentity
import com.rork.tcgstatsandroid.data.CardStats
import com.rork.tcgstatsandroid.data.CardText
import com.rork.tcgstatsandroid.data.DataSource
import com.rork.tcgstatsandroid.data.LimitlessClient
import com.rork.tcgstatsandroid.data.StatsWindow
import com.rork.tcgstatsandroid.ui.AppViewModel
import com.rork.tcgstatsandroid.ui.LoadState
import com.rork.tcgstatsandroid.ui.components.BrandHeader
import com.rork.tcgstatsandroid.ui.components.CanvasBackground
import com.rork.tcgstatsandroid.ui.components.CardImage
import com.rork.tcgstatsandroid.ui.components.CircleIconButton
import com.rork.tcgstatsandroid.ui.components.DeckRow
import com.rork.tcgstatsandroid.ui.components.DetailTopBar
import com.rork.tcgstatsandroid.ui.components.EmptyRow
import com.rork.tcgstatsandroid.ui.components.LoadingCard
import com.rork.tcgstatsandroid.ui.components.RowDivider
import com.rork.tcgstatsandroid.ui.components.SectionHeader
import com.rork.tcgstatsandroid.ui.components.SegmentedPicker
import com.rork.tcgstatsandroid.ui.components.SourcePicker
import com.rork.tcgstatsandroid.ui.components.TournamentRow
import com.rork.tcgstatsandroid.ui.components.UsageRing
import com.rork.tcgstatsandroid.ui.components.cardSurface
import com.rork.tcgstatsandroid.ui.components.extendStart
import com.rork.tcgstatsandroid.ui.components.pressable
import com.rork.tcgstatsandroid.ui.navigation.AppActions
import com.rork.tcgstatsandroid.ui.theme.Palette
import java.util.Locale

/** Card usage, trend, top decks and tournament results. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardResultsScreen(
    vm: AppViewModel,
    cardKey: String,
    isTabRoot: Boolean,
    actions: AppActions,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var window by rememberSaveable { mutableStateOf(StatsWindow.EightWeeks) }
    val dataset = state.dataset
    val stats = remember(dataset, cardKey, window) { vm.stats(cardKey, window) }

    CanvasBackground(modifier) {
        Column(Modifier.fillMaxSize()) {
            if (!isTabRoot) {
                DetailTopBar(title = stats?.card?.name ?: "Card", onBack = actions.back) {
                    ResultsMenu(stats, actions, onRefresh = { vm.load(force = true) })
                    Spacer(Modifier.width(8.dp))
                }
            }
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { vm.load(force = true) },
                modifier = Modifier.weight(1f),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .then(if (isTabRoot) Modifier.statusBarsPadding() else Modifier.navigationBarsPadding())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (isTabRoot) {
                        BrandHeader(Modifier.padding(top = 8.dp)) {
                            ResultsMenu(stats, actions, onRefresh = { vm.load(force = true) })
                        }
                        SourcePicker(state.source, onSelect = vm::selectSource)
                    }
                    if (stats != null) {
                        HeroSection(stats)
                        TrendSection(stats, window, onWindow = { window = it })
                        val key = stats.card.key
                        TopDecksSection(stats, onSeeAll = { actions.openAllDecks(key, window) }, onDeck = { actions.openDeck(key, it, window) })
                        TournamentsSection(
                            stats = stats,
                            isOfficial = state.source == DataSource.Official,
                            onSeeAll = { actions.openAllTournaments(key, window) },
                            onOpen = { actions.openTournament(it, key) },
                        )
                        ResultsFooter(stats, state.source)
                    } else {
                        ResultsPlaceholder(
                            card = vm.identity(cardKey),
                            loadState = state.loadState,
                            onRetry = { vm.load(force = true) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultsMenu(stats: CardStats?, actions: AppActions, onRefresh: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    Box {
        CircleIconButton(Icons.Filled.MoreHoriz, label = "More") { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Scan another card") },
                leadingIcon = { Icon(Icons.Filled.DocumentScanner, contentDescription = null) },
                onClick = {
                    expanded = false
                    actions.goToScan()
                },
            )
            if (stats != null) {
                DropdownMenuItem(
                    text = { Text("Open on Limitless TCG") },
                    leadingIcon = { Icon(Icons.Filled.Language, contentDescription = null) },
                    onClick = {
                        expanded = false
                        runCatching { uriHandler.openUri(stats.card.limitlessUrl) }
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Refresh data") },
                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                onClick = {
                    expanded = false
                    onRefresh()
                },
            )
        }
    }
}

@Composable
private fun HeroSection(stats: CardStats) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val spec = spring<Float>(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow)
    val rotation by animateFloatAsState(if (appeared) -6f else 0f, spec, label = "heroRotation")
    val scale by animateFloatAsState(if (appeared) 1f else 0.92f, spec, label = "heroScale")

    Row(verticalAlignment = Alignment.Top) {
        CardImage(
            card = stats.card,
            large = true,
            cornerRadius = 12.dp,
            modifier = Modifier
                .zIndex(1f)
                .padding(top = 8.dp, start = 6.dp)
                .width(150.dp)
                .graphicsLayer {
                    rotationZ = rotation
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(14.dp, RoundedCornerShape(12.dp)),
        )
        Column(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stats.card.name, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 2)
                Text(stats.card.printLabel, fontSize = 15.sp, color = Palette.Muted)
            }
            Row(
                Modifier
                    .extendStart(12.dp)
                    .fillMaxWidth()
                    .cardSurface(24.dp)
                    .padding(start = 28.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                UsageRing(stats.usage, Modifier.size(104.dp), lineWidth = 10.dp, fontSize = 20.sp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    stats.delta?.let { delta ->
                        val up = delta >= 0
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (up) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                                contentDescription = null,
                                tint = if (up) Palette.Green else Palette.Red,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                "${if (up) "+" else ""}${CardText.percent(delta)}",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (up) Palette.Green else Palette.Red,
                            )
                        }
                        Text("vs last month", fontSize = 12.sp, color = Palette.Muted)
                    }
                    Text(
                        "${String.format(Locale.US, "%.1f", stats.averageCopies)} avg copies",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Palette.Blue,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrendSection(stats: CardStats, window: StatsWindow, onWindow: (StatsWindow) -> Unit) {
    Column(
        Modifier.fillMaxWidth().cardSurface().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Usage Trend", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.weight(1f))
            SegmentedPicker(
                options = StatsWindow.entries,
                selected = window,
                label = { it.label },
                onSelect = onWindow,
                modifier = Modifier.width(180.dp),
            )
        }
        if (stats.trend.size >= 2) {
            TrendChart(stats.trend, Modifier.fillMaxWidth().height(170.dp))
        } else {
            EmptyRow("Not enough weeks of data for a trend yet.", Modifier.height(80.dp))
        }
    }
}

@Composable
private fun TopDecksSection(stats: CardStats, onSeeAll: () -> Unit, onDeck: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().cardSurface().padding(16.dp)) {
        SectionHeader("Top Decks", Modifier.padding(bottom = 6.dp), onSeeAll = if (stats.topDecks.size > 3) onSeeAll else null)
        if (stats.topDecks.isEmpty()) EmptyRow("No decks in this period")
        stats.topDecks.take(3).forEachIndexed { index, deck ->
            if (index > 0) RowDivider()
            DeckRow(index + 1, deck, Modifier.pressable { onDeck(deck.name) })
        }
    }
}

@Composable
private fun TournamentsSection(stats: CardStats, isOfficial: Boolean, onSeeAll: () -> Unit, onOpen: (String) -> Unit) {
    val ordered = if (isOfficial) stats.appearances.sortedByDescending { it.date } else stats.topAppearances
    val top = ordered.take(4)
    Column(Modifier.fillMaxWidth().cardSurface().padding(16.dp)) {
        SectionHeader(
            if (isOfficial) "Recent Tournament Results" else "Top Tournament Results",
            Modifier.padding(bottom = 6.dp),
            onSeeAll = if (stats.appearances.size > top.size) onSeeAll else null,
        )
        if (top.isEmpty()) EmptyRow("Not played in tournaments this period")
        top.forEachIndexed { index, appearance ->
            if (index > 0) RowDivider()
            TournamentRow(appearance, Modifier.pressable { onOpen(appearance.tournamentId) })
        }
    }
}

@Composable
private fun ResultsFooter(stats: CardStats, source: DataSource) {
    val scope = if (source == DataSource.Official) {
        "top-${LimitlessClient.OFFICIAL_LIST_LIMIT} decklists from ${stats.tournamentCount} official tournaments"
    } else {
        "${stats.totalDecks} decklists from ${stats.tournamentCount} Standard tournaments"
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Based on $scope", fontSize = 12.sp, color = Palette.Muted, textAlign = TextAlign.Center)
        Text("Data from Limitless TCG · ${source.label}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted)
    }
}

@Composable
private fun ResultsPlaceholder(card: CardIdentity?, loadState: LoadState, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (card != null) {
            CardImage(
                card,
                large = true,
                cornerRadius = 12.dp,
                modifier = Modifier.width(140.dp).graphicsLayer { rotationZ = -5f }.shadow(12.dp, RoundedCornerShape(12.dp)),
            )
            Text(card.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        }
        when (loadState) {
            is LoadState.Failed -> {
                Text(loadState.message, fontSize = 15.sp, color = Palette.Muted, textAlign = TextAlign.Center)
                Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue)) { Text("Try Again") }
            }
            LoadState.Loaded -> Text(
                "This card hasn't appeared in recent tournament decklists.",
                fontSize = 15.sp,
                color = Palette.Muted,
                textAlign = TextAlign.Center,
            )
            is LoadState.Loading -> LoadingCard(loadState.progress)
            LoadState.Idle -> LoadingCard(0.0)
        }
    }
}
