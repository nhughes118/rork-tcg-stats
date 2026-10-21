package com.rork.tcgstatsandroid.ui.screens

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rork.tcgstatsandroid.data.EventKind
import com.rork.tcgstatsandroid.data.EventLocator
import com.rork.tcgstatsandroid.data.EventLocatorException
import com.rork.tcgstatsandroid.data.GeoPoint
import com.rork.tcgstatsandroid.data.LocalEvent
import com.rork.tcgstatsandroid.ui.components.CanvasBackground
import com.rork.tcgstatsandroid.ui.components.Chevron
import com.rork.tcgstatsandroid.ui.components.SegmentedPicker
import com.rork.tcgstatsandroid.ui.components.cardSurface
import com.rork.tcgstatsandroid.ui.components.pressable
import com.rork.tcgstatsandroid.ui.theme.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

// ---------- State ----------

sealed interface EventsPhase {
    data object Idle : EventsPhase
    data class Loading(val place: String?) : EventsPhase
    data class Loaded(val place: GeoPoint, val events: List<LocalEvent>) : EventsPhase
    data class Failed(val message: String, val place: GeoPoint?) : EventsPhase
}

data class EventsUiState(
    val query: String = "",
    val range: Int = 25,
    val tcgOnly: Boolean = true,
    val phase: EventsPhase = EventsPhase.Idle,
)

class EventsViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("tcg_stats", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        EventsUiState(
            query = prefs.getString(KEY_QUERY, "") ?: "",
            range = prefs.getInt(KEY_RANGE, 25).takeIf { it in EventLocator.distanceOptions } ?: 25,
            tcgOnly = prefs.getBoolean(KEY_TCG, true),
        )
    )
    val state: StateFlow<EventsUiState> = _state
    private var job: Job? = null

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun setRange(range: Int, activity: Activity?) {
        _state.update { it.copy(range = range) }
        prefs.edit().putInt(KEY_RANGE, range).apply()
        if (_state.value.phase !is EventsPhase.Idle && activity != null) search(activity)
    }

    fun setTcgOnly(value: Boolean) {
        _state.update { it.copy(tcgOnly = value) }
        prefs.edit().putBoolean(KEY_TCG, value).apply()
    }

    fun search(activity: Activity) {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        prefs.edit().putString(KEY_QUERY, query).apply()
        job?.cancel()
        _state.update { it.copy(phase = EventsPhase.Loading(null)) }
        job = viewModelScope.launch {
            var point: GeoPoint? = null
            try {
                point = EventLocator.geocode(getApplication(), query)
                _state.update { it.copy(phase = EventsPhase.Loading(point.label)) }
                val events = EventLocator.search(activity, point, _state.value.range)
                _state.update { it.copy(phase = EventsPhase.Loaded(point, events)) }
            } catch (e: EventLocatorException) {
                _state.update { it.copy(phase = EventsPhase.Failed(e.message ?: "Something went wrong.", point)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = EventsPhase.Failed("Couldn't load events right now. Check your connection and try again.", point)) }
            }
        }
    }

    private companion object {
        const val KEY_QUERY = "events_query"
        const val KEY_RANGE = "events_range"
        const val KEY_TCG = "events_tcg_only"
    }
}

// ---------- UI ----------

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun EventKind.color(): Color = when (this) {
    EventKind.Prerelease -> Palette.Gold
    EventKind.LeagueCup -> Palette.Red
    EventKind.LeagueChallenge -> Palette.Blue
    EventKind.Championship -> Palette.Ink
    EventKind.Tournament -> Palette.Blue
    EventKind.League -> Palette.Green
    EventKind.Other -> Palette.Muted
}

