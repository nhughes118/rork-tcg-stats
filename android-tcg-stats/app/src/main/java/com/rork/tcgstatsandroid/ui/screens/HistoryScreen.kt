package com.rork.tcgstatsandroid.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rork.tcgstatsandroid.data.CardText
import com.rork.tcgstatsandroid.data.HistoryEntry
import com.rork.tcgstatsandroid.ui.AppViewModel
import com.rork.tcgstatsandroid.ui.components.CanvasBackground
import com.rork.tcgstatsandroid.ui.components.CardImage
import com.rork.tcgstatsandroid.ui.components.Chevron
import com.rork.tcgstatsandroid.ui.components.CircleIconButton
import com.rork.tcgstatsandroid.ui.components.SearchField
import com.rork.tcgstatsandroid.ui.components.cardSurface
import com.rork.tcgstatsandroid.ui.components.pressable
import com.rork.tcgstatsandroid.ui.navigation.AppActions
import com.rork.tcgstatsandroid.ui.theme.Palette

@Composable
fun HistoryScreen(vm: AppViewModel, actions: AppActions, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(state.history, query) {
        if (query.isBlank()) state.history else state.history.filter { it.card.name.contains(query.trim(), ignoreCase = true) }
    }

    CanvasBackground(modifier) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("History", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.weight(1f))
                if (state.history.isNotEmpty()) {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        CircleIconButton(Icons.Filled.MoreHoriz, label = "More options") { expanded = true }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Clear History", color = Palette.Red) },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = Palette.Red) },
                                onClick = {
                                    expanded = false
                                    vm.clearHistory()
                                },
                            )
                        }
                    }
                }
            }
            if (state.history.isNotEmpty()) {
                SearchField(query, { query = it }, "Search your scans", Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
            when {
                state.history.isEmpty() -> EmptyHistory(onScan = actions.goToScan)
                filtered.isEmpty() -> Text(
                    "No results for \"$query\"",
                    fontSize = 15.sp,
                    color = Palette.Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(filtered, key = { it.card.key }) { entry ->
                        SwipeableHistoryRow(
                            entry = entry,
                            onOpen = { actions.openCard(entry.card.key) },
                            onDelete = { vm.deleteHistory(entry.card.key) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeableHistoryRow(entry: HistoryEntry, onOpen: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) onDelete()
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        modifier = modifier,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(Palette.Red, RoundedCornerShape(20.dp)).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color.White)
            }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .cardSurface()
                .pressable(onClick = onOpen)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CardImage(entry.card, Modifier.width(54.dp).shadow(3.dp, RoundedCornerShape(5.dp)), cornerRadius = 5.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.card.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                Text(
                    "Scanned ${DateUtils.getRelativeTimeSpanString(entry.scannedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}",
                    fontSize = 15.sp,
                    color = Palette.Muted,
                )
            }
            Text(CardText.percent(entry.usage), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            Chevron()
        }
    }
}

@Composable
private fun EmptyHistory(onScan: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 120.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.DocumentScanner, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(48.dp))
        Text("No scans yet", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        Text("Cards you scan or look up will show up here.", fontSize = 15.sp, color = Palette.Muted, textAlign = TextAlign.Center)
        Button(onClick = onScan, colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue), modifier = Modifier.padding(top = 6.dp)) {
            Text("Scan a Card", fontWeight = FontWeight.SemiBold)
        }
    }
}
