package com.rork.tcgstatsandroid.data

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

class LeaderboardException(message: String) : Exception(message)

enum class Division(val slug: String, val label: String) {
    Masters("tcg-master", "Masters"),
    Seniors("tcg-senior", "Seniors"),
    Juniors("tcg-junior", "Juniors");

    /** The division's public leaderboard page (Masters lives at `tcg-masters/`, the others at their API slug). */
    val pageUrl: String get() = "https://www.pokemon.com/us/play-pokemon/leaderboards/${if (this == Masters) "tcg-masters" else slug}/"
    val league: String get() = slug.removePrefix("tcg-")
}

/** Play! Pokémon rating zones. Each zone has its own leaderboard with region-specific ranks. */
enum class LeaderboardRegion(val zone: String, val label: String) {
    Global("All", "Global"),
    NorthAmerica("REGION-NA", "US & Canada"),
    Europe("REGION-EU", "Europe"),
    LatinAmerica("REGION-LA", "Latin America"),
    Oceania("REGION-AP", "Oceania"),
    MiddleEast("REGION-SO", "Middle East & S. Africa");
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
    /** Season label shown in the UI, e.g. "2026". */
    val season: String? = null,
    /** Zone of [LeaderboardRegion] this index was built for. */
    val region: String = LeaderboardRegion.Global.zone,
)

/** A Championship Points season as listed in the leaderboard page's season picker. */
data class LeaderboardSeason(val id: String, val label: String)

/**
 * Downloads the Play! Pokémon TCG Championship Points leaderboard. The site has no name search, so the whole
 * division is indexed once (cached on disk for [CACHE_TTL_MS]) and searched locally.
 */
object Leaderboard {
    private const val API = "https://www.pokemon.com/us/play-pokemon/leaderboards/op/api/"
    private const val CACHE_TTL_MS = 12 * 3_600_000L
    private const val REQUESTED_PER_PAGE = 100
    private const val CONCURRENCY = 5

    fun pageUrl(division: Division): String = division.pageUrl

    /** Known season id (from the site's picker), used when the page can't be read. */
    private val FALLBACK_SEASON = LeaderboardSeason("60", "2026")

    /** What the live page selected by default, plus the API request its own script made with those values. */
    private class PageSetup(
        val ready: Boolean,
        val capturedUrl: String? = null,
        val capturedBody: String? = null,
        val type: String? = null,
        val zone: String? = null,
        val whenId: String? = null,
        val whenLabel: String? = null,
        val whenOptions: Map<String, String> = emptyMap(),
    )

    /** An API URL with its query split out so paging can be swapped. */
    private class Template(val base: String, val params: List<Pair<String, String>>) {
        fun url(page: Int, perPage: Int?): String {
            val items = params.filter { it.first != "page" && (it.first != "per_page" || perPage == null) }.toMutableList()
            if (perPage != null) items += "per_page" to perPage.toString()
            items += "page" to page.toString()
            if (items.none { it.first == "format" }) items += "format" to "json"
            return base + "?" + items.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }
        }

        val describe: String get() = url(1, null)
        fun param(name: String) = params.firstOrNull { it.first == name }?.second

        /** Same request with `zone` set to [zone] (added if the original had none). */
        fun withZone(zone: String): Template {
            val items = params.filter { it.first != "zone" }.toMutableList()
            val at = items.indexOfFirst { it.first == "include" || it.first == "format" }.takeIf { it >= 0 } ?: items.size
            items.add(at, "zone" to zone)
            return Template(base, items)
        }