private fun milesLabel(d: Double): String = if (d < 10) String.format(Locale.US, "%.1f mi", d) else "${d.toInt()} mi"

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun openDirections(context: Context, event: LocalEvent) {
    val uri = if (event.latitude != null && event.longitude != null) {
        val label = Uri.encode(event.venue ?: event.name)
        Uri.parse("geo:${event.latitude},${event.longitude}?q=${event.latitude},${event.longitude}($label)")
    } else {
        Uri.parse("geo:0,0?q=" + Uri.encode(listOfNotNull(event.venue, event.address).joinToString(", ")))
    }
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        openUrl(context, "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(listOfNotNull(event.venue, event.address).joinToString(", ")))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(modifier: Modifier = Modifier, vm: EventsViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val focus = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }

    val submit = {
        focus.clearFocus()
        if (activity != null && state.query.isNotBlank()) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            vm.search(activity)
        }
    }

    val loaded = state.phase as? EventsPhase.Loaded
    val visible = remember(loaded, state.tcgOnly) {
        loaded?.events?.filter { !state.tcgOnly || it.isTcg }.orEmpty()
    }

    CanvasBackground(modifier) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "title") {
                Column(Modifier.padding(bottom = 4.dp)) {
                    Text("Events", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                    Text("Pokémon TCG events near you", fontSize = 15.sp, color = Palette.Muted)
                }
            }
            item(key = "search") {
                SearchCard(
                    query = state.query,
                    onQuery = vm::setQuery,
                    range = state.range,
                    onRange = { vm.setRange(it, activity) },
                    tcgOnly = state.tcgOnly,
                    onTcgOnly = vm::setTcgOnly,
                    loading = state.phase is EventsPhase.Loading,
                    onSubmit = submit,
                )
            }
            when (val phase = state.phase) {
                EventsPhase.Idle -> item(key = "idle") { IdleState() }
                is EventsPhase.Loading -> item(key = "loading") { LoadingState(phase.place) }
                is EventsPhase.Failed -> item(key = "failed") {
                    MessageState(
                        icon = Icons.Filled.WifiOff,
                        title = "Couldn't load events",
                        message = phase.message,
                        primary = "Try Again" to { activity?.let(vm::search) },
                        pokemonUrl = phase.place?.let { EventLocator.pageUrl(it.latitude, it.longitude, state.range) },
                    )
                }
                is EventsPhase.Loaded -> {
                    val pokemonUrl = EventLocator.pageUrl(phase.place.latitude, phase.place.longitude, state.range)
                    if (visible.isEmpty()) {
                        item(key = "empty") {
                            MessageState(
                                icon = Icons.Filled.EventBusy,
                                title = "No events found",
                                message = "Nothing within ${state.range} mi of ${phase.place.label}" +
                                    (if (state.tcgOnly && phase.events.isNotEmpty()) " for the TCG. Turn off TCG only to see other events." else ". Try a wider distance."),
                                primary = null,
                                pokemonUrl = pokemonUrl,
                            )
                        }
                    } else {
                        item(key = "summary") {
                            Text(
                                "${visible.size} event${if (visible.size == 1) "" else "s"} within ${state.range} mi of ${phase.place.label}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Palette.Muted,
                                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                            )
                        }
                        val groups = visible.groupBy { it.dayKey() }
                        groups.forEach { (day, events) ->
                            item(key = "h-$day") {
                                Text(
                                    events.first().dayLabel(),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Palette.Ink,
                                    modifier = Modifier.padding(top = 10.dp, start = 4.dp).animateItem(),
                                )
                            }
                            items(events, key = { "e-${it.id}" }) { event ->
                                EventRow(event, Modifier.animateItem()) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedId = event.id
                                }
                            }
                        }
                        item(key = "footer") {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .pressable { openUrl(context, pokemonUrl) }
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("See all on Pokémon.com", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Blue)
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = Palette.Blue, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    val selected = loaded?.events?.firstOrNull { it.id == selectedId }
    if (selected != null && loaded != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedId = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.Canvas,
        ) {
            EventDetail(
                event = selected,
                pokemonUrl = selected.link ?: EventLocator.pageUrl(loaded.place.latitude, loaded.place.longitude, state.range),
                onDirections = { openDirections(context, selected) },
                onOpen = { url -> openUrl(context, url) },
            )
        }
    }
}

@Composable
private fun SearchCard(
    query: String,
    onQuery: (String) -> Unit,
    range: Int,
    onRange: (Int) -> Unit,
    tcgOnly: Boolean,
    onTcgOnly: (Boolean) -> Unit,
    loading: Boolean,
    onSubmit: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().cardSurface().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier
                    .weight(1f)
                    .height(46.dp)
                    .background(Color(0x1F767680), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Palette.Red, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Zip code or city", fontSize = 17.sp, color = Palette.Muted)
                    BasicTextField(
                        value = query,
                        onValueChange = onQuery,
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 17.sp, color = Palette.Ink),
                        cursorBrush = SolidColor(Palette.Red),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
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
            Button(
                onClick = onSubmit,
                enabled = query.isNotBlank() && !loading,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Red, disabledContainerColor = Palette.Red.copy(alpha = 0.35f)),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.height(46.dp),
            ) {
                Text("Search", fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
        SegmentedPicker(
            options = EventLocator.distanceOptions,
            selected = range,
            label = { "$it mi" },
            onSelect = onRange,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("TCG only", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                Text("Hide video game and Pokémon GO events", fontSize = 12.sp, color = Palette.Muted)
            }
            Switch(
                checked = tcgOnly,
                onCheckedChange = onTcgOnly,
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.Blue, checkedThumbColor = Color.White),
            )
        }
    }
}

@Composable
private fun IdleState() {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(72.dp).background(Palette.Gold.copy(alpha = 0.18f), CircleShape).border(2.dp, Palette.Gold, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Place, contentDescription = null, tint = Palette.Red, modifier = Modifier.size(34.dp))
        }
        Text("Find your next tournament", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, textAlign = TextAlign.Center)
        Text(
            "Prereleases, League Challenges, League Cups and more, straight from Pokémon's official event locator.",
            fontSize = 15.sp,
            color = Palette.Muted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LoadingState(place: String?) {
    Row(
        Modifier.fillMaxWidth().cardSurface().padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(color = Palette.Red, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
        AnimatedContent(place, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "loading") { p ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (p == null) "Finding location…" else "Checking events near $p…", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                Text("This can take a few seconds", fontSize = 13.sp, color = Palette.Muted)
            }
        }
    }
}

@Composable
private fun MessageState(
    icon: ImageVector,
    title: String,
    message: String,
    primary: Pair<String, () -> Unit>?,
    pokemonUrl: String?,
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().cardSurface().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(40.dp))
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        Text(message, fontSize = 15.sp, color = Palette.Muted, textAlign = TextAlign.Center)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            primary?.let { (label, action) ->
                Button(onClick = action, colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue)) {
                    Text(label, fontWeight = FontWeight.SemiBold)
                }
            }
            pokemonUrl?.let { url ->
                OutlinedButton(onClick = { openUrl(context, url) }) {
                    Text("Open on Pokémon.com", fontWeight = FontWeight.SemiBold, color = Palette.Blue)
                }
            }
        }
    }
}

