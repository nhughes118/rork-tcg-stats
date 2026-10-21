package com.rork.tcgstatsandroid.ui.screens

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rork.tcgstatsandroid.data.Division
import com.rork.tcgstatsandroid.data.Leaderboard
import com.rork.tcgstatsandroid.data.LeaderboardException
import com.rork.tcgstatsandroid.data.LeaderboardIndex
import com.rork.tcgstatsandroid.data.LeaderboardPlayer
import com.rork.tcgstatsandroid.ui.components.CanvasBackground
import com.rork.tcgstatsandroid.ui.components.Chevron
import com.rork.tcgstatsandroid.ui.components.SegmentedPicker
import com.rork.tcgstatsandroid.ui.components.cardSurface
import com.rork.tcgstatsandroid.ui.components.pressable
import com.rork.tcgstatsandroid.ui.theme.Palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

// ---------- State ----------

sealed interface LeaderboardSync {
    data object Idle : LeaderboardSync
    data class Loading(val loaded: Int, val total: Int) : LeaderboardSync
    data class Ready(val fetchedAt: Long, val complete: Boolean) : LeaderboardSync
    data class Failed(val message: String) : LeaderboardSync
}

data class PlayersUiState(
    val query: String = "",
    val division: Division = Division.Masters,
    val sync: LeaderboardSync = LeaderboardSync.Idle,
    val players: List<LeaderboardPlayer> = emptyList(),
    val total: Int = 0,
    val results: List<LeaderboardPlayer> = emptyList(),
    val me: LeaderboardPlayer? = null,
)

class PlayersViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("tcg_stats", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        PlayersUiState(
            query = prefs.getString(KEY_QUERY, "") ?: "",
            division = Division.entries.firstOrNull { it.name == prefs.getString(KEY_DIVISION, null) } ?: Division.Masters,
        )
    )
    val state: StateFlow<PlayersUiState> = _state
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var normalized: List<String> = emptyList()
    private var cacheIndex: LeaderboardIndex? = null

    init {
        viewModelScope.launch { restoreCache(_state.value.division) }
    }

    private suspend fun restoreCache(division: Division) {
        val cached = Leaderboard.cached(getApplication(), division) ?: return
        if (_state.value.division != division) return
        cacheIndex = cached
        applyPlayers(cached.players, cached.total)
        _state.update { it.copy(sync = LeaderboardSync.Ready(cached.fetchedAt, cached.complete)) }
    }

    private suspend fun applyPlayers(players: List<LeaderboardPlayer>, total: Int) {
        val norms = withContext(Dispatchers.Default) { players.map { Leaderboard.normalize(it.name) } }
        normalized = norms
        _state.update { it.copy(players = players, total = total) }
        resolveMe()
        recompute(immediate = true)
    }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        prefs.edit().putString(KEY_QUERY, q).apply()
        recompute(immediate = false)
    }

    fun setDivision(division: Division) {
        if (division == _state.value.division) return
        loadJob?.cancel()
        cacheIndex = null
        normalized = emptyList()
        prefs.edit().putString(KEY_DIVISION, division.name).apply()
        _state.update { it.copy(division = division, players = emptyList(), results = emptyList(), total = 0, me = null, sync = LeaderboardSync.Idle) }
        viewModelScope.launch { restoreCache(division) }
    }

    /** Loads the division if there's no fresh cache. */
    fun ensureLoaded(activity: Activity) {
        if (loadJob?.isActive == true) return
        viewModelScope.launch {
            val division = _state.value.division
            val cached = cacheIndex ?: Leaderboard.cached(getApplication(), division)?.also { cacheIndex = it }
            if (cached != null && cached.division == division.slug && Leaderboard.isFresh(cached)) {
                if (_state.value.players.isEmpty()) {
                    applyPlayers(cached.players, cached.total)
                    _state.update { it.copy(sync = LeaderboardSync.Ready(cached.fetchedAt, cached.complete)) }
                }
                return@launch
            }
            refresh(activity)
        }
    }

    fun refresh(activity: Activity) {
        loadJob?.cancel()
        val division = _state.value.division
        val hadPlayers = _state.value.players.isNotEmpty()
        _state.update { it.copy(sync = LeaderboardSync.Loading(0, it.total)) }
        loadJob = viewModelScope.launch {
            var lastEmit = 0L
            try {
                val index = Leaderboard.load(activity, division) { collected, total ->
                    _state.update { it.copy(sync = LeaderboardSync.Loading(collected.size, total)) }
                    val now = System.currentTimeMillis()
                    // Stream partial results only when there's nothing cached to show yet.
                    if (!hadPlayers && (now - lastEmit > 700 || collected.size >= total)) {
                        lastEmit = now
                        val sorted = withContext(Dispatchers.Default) { collected.sortedBy { it.rank } }
                        applyPlayers(sorted, total)
                    }
                }
                cacheIndex = index
                applyPlayers(index.players, index.total)
                _state.update { it.copy(sync = LeaderboardSync.Ready(index.fetchedAt, index.complete)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LeaderboardException) {
                fail(e.message ?: GENERIC_ERROR)
            } catch (e: Exception) {
                fail(GENERIC_ERROR)
            }
        }
    }

    private fun fail(message: String) {
        // Keep showing a previous index if there is one; otherwise surface the error.
        val cached = cacheIndex
        _state.update {
            it.copy(sync = if (it.players.isNotEmpty() && cached != null) LeaderboardSync.Ready(cached.fetchedAt, cached.complete) else LeaderboardSync.Failed(message))
        }
    }

    private fun recompute(immediate: Boolean) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (!immediate) delay(160)
            val s = _state.value
            val players = s.players
            val norms = normalized
            val results = withContext(Dispatchers.Default) {
                if (s.query.isBlank()) players.take(10) else Leaderboard.search(players, norms, s.query)
            }
            _state.update { it.copy(results = results) }
        }
    }

    // ---------- "Me" ----------

    fun isMe(player: LeaderboardPlayer): Boolean {
        val me = _state.value.me ?: return false
        return me.key == player.key
    }

    fun toggleMe(player: LeaderboardPlayer) {
        if (isMe(player)) {
            prefs.edit().remove(KEY_ME_NAME).remove(KEY_ME_DIVISION).remove(KEY_ME_RANK).apply()
            _state.update { it.copy(me = null) }
        } else {
            prefs.edit()
                .putString(KEY_ME_NAME, player.name)
                .putString(KEY_ME_DIVISION, _state.value.division.name)
                .putInt(KEY_ME_RANK, player.rank)
                .apply()
            _state.update { it.copy(me = player) }
        }
    }

    private fun resolveMe() {
        val name = prefs.getString(KEY_ME_NAME, null)
        val division = prefs.getString(KEY_ME_DIVISION, null)
        if (name == null || division != _state.value.division.name) {
            _state.update { it.copy(me = null) }
            return
        }
        val hint = prefs.getInt(KEY_ME_RANK, 0)
        val match = _state.value.players.filter { it.name == name }.minByOrNull { abs(it.rank - hint) }
        if (match != null) prefs.edit().putInt(KEY_ME_RANK, match.rank).apply()
        _state.update { it.copy(me = match) }
    }

    private companion object {
        const val KEY_QUERY = "players_query"
        const val KEY_DIVISION = "players_division"
        const val KEY_ME_NAME = "players_me_name"
        const val KEY_ME_DIVISION = "players_me_division"
        const val KEY_ME_RANK = "players_me_rank"
        const val GENERIC_ERROR = "Couldn't load the leaderboard. Check your connection and try again."
    }
}

// ---------- Formatting ----------

private val intFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)
private fun Int.fmt(): String = intFormat.format(this)

private fun topPercent(rank: Int, total: Int): String {
    if (total <= 0) return "—"
    val p = rank * 100.0 / total
    return when {
        p < 1 -> String.format(Locale.US, "Top %.2f%%", p)
        p < 10 -> String.format(Locale.US, "Top %.1f%%", p)
        else -> "Top ${p.toInt().coerceAtLeast(1)}%"
    }
}

