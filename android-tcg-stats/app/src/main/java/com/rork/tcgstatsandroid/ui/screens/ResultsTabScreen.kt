package com.rork.tcgstatsandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rork.tcgstatsandroid.data.CardCategory
import com.rork.tcgstatsandroid.data.CardText
import com.rork.tcgstatsandroid.ui.AppViewModel
import com.rork.tcgstatsandroid.ui.LoadState
import com.rork.tcgstatsandroid.ui.components.BrandHeader
import com.rork.tcgstatsandroid.ui.components.CanvasBackground
import com.rork.tcgstatsandroid.ui.components.CardImage
import com.rork.tcgstatsandroid.ui.components.LoadingCard
import com.rork.tcgstatsandroid.ui.components.SourcePicker
import com.rork.tcgstatsandroid.ui.components.cardSurface
import com.rork.tcgstatsandroid.ui.components.pressable
import com.rork.tcgstatsandroid.ui.navigation.AppActions
import com.rork.tcgstatsandroid.ui.theme.Palette

/** Home tab: results for the last scanned card, or an onboarding state. */
@Composable
fun ResultsTabScreen(vm: AppViewModel, actions: AppActions, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    var isSearching by rememberSaveable { mutableStateOf(false) }
    val key = state.currentCardKey
    if (key != null) {
        androidx.compose.runtime.key(key) {
            CardResultsScreen(vm, key, isTabRoot = true, actions = actions, modifier = modifier)
        }
    } else {
        EmptyResults(vm, actions, onSearch = { isSearching = true }, modifier = modifier)
    }
    if (isSearching) {
        CardSearchSheet(vm, onDismiss = { isSearching = false }, onSelect = { actions.showResults(it) })
    }
}

@Composable
private fun EmptyResults(vm: AppViewModel, actions: AppActions, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    CanvasBackground(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                BrandHeader(Modifier.padding(top = 8.dp))
                SourcePicker(state.source, onSelect = vm::selectSource)
                Column(
                    Modifier.fillMaxWidth().cardSurface(24.dp).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Scan a card to see how it's performing", fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                    Text("Usage, trends and top finishes from recent tournaments on Limitless TCG.", fontSize = 17.sp, color = Palette.Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Scan Card", Icons.Filled.DocumentScanner, Palette.Blue, Color.White, Modifier.weight(1f), actions.goToScan)
                        PillButton("Search", Icons.Filled.Search, Palette.Blue.copy(alpha = 0.1f), Palette.Blue, Modifier.weight(1f), onSearch)
                    }
                }
            }
            val popular = state.popularCards.filter { it.card.category != CardCategory.Energy }.take(12)
            if (popular.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    when (val ls = state.loadState) {
                        is LoadState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(ls.message, fontSize = 15.sp, color = Palette.Muted)
                            Button(onClick = { vm.load(force = true) }, colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue)) { Text("Try Again") }
                        }
                        is LoadState.Loading -> LoadingCard(ls.progress)
                        else -> LoadingCard(0.0)
                    }
                }
            } else {
                Text("Most played right now", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.padding(horizontal = 16.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(popular, key = { it.card.key }) { item ->
                        Column(
                            Modifier.width(110.dp).pressable { actions.showResults(item.card.key) },
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CardImage(item.card, Modifier.fillMaxWidth().shadow(6.dp, RoundedCornerShape(8.dp)), cornerRadius = 8.dp)
                            Text(item.card.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(CardText.percent(item.usage), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.Red)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PillButton(
    label: String,
    icon: ImageVector,
    background: Color,
    content: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .heightIn(min = 50.dp)
            .background(background, CircleShape)
            .pressable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = content, maxLines = 1)
    }
}