@Composable
private fun DateBadge(event: LocalEvent) {
    val tint = event.kind.color()
    Column(
        Modifier
            .width(52.dp)
            .background(tint.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(event.monthAbbrev(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (event.kind == EventKind.Prerelease) Palette.Ink else tint, letterSpacing = 0.8.sp)
        Text(event.dayNumber(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
    }
}

@Composable
private fun KindBadge(event: LocalEvent) {
    val tint = event.kind.color()
    val label = if (event.kind == EventKind.Other) event.typeName ?: event.kind.label else event.kind.label
    Text(
        label.uppercase(Locale.US),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        color = if (event.kind == EventKind.Prerelease) Palette.Ink else tint,
        maxLines = 1,
        modifier = Modifier.background(tint.copy(alpha = 0.14f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun EventRow(event: LocalEvent, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().cardSurface().pressable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DateBadge(event)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KindBadge(event)
                event.distanceMiles?.let { Text(milesLabel(it), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted) }
            }
            Text(event.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(event.timeLabel(), event.venue).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, fontSize = 13.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Chevron()
    }
}

@Composable
private fun DetailLine(icon: ImageVector, text: String, secondary: String? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = Palette.Blue, modifier = Modifier.size(20.dp).padding(top = 1.dp))
        Column {
            Text(text, fontSize = 15.sp, color = Palette.Ink)
            secondary?.let { Text(it, fontSize = 13.sp, color = Palette.Muted) }
        }
    }
}

@Composable
private fun EventDetail(event: LocalEvent, pokemonUrl: String, onDirections: () -> Unit, onOpen: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            DateBadge(event)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                KindBadge(event)
                Text(event.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            }
        }
        Column(
            Modifier.fillMaxWidth().cardSurface().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DetailLine(Icons.Filled.Schedule, event.fullDateLabel(), event.timeZone?.let { java.util.TimeZone.getTimeZone(it).getDisplayName(false, java.util.TimeZone.SHORT, Locale.US) + " local time" })
            event.venue?.let { DetailLine(Icons.Filled.Storefront, it, event.distanceMiles?.let { d -> "${milesLabel(d)} away" }) }
            event.address?.let { DetailLine(Icons.Filled.Place, it) }
            event.cost?.let { DetailLine(Icons.Filled.Payments, it) }
        }
        Button(
            onClick = onDirections,
            enabled = event.address != null || event.latitude != null,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Red),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Icon(Icons.Filled.Directions, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Directions", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
            onClick = { onOpen(pokemonUrl) },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = Palette.Blue, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("View on Pokémon.com", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Blue)
        }
    }
}
