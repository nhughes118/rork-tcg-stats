package com.rork.tcgstatsandroid.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.rork.tcgstatsandroid.data.StatsWindow
import com.rork.tcgstatsandroid.ui.AppViewModel
import com.rork.tcgstatsandroid.ui.scan.ScanScreen
import com.rork.tcgstatsandroid.ui.screens.AllDecksScreen
import com.rork.tcgstatsandroid.ui.screens.AllTournamentsScreen
import com.rork.tcgstatsandroid.ui.screens.CardResultsScreen
import com.rork.tcgstatsandroid.ui.screens.DeckEntriesScreen
import com.rork.tcgstatsandroid.ui.screens.HistoryScreen
import com.rork.tcgstatsandroid.ui.screens.ResultsTabScreen
import com.rork.tcgstatsandroid.ui.screens.TournamentScreen
import com.rork.tcgstatsandroid.ui.theme.Palette
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable object ScanRoute
@Serializable object ResultsRoute
@Serializable object HistoryRoute
@Serializable data class CardRoute(val key: String)
@Serializable data class TournamentRoute(val id: String, val cardKey: String)
@Serializable data class AllTournamentsRoute(val cardKey: String, val window: String)
@Serializable data class AllDecksRoute(val cardKey: String, val window: String)
@Serializable data class DeckRoute(val cardKey: String, val deckName: String, val window: String)

private data class Tab(val route: Any, val routeClass: KClass<*>, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(ScanRoute, ScanRoute::class, "Scan", Icons.Filled.DocumentScanner),
    Tab(ResultsRoute, ResultsRoute::class, "Results", Icons.Filled.BarChart),
    Tab(HistoryRoute, HistoryRoute::class, "History", Icons.Filled.History),
)

private fun windowOf(name: String): StatsWindow = StatsWindow.entries.firstOrNull { it.name == name } ?: StatsWindow.EightWeeks

private fun NavHostController.switchTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun AppNavigation(vm: AppViewModel = viewModel()) {
    val navController = rememberNavController()
    val haptic = LocalHapticFeedback.current
    val startRoute: Any = remember { if (vm.startsOnScan) ScanRoute else ResultsRoute }
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val currentTab = tabs.firstOrNull { tab -> destination?.hasRoute(tab.routeClass) == true }

    val actions = remember(navController) {
        AppActions(
            openCard = { navController.navigate(CardRoute(it)) },
            openTournament = { id, key -> navController.navigate(TournamentRoute(id, key)) },
            openAllTournaments = { key, w -> navController.navigate(AllTournamentsRoute(key, w.name)) },
            openAllDecks = { key, w -> navController.navigate(AllDecksRoute(key, w.name)) },
            openDeck = { key, deck, w -> navController.navigate(DeckRoute(key, deck, w.name)) },
            showResults = { key ->
                if (vm.showResults(key)) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    navController.navigate(ResultsRoute) {
                        popUpTo(navController.graph.findStartDestination().id)
                        launchSingleTop = true
                    }
                }
            },
            goToScan = { navController.switchTab(ScanRoute) },
            back = { navController.popBackStack() },
        )
    }

    Scaffold(
        containerColor = Palette.Canvas,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (currentTab != null) {
                NavigationBar(containerColor = Palette.Surface, tonalElevation = 0.dp) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == currentTab,
                            onClick = { if (tab != currentTab) navController.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Palette.Red,
                                selectedTextColor = Palette.Red,
                                indicatorColor = Palette.Red.copy(alpha = 0.12f),
                                unselectedIconColor = Palette.Muted,
                                unselectedTextColor = Palette.Muted,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startRoute,
            modifier = Modifier.fillMaxSize().padding(bottom = innerPadding.calculateBottomPadding()),
            enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() },
        ) {
            composable<ScanRoute>(enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) {
                ScanScreen(vm, actions)
            }
            composable<ResultsRoute>(enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) {
                ResultsTabScreen(vm, actions)
            }
            composable<HistoryRoute>(enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) {
                HistoryScreen(vm, actions)
            }
            composable<CardRoute> { entry ->
                CardResultsScreen(vm, entry.toRoute<CardRoute>().key, isTabRoot = false, actions = actions)
            }
            composable<TournamentRoute> { entry ->
                val route = entry.toRoute<TournamentRoute>()
                TournamentScreen(vm, route.id, route.cardKey, actions)
            }
            composable<AllTournamentsRoute> { entry ->
                val route = entry.toRoute<AllTournamentsRoute>()
                AllTournamentsScreen(vm, route.cardKey, windowOf(route.window), actions)
            }
            composable<AllDecksRoute> { entry ->
                val route = entry.toRoute<AllDecksRoute>()
                AllDecksScreen(vm, route.cardKey, windowOf(route.window), actions)
            }
            composable<DeckRoute> { entry ->
                val route = entry.toRoute<DeckRoute>()
                DeckEntriesScreen(vm, route.cardKey, route.deckName, windowOf(route.window), actions)
            }
        }
    }
}