        companion object {
            private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
            fun parse(url: String): Template? = runCatching {
                val uri = Uri.parse(url)
                val base = url.substringBefore('?')
                val params = uri.queryParameterNames.map { it to (uri.getQueryParameter(it) ?: "") }
                Template(base, params)
            }.getOrNull()
        }
    }

    /** Request URLs to try, best first: the page's own request, then ones rebuilt from its dropdown values. */
    private fun templates(division: Division, region: LeaderboardRegion, setup: PageSetup): List<Template> {
        val out = ArrayList<Template>()
        setup.capturedUrl?.let { Template.parse(it) }?.let { out += it.withZone(region.zone) }
        val type = setup.type ?: "championship"
        val whenId = setup.whenId ?: FALLBACK_SEASON.id
        fun build(product: Boolean, season: String?): Template {
            val items = mutableListOf("leaderboard_type" to type)
            if (season != null) items += "leaderboard_when" to season
            items += "leaderboard_who" to ""
            if (product) { items += "product" to "tcg"; items += "league" to division.league }
            items += "zone" to region.zone
            items += "include" to "metadata"
            items += "format" to "json"
            return Template("$API${division.slug}/", items)
        }
        out += build(true, whenId)
        out += build(false, whenId)
        out += build(true, null)
        return out.distinctBy { it.describe }
    }

    // ---------- Cache ----------

    private fun cacheFile(context: Context, division: Division, region: LeaderboardRegion): File {
        val suffix = if (region == LeaderboardRegion.Global) "" else "-${region.zone.lowercase(Locale.US)}"
        return File(context.filesDir, "leaderboard").apply { mkdirs() }.let { File(it, "${division.slug}$suffix-v3.json") }
    }

    suspend fun cached(context: Context, division: Division, region: LeaderboardRegion): LeaderboardIndex? = withContext(Dispatchers.IO) {
        val file = cacheFile(context, division, region)
        if (!file.exists()) return@withContext null
        runCatching { LimitlessClient.json.decodeFromString(LeaderboardIndex.serializer(), file.readText()) }.getOrNull()
            ?.takeIf { it.division == division.slug && it.region == region.zone }
    }

    fun isFresh(index: LeaderboardIndex): Boolean =
        index.complete && System.currentTimeMillis() - index.fetchedAt < CACHE_TTL_MS

    private suspend fun save(context: Context, division: Division, region: LeaderboardRegion, index: LeaderboardIndex) = withContext(Dispatchers.IO) {
        runCatching {
            val file = cacheFile(context, division, region)
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
     * Every request runs inside a hidden WebView on the division's real page, so it carries the same bot-check
     * cookies and dropdown defaults as a visitor's browser.
     */
    suspend fun load(
        activity: Activity,
        division: Division,
        region: LeaderboardRegion,
        onProgress: suspend (List<LeaderboardPlayer>, Int) -> Unit,
    ): LeaderboardIndex {
        val web = WebFetcher(activity)

        suspend fun fetchPage(template: Template, page: Int, perPage: Int?, attempts: Int = 3): Page {
            val url = template.url(page, perPage)
            var attempt = 0
            while (true) {
                try {
                    val text = web.get(url)
                    if (!text.trimStart().startsWith("{")) {
                        if (attempt + 1 >= attempts) Log.d("Leaderboard", "page $page failed: $url -> ${text.take(160)}")
                        throw BlockedException()
                    }
                    return parse(text, page)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    attempt++
                    if (attempt >= attempts) throw if (e is BlockedException) e else LeaderboardException(e.message ?: "")
                    delay(1_200L * attempt)
                }
            }
        }

        try {
            val setup = web.open(division.pageUrl)
            Log.d("Leaderboard", "${division.slug} page ready=${setup.ready} type=${setup.type} when=${setup.whenId} (${setup.whenLabel}) zone=${setup.zone}")
            Log.d("Leaderboard", "captured: ${setup.capturedUrl ?: "none"} body=${setup.capturedBody?.take(200) ?: "none"}")
            if (!setup.ready) {
                throw LeaderboardException("Pokémon's leaderboard page didn't load. It may be limiting requests, so try again in a minute.")
            }

            var first: Page? = null
            var chosen: Pair<Template, Int?>? = null
            var responded = false
            probe@ for (template in templates(division, region, setup)) {
                // The site's own picker tops out at 100 rows; larger sizes come back empty, so never trust those.
                val sizes: List<Int?> = listOf(REQUESTED_PER_PAGE, null)
                for (size in sizes) {
                    try {
                        val page = fetchPage(template, 1, size, attempts = 2)
                        responded = true
                        Log.d("Leaderboard", "probe ${template.describe} per_page=${size ?: "page"} -> ${page.records.size} records, total ${page.total}")
                        if (page.records.isNotEmpty()) { first = page; chosen = template to size; break@probe }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        continue
                    }
                }
            }
            if (first == null || chosen == null) {
                throw LeaderboardException(
                    if (responded) "The ${division.label} ${if (region == LeaderboardRegion.Global) "" else "${region.label} "}leaderboard came back empty (page ${if (setup.capturedUrl == null) "sent no request" else "request seen"}, season ${setup.whenLabel ?: "?"})."
                    else "Pokémon's leaderboard didn't respond. It may be limiting requests, so try again in a minute."
                )
            }
            val (template, size) = chosen
            val seasonLabel = template.param("leaderboard_when")?.let { setup.whenOptions[it] } ?: setup.whenLabel
            val perPage = first.records.size
            val total = first.total ?: first.records.size
            val pageCount = first.pageCount?.takeIf { it > 0 } ?: ceil(total / perPage.toDouble()).toInt()
            Log.d("Leaderboard", "${division.slug}: total=$total perPage=$perPage pages=$pageCount season=$seasonLabel")

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
                                val result = runCatching { fetchPage(template, page, size) }
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
                season = seasonLabel,
                region = region.zone,
            )
            Log.d("Leaderboard", "${division.slug}: indexed ${players.size}/$total (failed pages ${failed.get()})")
            save(activity.applicationContext, division, region, index)
            return index
        } finally {
            web.close()
        }
    }

    private fun parse(text: String, page: Int): Page {
        val root = LimitlessClient.json.parseToJsonElement(text).jsonObject
        val board = root["leaderboard"] as? JsonObject
        val meta = (root["_metadata"] as? JsonObject) ?: (board?.get("_metadata") as? JsonObject)
        val boardMeta = board?.get("_metadata") as? JsonObject
        val records = (board?.get("records") as? JsonArray) ?: (root["records"] as? JsonArray) ?: JsonArray(emptyList())
        val perPage = meta?.int("per_page") ?: boardMeta?.int("per_page") ?: records.size
        val players = records.mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val rank = o.int("rank") ?: ((page - 1) * perPage + i + 1)
            val name = (o.str("screen_name") ?: o.str("name"))?.replace("\\'", "'") ?: return@mapIndexedNotNull null
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

    /** Records every leaderboard API request the page's own script makes (and its response). */
    private const val HOOK_JS = """
    (function(){
      if (window.__lbHooked) return; window.__lbHooked = true; window.__lbReqs = [];
      var open = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function(m, u) {
        try {
          var abs = new URL(u, location.href).href;
          if (abs.indexOf('leaderboard_type') >= 0) {
            var entry = {url: abs, body: null}; window.__lbReqs.push(entry);
            var xhr = this;
            this.addEventListener('loadend', function(){ try { entry.body = xhr.responseText || ''; } catch(e) { entry.body = ''; } });
          }
        } catch (e) {}
        return open.apply(this, arguments);
      };
    })();
    """

    /** Waits for the page's first leaderboard request, then reports it with the dropdowns' selected values. */
    private const val DISCOVER_JS = """
    (async function(){
      const pick = () => {
        const reqs = (window.__lbReqs || []).filter(r => r.url.indexOf('isUserStats') < 0);
        return reqs.length ? reqs[reqs.length - 1] : null;
      };
      for (let i = 0; i < 30; i++) {
        const r = pick();
        if (r && r.body !== null) break;
        await new Promise(res => setTimeout(res, 500));
      }
      const r = pick();
      const val = id => { const s = document.getElementById(id); return s ? s.value : null; };
      const w = document.getElementById('leaderboardWhenFilter');
      const opt = w && w.selectedIndex >= 0 ? w.options[w.selectedIndex] : null;
      return JSON.stringify({
        url: r ? r.url : null,
        body: r && r.body ? r.body.slice(0, 4000) : null,
        type: val('leaderboardTypeFilter'),
        zone: val('leaderboardZoneFilter'),
        when: w ? w.value : null,
        whenLabel: opt ? opt.text.trim() : null,
        options: w ? Array.from(w.options).map(o => [o.value, o.text.trim()]) : []
      });
    })()
    """

    /** Passes the site's bot check in a real browser context, then issues same-origin fetches from the page. */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private class WebFetcher(private val activity: Activity) {
        private val handler = Handler(Looper.getMainLooper())
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
        private val ids = AtomicInteger(0)
        private var web: WebView? = null
        private val ready = CompletableDeferred<Unit>()

        suspend fun open(url: String): PageSetup {
            withContext(Dispatchers.Main) {
                // The hidden page sits on top of the app, so it must never receive touches: otherwise taps fall
                // through to the site's own dropdowns (region / leaderboard type / season) and open their pickers.
                val view = object : WebView(activity) {
                    override fun dispatchTouchEvent(event: android.view.MotionEvent?): Boolean = false
                    @SuppressLint("ClickableViewAccessibility")
                    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean = false
                    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent?): Boolean = false
                }
                view.isClickable = false
                view.isLongClickable = false
                view.isFocusable = false
                view.isFocusableInTouchMode = false
                view.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
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
                val documentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                if (documentStart) {
                    runCatching { WebViewCompat.addDocumentStartJavaScript(view, HOOK_JS, setOf("https://www.pokemon.com")) }
                }
                view.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(v: WebView, u: String?, favicon: android.graphics.Bitmap?) {
                        if (!documentStart) v.evaluateJavascript(HOOK_JS, null)
                    }

                    override fun onPageFinished(v: WebView, u: String?) {
                        // The bot check serves an interstitial that reloads itself; the real page has the rankings container.
                        v.evaluateJavascript("(function(){return !!document.querySelector('#stats-leaderboards-selected-rankings') && !!window.jQuery;})()") { r ->
                            if (r == "true") ready.complete(Unit)
                        }
                    }
                }
                view.alpha = 0.01f
                (activity.window.decorView as ViewGroup).addView(view, FrameLayout.LayoutParams(390, 844))
                web = view
                view.loadUrl(url)
            }
            withTimeoutOrNull(30_000) { ready.await() } ?: return PageSetup(ready = false)

            repeat(3) {
                val json = runAsync(DISCOVER_JS, 25_000)
                val o = runCatching { LimitlessClient.json.parseToJsonElement(json).jsonObject }.getOrNull()
                if (o == null) { delay(1_000); return@repeat }
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                val options = LinkedHashMap<String, String>()
                (o["options"] as? JsonArray)?.forEach { el ->
                    val pair = el as? JsonArray ?: return@forEach
                    val k = (pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@forEach
                    val v = (pair.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: return@forEach
                    options.putIfAbsent(k, v)
                }
                return PageSetup(
                    ready = true,
                    capturedUrl = s("url"),
                    capturedBody = s("body"),
                    type = s("type"),
                    zone = s("zone"),
                    whenId = s("when"),
                    whenLabel = s("whenLabel"),
                    whenOptions = options,
                )
            }
            return PageSetup(ready = true)
        }

        /** Runs a JS expression that yields a promise of a string and returns that string. */
        private suspend fun runAsync(expression: String, timeoutMs: Long): String {
            val id = ids.incrementAndGet()
            val deferred = CompletableDeferred<String>()
            pending[id] = deferred
            val js = "Promise.resolve($expression).then(function(t){TCGLeaderboard.post($id, String(t));}).catch(function(){TCGLeaderboard.post($id, '');});"
            withContext(Dispatchers.Main) { web?.evaluateJavascript(js, null) }
            return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: run { pending.remove(id); "" }
        }

        suspend fun get(url: String): String = runAsync(
            "fetch(${jsString(url)}, {credentials:'include', headers:{'Accept':'application/json, text/javascript, */*; q=0.01','X-Requested-With':'XMLHttpRequest'}}).then(function(r){return r.text();})",
            30_000,
        )

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