private fun rankTint(rank: Int): Color? = when (rank) {
    1 -> Palette.Gold
    2 -> Palette.Silver
    3 -> Palette.Bronze
    else -> null
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

private fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// ---------- UI ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayersScreen(modifier: Modifier = Modifier, vm: PlayersViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findHostActivity() }
    val focus = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.division, activity) {
        activity?.let(vm::ensureLoaded)
    }

    val loading = state.sync is LeaderboardSync.Loading
    val searching = state.query.isNotBlank()
    val open: (LeaderboardPlayer) -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        focus.clearFocus()
        selectedKey = it.key
    }

    CanvasBackground(modifier) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "title") {
                Column(Modifier.padding(bottom = 4.dp)) {
                    Text("Players", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                    Text("Championship Points leaderboard", fontSize = 15.sp, color = Palette.Muted)
                }
            }
            item(key = "search") {
                PlayerSearchCard(
                    query = state.query,
                    onQuery = vm::setQuery,
                    division = state.division,
                    onDivision = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        vm.setDivision(it)
                    },
                    sync = state.sync,
                    indexed = state.players.size,
                    onRefresh = { activity?.let(vm::refresh) },
                    onSubmit = { focus.clearFocus() },
                )
            }
            state.me?.let { me ->
                if (!searching) {
                    item(key = "me") { MeTile(me, state.total, Modifier.animateItem()) { open(me) } }
                }
            }

            val failed = state.sync as? LeaderboardSync.Failed
            when {
                failed != null && state.players.isEmpty() -> item(key = "failed") {
                    PlayersMessage(
                        icon = Icons.Filled.WifiOff,
                        title = "Couldn't load the leaderboard",
                        message = failed.message,
                        primary = "Try Again" to { activity?.let(vm::refresh) },
                        url = Leaderboard.pageUrl(state.division),
                    )
                }
                state.players.isEmpty() -> item(key = "warming") { WarmingUp(state.sync) }
                else -> {
                    item(key = "header") {
                        val label = when {
                            !searching -> "Top 10 · ${state.division.label}"
                            state.results.isEmpty() -> ""
                            loading -> "${state.results.size}${if (state.results.size >= 200) "+" else ""} matches so far"
                            else -> "${state.results.size}${if (state.results.size >= 200) "+" else ""} match${if (state.results.size == 1) "" else "es"}"
                        }
                        if (label.isNotEmpty()) {
                            Text(
                                label,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Palette.Muted,
                                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                            )
                        }
                    }
                    if (searching && state.results.isEmpty()) {
                        item(key = "empty") {
                            PlayersMessage(
                                icon = Icons.Filled.PersonSearch,
                                title = if (loading) "Still searching…" else "No players found",
                                message = if (loading) {
                                    "Nobody named “${state.query.trim()}” yet. The rest of the leaderboard is still loading."
                                } else {
                                    "Nobody named “${state.query.trim()}” in ${state.division.label}. Only players with Championship Points this season are listed."
                                },
                                primary = null,
                                url = null,
                            )
                        }
                    } else {
                        items(state.results, key = { "p-${it.key}" }) { player ->
                            PlayerRow(player, isMe = state.me?.key == player.key, modifier = Modifier.animateItem()) { open(player) }
                        }
                    }
                    item(key = "footer") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .pressable { openLink(context, Leaderboard.pageUrl(state.division)) }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Full leaderboard on Pokémon.com", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Blue)
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = Palette.Blue, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }

    val selected = selectedKey?.let { key -> state.players.firstOrNull { it.key == key } }
    if (selected != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedKey = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.Canvas,
        ) {
            PlayerDetail(
                player = selected,
                players = state.players,
                total = state.total,
                division = state.division,
                isMe = state.me?.key == selected.key,
                onToggleMe = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.toggleMe(selected)
                },
                onOpen = { openLink(context, Leaderboard.pageUrl(state.division)) },
            )
        }
    }
}

@Composable
private fun PlayerSearchCard(
    query: String,
    onQuery: (String) -> Unit,
    division: Division,
    onDivision: (Division) -> Unit,
    sync: LeaderboardSync,
    indexed: Int,
    onRefresh: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().cardSurface().padding(14.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(Color(0x1F767680), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = Palette.Red, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search player name", fontSize = 17.sp, color = Palette.Muted)
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 17.sp, color = Palette.Ink),
                    cursorBrush = SolidColor(Palette.Red),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
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
        SegmentedPicker(
            options = Division.entries.toList(),
            selected = division,
            label = { it.label },
            onSelect = onDivision,
            modifier = Modifier.fillMaxWidth(),
        )
        SyncLine(sync, indexed, onRefresh)
    }
}

