package com.rork.tcgstatsandroid.data

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

class LeaderboardException(message: String) : Exception(message)

enum class Division(val slug: String, val label: String) {
    Masters("tcg-master", "Masters"),
    Seniors("tcg-senior", "Seniors"),
    Juniors("tcg-junior", "Juniors"),
}

@Serializable
data class LeaderboardPlayer(
    val rank: Int,
    val name: String,
    val points: Int,
    val country: String? = null,
    val countryCode: String? = null,
    val inCutoff: Boolean = false,
) {
    val key: String get() = "$rank|$name"
}

@Serializable
data class LeaderboardIndex(
    val division: String,
    val fetchedAt: Long,
    val total: Int,
    val complete: Boolean,
    val players: List<LeaderboardPlayer>,
)

/**
 * Downloads the Play! Pokémon TCG Championship Points leaderboard. The site has no name search, so the whole
 * division is indexed once (cached on disk for [CACHE_TTL_MS]) and searched locally.
 */
object Leaderboard {
    private const val API = "https://www.pokemon.com/us/play-pokemon/leaderboards/op/api/"
    const val PAGE_URL = "https://www.pokemon.com/us/play-pokemon/leaderboards/tcg-masters/"
    private const val CACHE_TTL_MS = 12 * 3_600_000L
    private const val REQUESTED_PER_PAGE = 1000
    private const val CONCURRENCY = 5
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"

    /** The official page has its own division switcher, so every division links to the same page. */
    @Suppress("UNUSED_PARAMETER")
    fun pageUrl(division: Division): String = PAGE_URL

    private fun apiUrl(division: Division, page: Int, perPage: Int) =
        "$API${division.slug}/?per_page=$perPage&page=$page&include=metadata&format=json"

    // ---------- Cache ----------

    private fun cacheFile(context: Context, division: Division) =
        File(context.filesDir, "leaderboard").apply { mkdirs() }.let { File(it, "${division.slug}-v1.json") }

    suspend fun cached(context: Context, division: Division): LeaderboardIndex? = withContext(Dispatchers.IO) {
        val file = cacheFile(context, division)
        if (!file.exists()) return@withContext null
        runCatching { LimitlessClient.json.decodeFromString(LeaderboardIndex.serializer(), file.readText()) }.getOrNull()
    }

    fun isFresh(index: LeaderboardIndex): Boolean =
        index.complete && System.currentTimeMillis() - index.fetchedAt < CACHE_TTL_MS

    private suspend fun save(context: Context, division: Division, index: LeaderboardIndex) = withContext(Dispatchers.IO) {
        runCatching {
            val file = cacheFile(context, division)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(LimitlessClient.json.encodeToString(LeaderboardIndex.serializer(), index))
            tmp.renameTo(file)
        }
    }

    // ---------- Fetching ----------

    private class Page(val records: List<LeaderboardPlayer>, val total: Int?, val pageCount: Int?, val perPage: Int?)

    private class BlockedException : Exception()

    /**
     * Indexes [division]. [onProgress] receives the players collected so far (unsorted) and the expected total.
     * Native requests are tried first; if the bot wall answers with HTML, a hidden WebView session takes over.
     */
    suspend fun load(
        activity: Activity,
        division: Division,
        onProgress: suspend (List<LeaderboardPlayer>, Int) -> Unit,
    ): LeaderboardIndex {
        var web: WebFetcher? = null
        val fetchMutex = Mutex()

        suspend fun fetchText(url: String): String {
            web?.let { return it.get(url) }
            val native = runCatching { LimitlessClient.httpGet(url, "application/json, text/plain, */*", UA) }
            val body = native.getOrNull()
            if (body != null && body.trimStart().startsWith("{")) return body
            Log.d("Leaderboard", "native fetch blocked (${native.exceptionOrNull()?.message ?: "HTML"}), switching to WebView")
            val fetcher = fetchMutex.withLock {
                web ?: WebFetcher(activity).also { it.open(PAGE_URL); web = it }
            }
            return fetcher.get(url)
        }

        suspend fun fetchPage(page: Int, perPage: Int): Page {
            var attempt = 0
            while (true) {
                try {
                    val text = fetchText(apiUrl(division, page, perPage))
                    if (!text.trimStart().startsWith("{")) throw BlockedException()
                    return parse(text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    attempt++
                    if (attempt >= 3) throw if (e is BlockedException) e else LeaderboardException(e.message ?: "")
                    delay(1_200L * attempt)
                }
            }
        }

        try {
            val first = try {
                fetchPage(1, REQUESTED_PER_PAGE)
            } catch (e: BlockedException) {
                throw LeaderboardException("Pokémon's leaderboard is temporarily limiting requests. Try again in a minute.")
            } catch (e: LeaderboardException) {
                throw LeaderboardException("Couldn't reach the Pokémon leaderboard. Check your connection and try again.")
            }
            if (first.records.isEmpty()) throw LeaderboardException("The ${division.label} leaderboard is empty right now.")
            val perPage = first.records.size
            val total = first.total ?: first.records.size
            val pageCount = first.pageCount?.takeIf { it > 0 } ?: ceil(total / perPage.toDouble()).toInt()
            Log.d("Leaderboard", "${division.slug}: total=$total perPage=$perPage pages=$pageCount")

            val collected = ConcurrentHashMap<String, LeaderboardPlayer>()
            first.records.forEach { collected[it.key] = it }
            onProgress(collected.values.toList(), total)

            val failed = AtomicInteger(0)
            val progressMutex = Mutex()
            if (pageCount > 1) {
                val semaphore = Semaphore(CONCURRENCY)
                coroutineScope {
                    (2..pageCount).map { page ->
                        async {
                            semaphore.withPermit {
                                val result = runCatching { fetchPage(page, REQUESTED_PER_PAGE) }
                                result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                                val records = result.getOrNull()?.records
                                if (records == null) failed.incrementAndGet() else records.forEach { collected[it.key] = it }
                                progressMutex.withLock { onProgress(collected.values.toList(), total) }
                            }
                        }
                    }.awaitAll()
                }
            }
            val players = withContext(Dispatchers.Default) { collected.values.sortedBy { it.rank } }
            val index = LeaderboardIndex(
                division = division.slug,
                fetchedAt = System.currentTimeMillis(),
                total = total,
                complete = failed.get() == 0,
                players = players,
            )
            Log.d("Leaderboard", "${division.slug}: indexed ${players.size}/$total (failed pages ${failed.get()})")
            save(activity.applicationContext, division, index)
            return index
        } finally {
            web?.close()
        }
    }

    private fun parse(text: String): Page {
        val root = LimitlessClient.json.parseToJsonElement(text).jsonObject
        val board = root["leaderboard"] as? JsonObject
        val meta = (root["_metadata"] as? JsonObject) ?: (board?.get("_metadata") as? JsonObject)
        val boardMeta = board?.get("_metadata") as? JsonObject
        val records = (board?.get("records") as? JsonArray) ?: (root["records"] as? JsonArray) ?: JsonArray(emptyList())
        val players = records.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val rank = o.int("rank") ?: return@mapNotNull null
            val name = o.str("screen_name") ?: o.str("name") ?: return@mapNotNull null
            LeaderboardPlayer(
                rank = rank,
                name = name,
                points = o.int("score") ?: 0,
                country = o.str("country_name"),
                countryCode = o.str("country"),
                inCutoff = (o["in_cutoff"] as? JsonPrimitive)?.contentOrNull == "true",
            )
        }
        fun metaInt(key: String) = meta?.int(key) ?: boardMeta?.int(key)
        return Page(players, metaInt("total_count"), metaInt("page_count"), metaInt("per_page"))
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.int(key: String): Int? {
        val p = this[key] as? JsonPrimitive ?: return null
        return p.intOrNull ?: p.doubleOrNull?.toInt() ?: p.contentOrNull?.toIntOrNull()
    }

    // ---------- Search ----------

    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.US)

