package com.rork.tcgstatsandroid.data

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class EventKind(val label: String) {
    Prerelease("Prerelease"),
    LeagueCup("League Cup"),
    LeagueChallenge("League Challenge"),
    Championship("Championship"),
    Tournament("Tournament"),
    League("League Play"),
    Other("Event"),
}

enum class EventGame(val label: String) { Tcg("TCG"), Vgc("VGC"), Go("GO") }

enum class EventGameFilter(val label: String, val game: EventGame?) {
    All("All", null), Tcg("TCG", EventGame.Tcg), Vgc("VGC", EventGame.Vgc), Go("GO", EventGame.Go)
}

enum class EventTypeFilter(val label: String) { All("All"), Cp("CP events"), Other("Other") }

data class GeoPoint(val latitude: Double, val longitude: Double, val label: String)

data class LocalEvent(
    val id: String,
    val name: String,
    val kind: EventKind,
    val typeName: String?,
    val startMillis: Long?,
    val hasTime: Boolean,
    val timeZone: String?,
    val venue: String?,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val distanceMiles: Double?,
    val products: List<String>,
    val cost: String?,
    val link: String?,
) {
    /**
     * Games this event is for, from its listed products and name. Unknown formats count as TCG;
     * UNITE-only events have no game and only show under "All".
     */
    val games: Set<EventGame> by lazy {
        val result = mutableSetOf<EventGame>()
        fun scan(raw: String, fromName: Boolean) {
            val t = raw.lowercase(Locale.US)
            if (t.contains("tcg") || t.contains("trading card")) result += EventGame.Tcg
            if (t.contains("video game") || t.contains("videogame") || t.contains("vgc") || VG.containsMatchIn(t)) result += EventGame.Vgc
            if (t.contains("pokémon go") || t.contains("pokemon go") || (!fromName && GO.containsMatchIn(t))) result += EventGame.Go
        }
        products.forEach { scan(it, false) }
        scan(name, true)
        if (result.isEmpty()) {
            val unite = products.any { it.contains("unite", true) } || name.contains("unite", true)
            if (!unite) result += EventGame.Tcg
        }
        result
    }

    /** Short game label for rows, e.g. "TCG" or "TCG/VGC". */
    val gameLabel: String?
        get() = if (games.isEmpty()) {
            if (products.any { it.contains("unite", true) }) "UNITE" else null
        } else EventGame.entries.filter { it in games }.joinToString("/") { it.label }

    /** Championship Points events (League Challenges and League Cups). */
    val awardsCp: Boolean get() = kind == EventKind.LeagueCup || kind == EventKind.LeagueChallenge

    fun matches(game: EventGameFilter, type: EventTypeFilter): Boolean {
        game.game?.let { if (it !in games) return false }
        return when (type) {
            EventTypeFilter.All -> true
            EventTypeFilter.Cp -> awardsCp
            EventTypeFilter.Other -> !awardsCp
        }
    }

    private companion object {
        val VG = Regex("""\bvg\b""")
        val GO = Regex("""\bgo\b""")
    }

    private fun zone(): TimeZone = timeZone?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getDefault()

    fun dayKey(): String = startMillis?.let { format("yyyy-MM-dd", it) } ?: "unknown"
    fun dayLabel(): String = startMillis?.let { format("EEEE, MMM d", it) } ?: "Date to be announced"
    fun timeLabel(): String? = if (hasTime && startMillis != null) format("h:mm a", startMillis) else null
    fun fullDateLabel(): String = startMillis?.let { format(if (hasTime) "EEE, MMM d · h:mm a" else "EEE, MMM d", it) } ?: "Date TBA"
    fun monthAbbrev(): String = startMillis?.let { format("MMM", it).uppercase(Locale.US) } ?: "TBA"
    fun dayNumber(): String = startMillis?.let { format("d", it) } ?: "–"

    private fun format(pattern: String, millis: Long): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone() }.format(Date(millis))
}

class EventLocatorException(message: String) : Exception(message)

object EventLocator {
    val distanceOptions = listOf(10, 25, 50, 100)

