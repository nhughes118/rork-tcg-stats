package com.rork.tcgstatsandroid.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rork.tcgstatsandroid.data.CardCategory
import com.rork.tcgstatsandroid.data.CardIdentity
import com.rork.tcgstatsandroid.data.CardText
import com.rork.tcgstatsandroid.data.PlayerEntry
import com.rork.tcgstatsandroid.ui.components.CardImage
import com.rork.tcgstatsandroid.ui.components.DeckIcon
import com.rork.tcgstatsandroid.ui.components.cardSurface
import com.rork.tcgstatsandroid.ui.components.pressable
import com.rork.tcgstatsandroid.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Full decklist with the scanned card highlighted, plus copy/share export. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecklistSheet(
    entry: PlayerEntry,
    highlightKey: String,
    tournamentName: String?,
    onOpenCard: (cardKey: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var isOpeningCard by remember { mutableStateOf(false) }
    // Like iOS: slide the sheet down first, then open the card. The host keeps the
    // selection saved, so the sheet slides back up when the user comes back.
    val openCard: (String) -> Unit = { key ->
        if (!isOpeningCard) {
            isOpeningCard = true
            scope.launch { sheetState.hide() }.invokeOnCompletion { onOpenCard(key) }
        }
    }
    var copyCount by remember { mutableStateOf(0) }
    var didCopy by remember { mutableStateOf(false) }
    LaunchedEffect(copyCount) {
        if (copyCount == 0) return@LaunchedEffect
        didCopy = true
        delay(2000)
        didCopy = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Palette.Canvas) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { shareDecklist(context, entry) }, enabled = entry.cards.isNotEmpty()) {
                Icon(Icons.Filled.Share, contentDescription = "Share decklist", tint = Palette.Blue)
            }
            Text(
                entry.name,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = Palette.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Done", color = Palette.Blue, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) }
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(Modifier.fillMaxWidth().cardSurface().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DeckIcon(entry.deckIcons, size = 52.dp)
                        Column(Modifier.weight(1f)) {
                            Text(entry.deckName, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                            Text(
                                "${CardText.ordinal(entry.placing)} · ${entry.recordText}${tournamentName?.let { " · $it" } ?: ""}",
                                fontSize = 15.sp,
                                color = Palette.Muted,
                            )
                        }
                    }
                    CopyDecklistButton(didCopy = didCopy, enabled = entry.cards.isNotEmpty()) {
                        copyToClipboard(context, entry.exportText)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        copyCount += 1
                    }
                }
            }
            CardCategory.entries.forEach { category ->
                val cards = entry.cards.filter { it.category == category }
                if (cards.isEmpty()) return@forEach
                item(key = category.name) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "${category.title} · ${cards.sumOf { it.count }}".uppercase(),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Palette.Muted,
                            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
                        )
                        Column(Modifier.fillMaxWidth().cardSurface(16.dp)) {
                            cards.forEach { card ->
                                val highlighted = card.key == highlightKey
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(if (highlighted) Palette.Red.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent)
                                        .pressable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            openCard(card.key)
                                        }
                                        .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        "${card.count}",
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (highlighted) Palette.Red else Palette.Ink,
                                        modifier = Modifier.width(22.dp),
                                    )
                                    CardImage(CardIdentity(card.name, card.set, card.number, card.category), Modifier.width(30.dp), cornerRadius = 3.dp)
                                    Text(
                                        card.name,
                                        fontSize = 17.sp,
                                        fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
                                        color = if (highlighted) Palette.Red else Palette.Ink,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text("${card.set} ${card.number}".trim(), fontSize = 12.sp, color = Palette.Muted)
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = "Open ${card.name} results",
                                        tint = Palette.Muted.copy(alpha = 0.6f),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyDecklistButton(didCopy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(if (didCopy) Palette.Blue else Palette.Red, label = "copyColor")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (enabled) color else color.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .pressable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        AnimatedContent(
            targetState = didCopy,
            transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.8f)) togetherWith fadeOut() },
            label = "copyLabel",
        ) { copied ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (copied) Icons.Filled.CheckCircle else Icons.Filled.ContentCopy,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    if (copied) "Copied to Clipboard" else "Copy Decklist",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = androidx.compose.ui.graphics.Color.White,
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Decklist", text))
}

private fun shareDecklist(context: Context, entry: PlayerEntry) {
    val text = "${entry.deckName} — ${entry.name}\n\n${entry.exportText}"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share decklist")) }
}