    private fun tokens(s: String): List<String> = normalize(s).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /** Players whose name contains every token of [query], best rank first. */
    fun search(players: List<LeaderboardPlayer>, normalizedNames: List<String>, query: String, limit: Int = 200): List<LeaderboardPlayer> {
        val q = tokens(query)
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<LeaderboardPlayer>()
        for (i in players.indices) {
            val n = normalizedNames.getOrNull(i) ?: normalize(players[i].name)
            if (q.all { n.contains(it) }) out += players[i]
        }
        out.sortBy { it.rank }
        return if (out.size > limit) out.subList(0, limit) else out
    }

    // ---------- Hidden WebView session ----------

    /** Passes the site's bot check in a real browser context, then issues same-origin fetches from the page. */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private class WebFetcher(private val activity: Activity) {
        private val handler = Handler(Looper.getMainLooper())
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
        private val ids = AtomicInteger(0)
        private var web: WebView? = null
        private var ready = CompletableDeferred<Unit>()

        suspend fun open(url: String) = withContext(Dispatchers.Main) {
            val view = WebView(activity)
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.loadsImagesAutomatically = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
            view.addJavascriptInterface(object {
                @JavascriptInterface
                fun post(id: Int, body: String) {
                    pending.remove(id)?.complete(body)
                }
            }, "TCGLeaderboard")
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, u: String?) {
                    // The bot check serves an interstitial that reloads itself; the real page defines pokemonUI.
                    v.evaluateJavascript("(function(){return !!(window.pokemonUI||document.querySelector('#stats-leaderboards-selected-rankings'));})()") { r ->
                        if (r == "true") ready.complete(Unit)
                    }
                }
            }
            view.alpha = 0.01f
            (activity.window.decorView as ViewGroup).addView(view, FrameLayout.LayoutParams(1, 1))
            web = view
            view.loadUrl(url)
            // Even if the marker never shows, try fetching after the timeout: cookies may already be set.
            withTimeoutOrNull(25_000) { ready.await() }
            Unit
        }

        suspend fun get(url: String): String {
            val id = ids.incrementAndGet()
            val deferred = CompletableDeferred<String>()
            pending[id] = deferred
            val js = """
                (function(){
                  fetch(${jsString(url)}, {credentials:'include', headers:{'Accept':'application/json, text/plain, */*','X-Requested-With':'XMLHttpRequest'}})
                    .then(function(r){return r.text();})
                    .then(function(t){TCGLeaderboard.post($id, t);})
                    .catch(function(){TCGLeaderboard.post($id, '');});
                })();
            """.trimIndent()
            withContext(Dispatchers.Main) { web?.evaluateJavascript(js, null) }
            return withTimeoutOrNull(30_000) { deferred.await() } ?: run { pending.remove(id); "" }
        }

        fun close() {
            handler.post {
                web?.let {
                    it.stopLoading()
                    (activity.window.decorView as ViewGroup).removeView(it)
                    it.destroy()
                }
                web = null
                pending.values.forEach { it.complete("") }
                pending.clear()
            }
        }

        private fun jsString(s: String): String =
            "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
    }
}
