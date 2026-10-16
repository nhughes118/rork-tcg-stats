package com.rork.tcgstatsandroid.data

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer

/** Small persisted state: scan history and the selected tournament source. */
class AppStorage(context: Context) {
    private val prefs = context.getSharedPreferences("tcg_stats", Context.MODE_PRIVATE)
    private val historySerializer = ListSerializer(HistoryEntry.serializer())

    fun loadHistory(): List<HistoryEntry> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching { LimitlessClient.json.decodeFromString(historySerializer, raw) }.getOrDefault(emptyList())
    }

    fun saveHistory(history: List<HistoryEntry>) {
        prefs.edit().putString(KEY_HISTORY, LimitlessClient.json.encodeToString(historySerializer, history)).apply()
    }

    var source: DataSource
        get() = DataSource.entries.firstOrNull { it.name == prefs.getString(KEY_SOURCE, null) } ?: DataSource.Online
        set(value) {
            prefs.edit().putString(KEY_SOURCE, value.name).apply()
        }

    private companion object {
        const val KEY_HISTORY = "history"
        const val KEY_SOURCE = "tournament_source"
    }
}
