package com.rork.tcgstatsandroid.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rork.tcgstatsandroid.data.AppStorage
import com.rork.tcgstatsandroid.data.CardDataset
import com.rork.tcgstatsandroid.data.CardIdentity
import com.rork.tcgstatsandroid.data.CardMatcher
import com.rork.tcgstatsandroid.data.CardStats
import com.rork.tcgstatsandroid.data.DataSource
import com.rork.tcgstatsandroid.data.HistoryEntry
import com.rork.tcgstatsandroid.data.LimitlessClient
import com.rork.tcgstatsandroid.data.StatsWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

sealed interface LoadState {
    data object Idle : LoadState
    data class Loading(val progress: Double) : LoadState
    data object Loaded : LoadState
    data class Failed(val message: String) : LoadState
}

/** Popular card shown in search and empty states. */
data class PopularCard(val card: CardIdentity, val usage: Double)

data class AppUiState(
    val source: DataSource = DataSource.Online,
    val datasets: Map<DataSource, CardDataset> = emptyMap(),
    val matchers: Map<DataSource, CardMatcher> = emptyMap(),
    val popular: Map<DataSource, List<PopularCard>> = emptyMap(),
    val loadStates: Map<DataSource, LoadState> = emptyMap(),
    val currentCardKey: String? = null,
    val history: List<HistoryEntry> = emptyList(),
) {
    val dataset: CardDataset? get() = datasets[source]
    val matcher: CardMatcher? get() = matchers[source]
    val popularCards: List<PopularCard> get() = popular[source].orEmpty()
    val loadState: LoadState get() = loadStates[source] ?: LoadState.Idle
    val isRefreshing: Boolean get() = loadState is LoadState.Loading && dataset != null
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val storage = AppStorage(application)
    private val statsCache = ConcurrentHashMap<String, CardStats>()

    private val _state: MutableStateFlow<AppUiState>
    val state: StateFlow<AppUiState>

    /** Whether the app should open on the Scan tab (no history yet). */
    val startsOnScan: Boolean

    init {
        val history = storage.loadHistory()
        startsOnScan = history.isEmpty()
        _state = MutableStateFlow(
            AppUiState(source = storage.source, history = history, currentCardKey = history.firstOrNull()?.card?.key)
        )
        state = _state.asStateFlow()
        load()
    }

    /** Switches the stats source and starts loading it if needed. */
    fun selectSource(source: DataSource) {
        if (source == _state.value.source) return
        storage.source = source
        _state.update { it.copy(source = source) }
        load()
    }

    fun load(force: Boolean = false) {
        val src = _state.value.source
        val current = _state.value.loadStates[src]
        if (current is LoadState.Loading) return
        if (_state.value.datasets[src] != null && !force) {
            setLoadState(src, LoadState.Loaded)
            return
        }
        setLoadState(src, LoadState.Loading(0.0))
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val (dataset, matcher, popular) = withContext(Dispatchers.Default) {
                    val result = LimitlessClient.loadDataset(context, src) { value -> reportProgress(src, value) }
                    val popular = result.recentUsage.entries
                        .sortedByDescending { it.value }
                        .mapNotNull { (key, usage) -> result.cards[key]?.let { PopularCard(it, usage) } }
                    Triple(result, CardMatcher(result), popular)
                }
                statsCache.keys.removeAll { it.startsWith("${src.name}|") }
                _state.update { s ->
                    val history = if (s.source == src) {
                        s.history.map { it.copy(usage = dataset.recentUsage[it.card.key] ?: 0.0) }
                    } else s.history
                    s.copy(
                        datasets = s.datasets + (src to dataset),
                        matchers = s.matchers + (src to matcher),
                        popular = s.popular + (src to popular),
                        loadStates = s.loadStates + (src to LoadState.Loaded),
                        history = history,
                    )
                }
                storage.saveHistory(_state.value.history)
            } catch (e: Exception) {
                Log.w("TCGStats", "Dataset load failed: ${e.javaClass.simpleName}")
                val message = (e as? com.rork.tcgstatsandroid.data.LimitlessException)?.message
                    ?: "Couldn't reach Limitless TCG. Check your connection and try again."
                val hasData = _state.value.datasets[src] != null
                setLoadState(src, if (hasData) LoadState.Loaded else LoadState.Failed(message))
            }
        }
    }

    private fun reportProgress(src: DataSource, value: Double) {
        _state.update { s ->
            val current = s.loadStates[src]
            if (current is LoadState.Loading && value > current.progress) {
                s.copy(loadStates = s.loadStates + (src to LoadState.Loading(value)))
            } else s
        }
    }

    private fun setLoadState(src: DataSource, state: LoadState) {
        _state.update { it.copy(loadStates = it.loadStates + (src to state)) }
    }

    fun stats(key: String, window: StatsWindow): CardStats? {
        val s = _state.value
        val dataset = s.dataset ?: return null
        val cacheKey = "${s.source.name}|$key|${window.name}|${System.identityHashCode(dataset)}"
        statsCache[cacheKey]?.let { return it }
        val stats = dataset.stats(key, window) ?: return null
        statsCache[cacheKey] = stats
        return stats
    }

    fun identity(key: String): CardIdentity? {
        val s = _state.value
        return s.dataset?.cards?.get(key) ?: s.history.firstOrNull { it.card.key == key }?.card
    }

    /** Selects a card for the Results tab and records it in history. Returns false if unknown. */
    fun showResults(key: String): Boolean {
        val s = _state.value
        val dataset = s.dataset ?: return false
        val card = dataset.cards[key] ?: return false
        val usage = dataset.recentUsage[key] ?: 0.0
        val history = listOf(HistoryEntry(card, usage, System.currentTimeMillis())) +
            s.history.filterNot { it.card.key == key }
        _state.update { it.copy(currentCardKey = key, history = history) }
        storage.saveHistory(history)
        return true
    }

    fun deleteHistory(key: String) {
        _state.update { s -> s.copy(history = s.history.filterNot { it.card.key == key }) }
        storage.saveHistory(_state.value.history)
    }

    fun clearHistory() {
        _state.update { it.copy(history = emptyList()) }
        storage.saveHistory(emptyList())
    }
}
