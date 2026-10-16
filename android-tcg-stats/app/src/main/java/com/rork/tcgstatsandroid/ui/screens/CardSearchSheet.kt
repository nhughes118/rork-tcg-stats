package com.rork.tcgstatsandroid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rork.tcgstatsandroid.data.CardText
import com.rork.tcgstatsandroid.ui.AppViewModel
import com.rork.tcgstatsandroid.ui.LoadState
import com.rork.tcgstatsandroid.ui.components.CardImage
import com.rork.tcgstatsandroid.ui.components.LoadingCard
import com.rork.tcgstatsandroid.ui.components.RowDivider
import com.rork.tcgstatsandroid.ui.components.SearchField
import com.rork.tcgstatsandroid.ui.components.pressable
import com.rork.tcgstatsandroid.ui.theme.Palette

/** Manual lookup of any card seen in recent tournament decklists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardSearchSheet(vm: AppViewModel, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    val all = state.popularCards
    val results = remember(all, query) {
        if (query.isBlank()) all.take(60) else all.filter { it.card.name.contains(query.trim(), ignoreCase = true) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Palette.Canvas) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Close", color = Palette.Blue, fontSize = 17.sp) }
                Text(
                    if (query.isEmpty()) "Most Played Cards" else "Search",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.Ink,
                    modifier = Modifier.weight(1f).padding(end = 72.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            SearchField(query, { query = it }, "Card name", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Box(Modifier.weight(1f)) {
                when {
                    all.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                        when (val ls = state.loadState) {
                            is LoadState.Failed -> Text("Couldn't load data. ${ls.message}", color = Palette.Muted, fontSize = 15.sp)
                            is LoadState.Loading -> LoadingCard(ls.progress)
                            else -> LoadingCard(0.0)
                        }
                    }
                    results.isEmpty() -> Text(
                        "No results for \"$query\"",
                        color = Palette.Muted,
                        fontSize = 15.sp,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp),
                    )
                    else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                        items(results, key = { it.card.key }) { item ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .pressable {
                                        onSelect(item.card.key)
                                        onDismiss()
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CardImage(item.card, Modifier.width(40.dp), cornerRadius = 4.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(item.card.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                                    Text(item.card.printLabel, fontSize = 12.sp, color = Palette.Muted)
                                }
                                Text(CardText.percent(item.usage), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                            }
                            RowDivider(start = 52.dp)
                        }
                    }
                }
            }
        }
    }
}