    fun pageUrl(lat: Double, lng: Double, rangeMiles: Int, start: Date = Date()): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(start)
        return "https://events.pokemon.com/EventLocator/Home?locale=en-US&latitude=$lat&longitude=$lng" +
            "&range=$rangeMiles&startdate=$day&iskm=false&filters="
    }

    // ---------- Geocoding ----------

    suspend fun geocode(context: Context, rawQuery: String): GeoPoint = withContext(Dispatchers.IO) {
        val query = rawQuery.trim()
        if (query.isEmpty()) throw EventLocatorException("Enter a zip code or city.")
        if (Regex("^\\d{5}(-\\d{4})?$").matches(query)) {
            zippopotam(query.take(5))?.let { return@withContext it }
        }
        platformGeocode(context, query)?.let { return@withContext it }
        openMeteo(query)?.let { return@withContext it }
        throw EventLocatorException("Couldn't find \"$query\". Try a 5-digit zip or \"City, State\".")
    }

    private suspend fun zippopotam(zip: String): GeoPoint? = runCatching {
        val body = LimitlessClient.httpGet("https://api.zippopotam.us/us/$zip", "application/json")
        val root = LimitlessClient.json.parseToJsonElement(body).jsonObject
        val place = root["places"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val lat = place["latitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
        val lng = place["longitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
        val city = place["place name"]?.jsonPrimitive?.contentOrNull
        val state = place["state abbreviation"]?.jsonPrimitive?.contentOrNull
        GeoPoint(lat, lng, listOfNotNull(city, state).joinToString(", ").ifEmpty { zip })
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun platformGeocode(context: Context, query: String): GeoPoint? = runCatching {
        if (!Geocoder.isPresent()) return null
        val result = Geocoder(context, Locale.US).getFromLocationName(query, 1)?.firstOrNull() ?: return null
        val label = listOfNotNull(result.locality ?: result.subAdminArea, result.adminArea).joinToString(", ").ifEmpty { query }
        GeoPoint(result.latitude, result.longitude, label)
    }.getOrNull()

    private suspend fun openMeteo(query: String): GeoPoint? = runCatching {
        val parts = query.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val name = parts.firstOrNull() ?: return null
        val region = parts.getOrNull(1)
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(name, "UTF-8")}&count=10&language=en&format=json"
        val root = LimitlessClient.json.parseToJsonElement(LimitlessClient.httpGet(url, "application/json")).jsonObject
        val results = root["results"]?.jsonArray?.map { it.jsonObject } ?: return null
        if (results.isEmpty()) return null
        val wantedState = region?.let { US_STATES[it.uppercase(Locale.US)] ?: it }
        val pick = results.firstOrNull { r ->
            wantedState != null && (r.str("admin1")?.equals(wantedState, true) == true || r.str("country")?.equals(wantedState, true) == true)
        } ?: results.firstOrNull { it.str("country_code") == "US" && region == null } ?: results.first()
        val lat = pick["latitude"]?.jsonPrimitive?.doubleOrNull ?: return null
        val lng = pick["longitude"]?.jsonPrimitive?.doubleOrNull ?: return null
        GeoPoint(lat, lng, listOfNotNull(pick.str("name"), pick.str("admin1")).joinToString(", "))
    }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    // ---------- Fetching (hidden WebView) ----------

    // The Event Locator only runs its event search on load when its stored client variables say a search is in
    // progress (AreEventsBeingSearched) in events mode (IsShowEvents). A fresh WebView has neither, so the page
    // would just show its landing state. This hook forces both on and captures the data responses; __tcgPrime
    // writes the variables to storage so a reload starts in search mode.
    private const val HOOK_JS = """
(function(){
  if (window.__tcgHooked) return; window.__tcgHooked = true;
  var D = String.fromCharCode(36), MARK = D + 'ClientVars' + D, FORCE = ['AreEventsBeingSearched', 'IsShowEvents'];
  var SP = Storage.prototype, og = SP.getItem, osi = SP.setItem;
  function forced(k){ k = String(k); var i = k.indexOf(MARK); if (i < 0) return false; return FORCE.indexOf(k.substring(i + MARK.length)) >= 0; }
  function truthy(v){ if (v === 'True' || v === 'False') return 'True'; if (v === '1' || v === '0') return '1'; return 'true'; }
  try { SP.getItem = function(k){ var v = og.apply(this, arguments); try { if (forced(k)) return truthy(v); } catch(e){} return v; }; } catch(e){}
  function prime(){ try { var s = window.localStorage, pre = null; for (var i = 0; i < s.length; i++){ var k = s.key(i), j = k.indexOf(MARK); if (j >= 0 && k.indexOf('EventLocator') >= 0){ pre = k.substring(0, j + MARK.length); break; } } if (!pre) return false; FORCE.forEach(function(n){ var v = og.call(s, pre + n); if (v !== truthy(v)) osi.call(s, pre + n, truthy(v)); }); return true; } catch(e){ return false; } }
  window.__tcgPrime = prime;
  prime();
  function send(u, b){ try { if (u && String(u).indexOf('screenservices') >= 0 && window.TCGEvents) { window.TCGEvents.post(JSON.stringify({url:String(u), body:String(b)})); } } catch(e){} }
  var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(m, u){ this.__tcgUrl = u; return oo.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function(){ var x = this; x.addEventListener('load', function(){ try { send(x.responseURL || x.__tcgUrl, x.responseText); } catch(e){} }); return os.apply(this, arguments); };
  if (window.fetch) { var of = window.fetch; window.fetch = function(){ var a = arguments; return of.apply(this, a).then(function(r){ try { var u = r.url || (a[0] && a[0].url) || a[0]; r.clone().text().then(function(t){ send(u, t); }); } catch(e){} return r; }); }; }
})();
"""

    data class Captured(val url: String, val body: String)

    // On a first visit the site's bot check sets its cookies while the page loads, so the page's own data requests
    // can come back empty. If no event list arrives, the page is reloaded (cookies and search state are in place
    // by then), which is what a second manual search used to do.
    private const val MAX_RELOADS = 2
    private const val RELOAD_AFTER_MS = 9_000L

    /** Loads the official Event Locator in an invisible WebView and captures its data responses. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchResponses(activity: Activity, url: String, timeoutMs: Long = 50_000): List<Captured> = withContext(Dispatchers.Main) {
        val captured = mutableListOf<Captured>()
        val finished = CompletableDeferred<Unit>()
        val handler = Handler(Looper.getMainLooper())
        val finish = Runnable { finished.complete(Unit) }
        var sawEventList = false
        var sawLocations = false
        var reloads = 0

        val web = WebView(activity)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadsImagesAutomatically = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        val documentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (documentStart) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(web, HOOK_JS, setOf("https://events.pokemon.com")) }
        }

        lateinit var watchdog: Runnable
        watchdog = Runnable {
            if (finished.isCompleted || sawEventList) return@Runnable
            if (reloads >= MAX_RELOADS) {
                // Out of retries: settle for whatever arrived (e.g. stores only).
                if (sawLocations) { handler.removeCallbacks(finish); handler.postDelayed(finish, 3_000) }
                return@Runnable
            }
            reloads++
            Log.d("Events", "no event list yet, reloading ($reloads/$MAX_RELOADS)")
            handler.removeCallbacks(finish)
            sawLocations = false
            web.evaluateJavascript("window.__tcgPrime ? window.__tcgPrime() : false") {
                if (finished.isCompleted) return@evaluateJavascript
                web.loadUrl(url)
                handler.postDelayed(watchdog, RELOAD_AFTER_MS)
            }
        }

        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun post(message: String) {
                handler.post {
                    val obj = runCatching { LimitlessClient.json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return@post
                    val u = obj.str("url") ?: return@post
                    val b = obj.str("body") ?: return@post
                    captured += Captured(u, b)
                    Log.d("Events", "captured ${u.substringAfterLast('/')} (${b.length} bytes)")
                    if (u.contains("GetEventList", true)) {
                        sawEventList = true
                        handler.removeCallbacks(watchdog)
                        // Event lists arrive in batches; finish once they go quiet.
                        handler.removeCallbacks(finish)
                        handler.postDelayed(finish, 3_000)
                    } else if (u.contains("GetLocations", true)) {
                        sawLocations = true
                        if (reloads >= MAX_RELOADS && !sawEventList) {
                            handler.removeCallbacks(finish)
                            handler.postDelayed(finish, 10_000)
                        }
                    }
                }
            }
        }, "TCGEvents")
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!documentStart) view.evaluateJavascript(HOOK_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(HOOK_JS, null)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (!documentStart) view.evaluateJavascript(HOOK_JS, null)
            }
        }
        web.alpha = 0.01f
        val root = activity.window.decorView as ViewGroup
        root.addView(web, FrameLayout.LayoutParams(1, 1))
        try {
            web.loadUrl(url)
            handler.postDelayed(watchdog, RELOAD_AFTER_MS)
            withTimeoutOrNull(timeoutMs) { finished.await() }
        } finally {
            handler.removeCallbacks(finish)
            handler.removeCallbacks(watchdog)
            finished.complete(Unit)
            web.stopLoading()
            root.removeView(web)
            web.destroy()
        }
        captured.toList()
    }

    suspend fun search(activity: Activity, point: GeoPoint, rangeMiles: Int): List<LocalEvent> {
        val responses = fetchResponses(activity, pageUrl(point.latitude, point.longitude, rangeMiles))
        if (responses.isEmpty()) throw EventLocatorException("Pokémon's event site didn't respond. Try again, or open it on Pokémon.com.")
        val events = withContext(Dispatchers.Default) { parse(responses, point, rangeMiles) }
        Log.d("Events", "${responses.size} responses -> ${events.size} events")
        return events
    }

    // ---------- Parsing ----------

    fun parse(responses: List<Captured>, origin: GeoPoint, rangeMiles: Int): List<LocalEvent> {
        val byId = LinkedHashMap<String, LocalEvent>()
        for (response in responses) {
            val root = runCatching { LimitlessClient.json.parseToJsonElement(response.body) }.getOrNull() ?: continue
            val strict = response.url.contains("Premier", true) || response.url.contains("Related", true)
            walk(root, null) { obj, parent ->
                val event = toEvent(obj, parent, origin) ?: return@walk
                val d = event.distanceMiles
                if (d != null && d > rangeMiles + 1) return@walk
                if (d == null && strict) return@walk
                if (!byId.containsKey(event.id)) byId[event.id] = event
            }
        }
        val now = System.currentTimeMillis() - 12 * 3_600_000L
        return byId.values
            .filter { it.startMillis == null || it.startMillis >= now }
            .sortedWith(compareBy<LocalEvent> { it.startMillis ?: Long.MAX_VALUE }.thenBy { it.distanceMiles ?: Double.MAX_VALUE })
    }

    private fun norm(key: String) = key.lowercase(Locale.US).replace("_", "")

    private fun JsonObject.field(vararg keys: String): JsonElement? {
        val wanted = keys.map(::norm)
        for (w in wanted) {
            for ((k, v) in this) if (norm(k) == w && v !is kotlinx.serialization.json.JsonNull) return v
        }
        return null
    }

    private fun JsonObject.text(vararg keys: String): String? {
        val p = field(*keys) as? JsonPrimitive ?: return null
        val s = p.contentOrNull?.trim() ?: return null
        return s.takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun JsonObject.obj(vararg keys: String): JsonObject? = field(*keys) as? JsonObject

    private fun JsonObject.number(vararg keys: String): Double? {
        val p = field(*keys) as? JsonPrimitive ?: return null
        return p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull()
    }

    private fun walk(element: JsonElement, parent: JsonObject?, visit: (JsonObject, JsonObject?) -> Unit) {
        when (element) {
            is JsonObject -> {
                if (isEvent(element)) {
                    visit(element, parent)
                    return
                }
                for (child in element.values) walk(child, element, visit)
            }
            is JsonArray -> element.forEach { walk(it, parent, visit) }
            else -> Unit
        }
    }

    private fun isEvent(o: JsonObject): Boolean {
        val name = o.text("name") ?: return false
        if (name.isBlank()) return false
        val start = o.text("start_datetime", "start_date", "startdate", "when") ?: return false
        return !start.startsWith("1900")
    }

    private fun toEvent(o: JsonObject, parent: JsonObject?, origin: GeoPoint): LocalEvent? {
        val name = o.text("name") ?: return null
        val startRaw = o.text("start_datetime", "start_date", "startdate", "when") ?: return null
        val address = o.obj("address", "location")
        val group = o.obj("activityGroup", "activity_group", "store")
        val typeName = parent?.text("EventTypeName", "EvenTypeName", "EventBadge") ?: o.text("EventType", "event_type")

        val lat = address?.number("latitude", "lat") ?: o.number("latitude", "lat")
        val lng = address?.number("longitude", "lng", "lon") ?: o.number("longitude", "lng", "lon")
        val validCoords = lat != null && lng != null && !(lat == 0.0 && lng == 0.0)
        val distance = if (validCoords) haversineMiles(origin.latitude, origin.longitude, lat!!, lng!!) else null

        val tz = address?.text("timezone", "time_zone") ?: o.text("timezone", "time_zone")
        val (millis, hasTime) = parseDate(startRaw, tz) ?: (null to false)

        val venue = address?.text("name", "venue_name") ?: o.text("VenueName", "venue_name") ?: group?.text("display_name", "name")
        val fullAddress = address?.text("full_address", "formatted_address")
            ?: listOfNotNull(
                o.text("StreetAddress", "street_address") ?: address?.text("street_address", "address1"),
                o.text("City") ?: address?.text("city"),
                listOfNotNull(o.text("State") ?: address?.text("state"), o.text("PostalCode") ?: address?.text("postal_code")).joinToString(" ").ifBlank { null },
            ).joinToString(", ").ifBlank { null }

        val products = mutableListOf<String>()
        o.field("products", "product")?.let { collectStrings(it, products) }
        val tags = mutableListOf<String>()
        o.field("tags")?.let { collectStrings(it, tags) }
        val metadata = o.obj("metadata")
        val text = listOfNotNull(typeName, o.text("activity_type", "type"), metadata?.text("category"), name)
            .plus(tags).joinToString(" ").lowercase(Locale.US)

        val id = o.text("guid", "EventGUID", "id", "local_id") ?: "$name|$startRaw|${venue.orEmpty()}"
        return LocalEvent(
            id = id,
            name = name,
            kind = classify(text),
            typeName = typeName,
            startMillis = millis,
            hasTime = hasTime,
            timeZone = tz,
            venue = venue,
            address = fullAddress,
            latitude = if (validCoords) lat else null,
            longitude = if (validCoords) lng else null,
            distanceMiles = distance,
            products = products.distinct(),
            cost = o.text("Cost", "cost") ?: metadata?.text("cost"),
            link = o.text("RegistrationLink", "pokemon_url", "event_website") ?: metadata?.text("event_website"),
        )
    }

    private fun collectStrings(e: JsonElement, out: MutableList<String>) {
        when (e) {
            is JsonPrimitive -> e.contentOrNull?.takeIf { it.isNotBlank() && e.isString }?.let(out::add)
            is JsonArray -> e.forEach { collectStrings(it, out) }
            is JsonObject -> e.values.forEach { collectStrings(it, out) }
        }
    }

    private fun classify(text: String): EventKind = when {
        "prerelease" in text || "pre-release" in text -> EventKind.Prerelease
        "league cup" in text || "league_cup" in text || "leaguecup" in text -> EventKind.LeagueCup
        "league challenge" in text || "league_challenge" in text || "leaguechallenge" in text -> EventKind.LeagueChallenge
        "regional" in text || "international" in text || "world" in text || "special event" in text ||
            "special_event" in text || "championship" in text -> EventKind.Championship
        "tournament" in text || "premier" in text -> EventKind.Tournament
        "league" in text || "play session" in text || "play_session" in text || "casual" in text -> EventKind.League
        else -> EventKind.Other
    }

    /** Returns epoch millis and whether the source included a time of day. */
    fun parseDate(raw: String, zone: String?): Pair<Long, Boolean>? {
        var s = raw.trim().replace(" ", "T").replace(Regex("\\.\\d+"), "")
        if (s.endsWith("Z")) s = s.dropLast(1) + "+00:00"
        if (s.contains("T")) {
            Regex("([+-]\\d{2})(\\d{2})$").find(s)?.let { m -> s = s.replaceRange(m.range, "${m.groupValues[1]}:${m.groupValues[2]}") }
        }
        val tz = zone?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getTimeZone("UTC")
        val withOffset = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        runCatching { withOffset.parse(s)?.time }.getOrNull()?.let { return it to true }
        val local = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = tz }
        runCatching { local.parse(s)?.time }.getOrNull()?.let { return it to true }
        val localMin = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = tz }
        runCatching { localMin.parse(s)?.time }.getOrNull()?.let { return it to true }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getDefault() }
        runCatching { day.parse(s.take(10))?.time }.getOrNull()?.let { return it to false }
        return null
    }

    fun haversineMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 3958.8
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private val US_STATES = mapOf(
        "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas", "CA" to "California", "CO" to "Colorado",
        "CT" to "Connecticut", "DE" to "Delaware", "FL" to "Florida", "GA" to "Georgia", "HI" to "Hawaii", "ID" to "Idaho",
        "IL" to "Illinois", "IN" to "Indiana", "IA" to "Iowa", "KS" to "Kansas", "KY" to "Kentucky", "LA" to "Louisiana",
        "ME" to "Maine", "MD" to "Maryland", "MA" to "Massachusetts", "MI" to "Michigan", "MN" to "Minnesota",
        "MS" to "Mississippi", "MO" to "Missouri", "MT" to "Montana", "NE" to "Nebraska", "NV" to "Nevada",
        "NH" to "New Hampshire", "NJ" to "New Jersey", "NM" to "New Mexico", "NY" to "New York", "NC" to "North Carolina",
        "ND" to "North Dakota", "OH" to "Ohio", "OK" to "Oklahoma", "OR" to "Oregon", "PA" to "Pennsylvania",
        "RI" to "Rhode Island", "SC" to "South Carolina", "SD" to "South Dakota", "TN" to "Tennessee", "TX" to "Texas",
        "UT" to "Utah", "VT" to "Vermont", "VA" to "Virginia", "WA" to "Washington", "WV" to "West Virginia",
        "WI" to "Wisconsin", "WY" to "Wyoming", "DC" to "District of Columbia",
    )
}