@Composable
private fun SyncLine(sync: LeaderboardSync, indexed: Int, onRefresh: () -> Unit) {
    when (sync) {
        is LeaderboardSync.Loading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val label = if (sync.total > 0) "Loading leaderboard ${sync.loaded.fmt()} / ${sync.total.fmt()}" else "Connecting to Pokémon.com…"
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted)
            if (sync.total > 0) {
                LinearProgressIndicator(
                    progress = { (sync.loaded.toFloat() / sync.total).coerceIn(0f, 1f) },
                    color = Palette.Red,
                    trackColor = Palette.Hairline,
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            } else {
                LinearProgressIndicator(color = Palette.Red, trackColor = Palette.Hairline, strokeCap = StrokeCap.Round, modifier = Modifier.fillMaxWidth().height(4.dp))
            }
        }
        is LeaderboardSync.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
            val ago = DateUtils.getRelativeTimeSpanString(sync.fetchedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            Text(
                "${indexed.fmt()} players · updated $ago" + if (!sync.complete) " · partial" else "",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Palette.Muted,
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier.pressable(onClick = onRefresh).padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, tint = Palette.Blue, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(3.dp))
                Text("Refresh", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Blue)
            }
        }
        is LeaderboardSync.Failed -> if (indexed > 0) {
            Text(sync.message, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Red)
        }
        LeaderboardSync.Idle -> Unit
    }
}

@Composable
private fun RankBadge(rank: Int, size: Int = 40) {
    val tint = rankTint(rank)
    val text = "#${rank.fmt()}"
    val fontSize = when {
        size > 50 -> 20.sp
        text.length > 6 -> 11.sp
        text.length > 4 -> 13.sp
        else -> 15.sp
    }
    Box(
        Modifier
            .defaultMinSize(minWidth = size.dp, minHeight = size.dp)
            .background(tint ?: Palette.Canvas, RoundedCornerShape((size / 2).dp))
            .border(1.dp, tint?.copy(alpha = 0.0f) ?: Palette.Hairline, RoundedCornerShape((size / 2).dp))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = fontSize, fontWeight = FontWeight.Bold, color = if (tint != null) Color.White else Palette.Ink, maxLines = 1)
    }
}

@Composable
private fun PlayerRow(player: LeaderboardPlayer, isMe: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .cardSurface()
            .then(if (isMe) Modifier.border(2.dp, Palette.Gold, RoundedCornerShape(20.dp)) else Modifier)
            .pressable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RankBadge(player.rank)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(player.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (isMe) Icon(Icons.Filled.Star, contentDescription = "You", tint = Palette.Gold, modifier = Modifier.size(16.dp))
            }
            player.country?.let { Text(it, fontSize = 13.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(player.points.fmt(), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Red)
            Text("CP", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.Muted, letterSpacing = 0.6.sp)
        }
        Chevron()
    }
}

@Composable
private fun MeTile(me: LeaderboardPlayer, total: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .cardSurface()
            .border(2.dp, Palette.Gold, RoundedCornerShape(20.dp))
            .pressable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = Palette.Gold, modifier = Modifier.size(16.dp))
            Text("YOU", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text(topPercent(me.rank, total), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.Blue,
                modifier = Modifier.background(Palette.Blue.copy(alpha = 0.1f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(me.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Rank #${me.rank.fmt()}" + (me.country?.let { " · $it" } ?: ""), fontSize = 14.sp, color = Palette.Muted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(me.points.fmt(), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.Red)
                Text("CHAMPIONSHIP POINTS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Palette.Muted, letterSpacing = 0.6.sp)
            }
        }
    }
}

@Composable
private fun WarmingUp(sync: LeaderboardSync) {
    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(72.dp).background(Palette.Gold.copy(alpha = 0.18f), CircleShape).border(2.dp, Palette.Gold, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (sync is LeaderboardSync.Loading) {
                CircularProgressIndicator(color = Palette.Red, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
            } else {
                Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = Palette.Red, modifier = Modifier.size(34.dp))
            }
        }
        Text("Who's leading the season?", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, textAlign = TextAlign.Center)
        Text(
            "Look up any player's Championship Points on the official Play! Pokémon leaderboard. The first load indexes every ranked player, then searches are instant.",
            fontSize = 15.sp,
            color = Palette.Muted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PlayersMessage(
    icon: ImageVector,
    title: String,
    message: String,
    primary: Pair<String, () -> Unit>?,
    url: String?,
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().cardSurface().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(40.dp))
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, textAlign = TextAlign.Center)
        Text(message, fontSize = 15.sp, color = Palette.Muted, textAlign = TextAlign.Center)
        if (primary != null || url != null) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                primary?.let { (label, action) ->
                    Button(onClick = action, colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue)) {
                        Text(label, fontWeight = FontWeight.SemiBold)
                    }
                }
                url?.let {
                    OutlinedButton(onClick = { openLink(context, it) }) {
                        Text("Open on Pokémon.com", fontWeight = FontWeight.SemiBold, color = Palette.Blue)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier, tint: Color = Palette.Ink) {
    Column(
        modifier.cardSurface().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, fontSize = 12.sp, color = Palette.Muted, maxLines = 2)
    }
}

@Composable
private fun PlayerDetail(
    player: LeaderboardPlayer,
    players: List<LeaderboardPlayer>,
    total: Int,
    division: Division,
    isMe: Boolean,
    onToggleMe: () -> Unit,
    onOpen: () -> Unit,
) {
    val countryRank = remember(player, players) {
        player.country?.let { c -> players.count { it.country == c && it.rank < player.rank } + 1 }
    }
    val above = remember(player, players) { players.lastOrNull { it.rank < player.rank } }
    val leader = players.firstOrNull()

    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            RankBadge(player.rank, size = 56)
            Column(Modifier.weight(1f)) {
                Text(player.name, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                Text(listOfNotNull(player.country, division.label).joinToString(" · "), fontSize = 15.sp, color = Palette.Muted)
            }
        }
        Column(
            Modifier.fillMaxWidth().cardSurface().padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(player.points.fmt(), fontSize = 52.sp, fontWeight = FontWeight.Bold, color = Palette.Red)
            Text("CHAMPIONSHIP POINTS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.Muted, letterSpacing = 1.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("#${player.rank.fmt()}", "of ${total.fmt()} players", Modifier.weight(1f))
            StatTile(topPercent(player.rank, total), "percentile", Modifier.weight(1f), tint = Palette.Blue)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(countryRank?.let { "#${it.fmt()}" } ?: "—", player.country?.let { "in $it" } ?: "country rank", Modifier.weight(1f))
            val gapValue = above?.let { "+${(it.points - player.points + 1).coerceAtLeast(1).fmt()}" } ?: "Leader"
            val gapLabel = above?.let { "CP to pass #${it.rank.fmt()}" } ?: "No one ahead"
            StatTile(gapValue, gapLabel, Modifier.weight(1f), tint = if (above == null) Palette.Gold else Palette.Ink)
        }
        if (leader != null && leader.key != player.key) {
            Text(
                "${(leader.points - player.points).fmt()} CP behind #1 ${leader.name}",
                fontSize = 13.sp,
                color = Palette.Muted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        AnimatedVisibility(player.inCutoff, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(
                "Inside the Worlds invite cutoff",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Palette.Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(Palette.Gold.copy(alpha = 0.2f), RoundedCornerShape(10.dp)).padding(10.dp),
            )
        }
        Button(
            onClick = onToggleMe,
            colors = ButtonDefaults.buttonColors(containerColor = if (isMe) Palette.Ink else Palette.Red),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Icon(if (isMe) Icons.Filled.StarBorder else Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (isMe) "Stop Tracking" else "Track as Me", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
            onClick = onOpen,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = Palette.Blue, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Leaderboard on Pokémon.com", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Blue)
        }
    }
}
