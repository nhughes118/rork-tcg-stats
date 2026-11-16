import CoreLocation
import Foundation
import UIKit
import WebKit

nonisolated struct EventLocatorError: LocalizedError, Sendable {
    let message: String
    var errorDescription: String? { message }
}

/// Finds Pokémon events near a place using the official Event Locator (events.pokemon.com).
nonisolated enum EventLocator {
    static let distanceOptions = [10, 25, 50, 100]

    static func pageURL(latitude: Double, longitude: Double, rangeMiles: Int, start: Date = .now) -> URL {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        let day = f.string(from: start)
        let s = "https://events.pokemon.com/EventLocator/Home?locale=en-US&latitude=\(latitude)&longitude=\(longitude)"
            + "&range=\(rangeMiles)&startdate=\(day)&iskm=false&filters="
        return URL(string: s)!
    }

    // MARK: Geocoding

    static func geocode(_ raw: String) async throws -> GeoPoint {
        let query = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { throw EventLocatorError(message: "Enter a zip code or city.") }
        if query.range(of: #"^\d{5}(-\d{4})?$"#, options: .regularExpression) != nil,
           let point = await zippopotam(String(query.prefix(5))) {
            return point
        }
        if let point = await platformGeocode(query) { return point }
        if let point = await openMeteo(query) { return point }
        throw EventLocatorError(message: "Couldn't find \"\(query)\". Try a 5-digit zip or \"City, State\".")
    }

    private static func getJSON(_ url: URL) async -> Any? {
        var req = URLRequest(url: url, timeoutInterval: 20)
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return nil }
        return try? JSONSerialization.jsonObject(with: data)
    }

    private static func zippopotam(_ zip: String) async -> GeoPoint? {
        guard let root = await getJSON(URL(string: "https://api.zippopotam.us/us/\(zip)")!) as? [String: Any],
              let place = (root["places"] as? [[String: Any]])?.first,
              let lat = Double(place["latitude"] as? String ?? ""),
              let lng = Double(place["longitude"] as? String ?? "") else { return nil }
        let label = [place["place name"] as? String, place["state abbreviation"] as? String].compactMap { $0 }.joined(separator: ", ")
        return GeoPoint(latitude: lat, longitude: lng, label: label.isEmpty ? zip : label)
    }

    private static func platformGeocode(_ query: String) async -> GeoPoint? {
        guard let placemark = try? await CLGeocoder().geocodeAddressString(query).first,
              let loc = placemark.location else { return nil }
        let label = [placemark.locality ?? placemark.subAdministrativeArea, placemark.administrativeArea]
            .compactMap { $0 }.joined(separator: ", ")
        return GeoPoint(latitude: loc.coordinate.latitude, longitude: loc.coordinate.longitude, label: label.isEmpty ? query : label)
    }

    private static func openMeteo(_ query: String) async -> GeoPoint? {
        let parts = query.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        guard let name = parts.first else { return nil }
        let region = parts.count > 1 ? parts[1] : nil
        var comps = URLComponents(string: "https://geocoding-api.open-meteo.com/v1/search")!
        comps.queryItems = [.init(name: "name", value: name), .init(name: "count", value: "10"),
                            .init(name: "language", value: "en"), .init(name: "format", value: "json")]
        guard let url = comps.url,
              let root = await getJSON(url) as? [String: Any],
              let results = root["results"] as? [[String: Any]], !results.isEmpty else { return nil }
        let wanted = region.map { usStates[$0.uppercased()] ?? $0 }
        let pick = results.first { r in
            guard let wanted else { return false }
            return (r["admin1"] as? String)?.caseInsensitiveCompare(wanted) == .orderedSame
                || (r["country"] as? String)?.caseInsensitiveCompare(wanted) == .orderedSame
        } ?? results.first { region == nil && ($0["country_code"] as? String) == "US" } ?? results[0]
        guard let lat = (pick["latitude"] as? NSNumber)?.doubleValue,
              let lng = (pick["longitude"] as? NSNumber)?.doubleValue else { return nil }
        let label = [pick["name"] as? String, pick["admin1"] as? String].compactMap { $0 }.joined(separator: ", ")
        return GeoPoint(latitude: lat, longitude: lng, label: label)
    }

    // MARK: Search

    struct SearchResult: Sendable {
        let events: [LocalEvent]
        let batches: Int
        let candidates: Int
    }

    @MainActor
    static func search(near point: GeoPoint, rangeMiles: Int) async throws -> SearchResult {
        let url = pageURL(latitude: point.latitude, longitude: point.longitude, rangeMiles: rangeMiles)
        let responses = await EventPageLoader().load(url)
        if responses.isEmpty {
            throw EventLocatorError(message: "Pokémon's event site didn't respond. Try again, or open it on Pokémon.com.")
        }
        let result = await Task.detached { await parse(responses, origin: point, rangeMiles: rangeMiles) }.value
        print("[Events] \(result.batches) responses -> \(result.candidates) candidates -> \(result.events.count) events")
        return result
    }

    // MARK: Parsing

    static func parse(_ responses: [EventPageLoader.Captured], origin: GeoPoint, rangeMiles: Int) async -> SearchResult {
        struct Raw {
            let o: [String: Any]
            let parent: [String: Any]?
            let strict: Bool
        }
        var raws: [Raw] = []
        for response in responses {
            guard let data = response.body.data(using: .utf8),
                  let root = try? JSONSerialization.jsonObject(with: data) else { continue }
            let strict = response.url.localizedCaseInsensitiveContains("Premier") || response.url.localizedCaseInsensitiveContains("Related")
            walk(root, parent: nil) { obj, parent in raws.append(Raw(o: obj, parent: parent, strict: strict)) }
        }
        // The Event Locator sends start times in the venue's local time with no marker, so the venue's zone
        // has to be resolved from coordinates. Warm the cache for every unique map cell up front, in parallel,
        // so building the events afterwards is instant.
        var cells: [(String, (Double, Double))] = []
        var seenCells = Set<String>()
        for r in raws {
            let lat = number(object(r.o, "address", "location"), "latitude", "lat") ?? number(r.o, "latitude", "lat")
            let lng = number(object(r.o, "address", "location"), "longitude", "lng", "lon") ?? number(r.o, "longitude", "lng", "lon")
            if let lat, let lng, !(lat == 0 && lng == 0) {
                let cell = zoneCell(lat: lat, lng: lng)
                if seenCells.insert(cell.0).inserted { cells.append(cell) }
            }
        }
        await withTaskGroup(of: TimeZone?.self) { group in
            var pending = cells.makeIterator()
            for _ in 0..<8 {
                if let c = pending.next() { group.addTask { await venueZone(lat: c.1.0, lng: c.1.1) } }
            }
            while await group.next() != nil {
                if let c = pending.next() { group.addTask { await venueZone(lat: c.1.0, lng: c.1.1) } }
            }
        }
        var byID: [String: LocalEvent] = [:]
        var order: [String] = []
        for r in raws {
            guard let event = await makeEvent(r.o, parent: r.parent, origin: origin) else { continue }
            if let d = event.distanceMiles, d > Double(rangeMiles) + 1 { continue }
            if event.distanceMiles == nil && r.strict { continue }
            if byID[event.id] == nil {
                byID[event.id] = event
                order.append(event.id)
            }
        }
        let cutoff = Date.now.addingTimeInterval(-12 * 3600)
        let events = order.compactMap { byID[$0] }
            .filter { ($0.start ?? .distantFuture) >= cutoff }
            .sorted {
                let a = $0.start ?? .distantFuture, b = $1.start ?? .distantFuture
                return a != b ? a < b : ($0.distanceMiles ?? .infinity) < ($1.distanceMiles ?? .infinity)
            }
        return SearchResult(events: events, batches: responses.count, candidates: raws.count)
    }

    private static func norm(_ key: String) -> String { key.lowercased().replacingOccurrences(of: "_", with: "") }

    private static func field(_ o: [String: Any], _ keys: [String]) -> Any? {
        for wanted in keys.map(norm) {
            for (k, v) in o where norm(k) == wanted && !(v is NSNull) { return v }
        }
        return nil
    }

    private static func text(_ o: [String: Any]?, _ keys: String...) -> String? {
        guard let o, let v = field(o, keys) else { return nil }
        let s: String
        if let str = v as? String { s = str } else if let n = v as? NSNumber { s = n.stringValue } else { return nil }
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty || t == "null" ? nil : t
    }

    private static func object(_ o: [String: Any], _ keys: String...) -> [String: Any]? {
        field(o, keys) as? [String: Any]
    }

    private static func number(_ o: [String: Any]?, _ keys: String...) -> Double? {
        guard let o, let v = field(o, keys) else { return nil }
        if let n = v as? NSNumber { return n.doubleValue }
        if let s = v as? String { return Double(s) }
        return nil
    }

    private static func walk(_ element: Any, parent: [String: Any]?, visit: ([String: Any], [String: Any]?) -> Void) {
        if let obj = element as? [String: Any] {
            if isEvent(obj) {
                visit(obj, parent)
                return
            }
            for child in obj.values { walk(child, parent: obj, visit: visit) }
        } else if let arr = element as? [Any] {
            for child in arr { walk(child, parent: parent, visit: visit) }
        }
    }

    private static func isEvent(_ o: [String: Any]) -> Bool {
        guard text(o, "name") != nil,
              let start = text(o, "start_datetime", "start_date", "startdate", "when") else { return false }
        return !start.hasPrefix("1900")
    }

    /** Counts event-shaped objects in a captured response body, to spot empty event lists. */
    static func countEventObjects(in body: String) -> Int {
        guard let data = body.data(using: .utf8), let root = try? JSONSerialization.jsonObject(with: data) else { return 0 }
        func count(_ element: Any) -> Int {
            if let obj = element as? [String: Any] {
                return isEvent(obj) ? 1 : obj.values.reduce(0) { $0 + count($1) }
            }
            if let arr = element as? [Any] { return arr.reduce(0) { $0 + count($1) } }
            return 0
        }
        return count(root)
    }

    private static func makeEvent(_ o: [String: Any], parent: [String: Any]?, origin: GeoPoint) async -> LocalEvent? {
        guard let name = text(o, "name"),
              let startRaw = text(o, "start_datetime", "start_date", "startdate", "when") else { return nil }
        let address = object(o, "address", "location")
        let group = object(o, "activityGroup", "activity_group", "store")
        let typeName = text(parent, "EventTypeName", "EvenTypeName", "EventBadge") ?? text(o, "EventType", "event_type")

        let lat = number(address, "latitude", "lat") ?? number(o, "latitude", "lat")
        let lng = number(address, "longitude", "lng", "lon") ?? number(o, "longitude", "lng", "lon")
        var coords: (Double, Double)?
        if let lat, let lng, !(lat == 0 && lng == 0) { coords = (lat, lng) }
        let distance = coords.map { haversineMiles(origin.latitude, origin.longitude, $0.0, $0.1) }

        let tzField = text(address, "timezone", "time_zone") ?? text(o, "timezone", "time_zone")
        let explicit = hasTrustedOffset(startRaw)
        lock.lock()
        let first = !loggedShape
        loggedShape = true
        lock.unlock()
        if first {
            let keys = o.keys.sorted().joined(separator: ",")
            print("[Events] first event: keys=[\(keys)] start=\(startRaw) tzField=\(tzField ?? "-") explicitOffset=\(explicit)")
        }
        // Venue-local clock time, in order of trust: a non-zero UTC offset in the string (a zero offset is
        // how the site stamps venue-local times, so it isn't trusted), the payload's own zone field, then
        // the venue's zone looked up from its coordinates (falls back to UTC).
        var resolved = safeZone(tzField ?? "")
        if resolved == nil, let coords {
            resolved = await venueZone(lat: coords.0, lng: coords.1)
        }
        let zone: TimeZone? = explicit ? nil : resolved
        let parsed = parseDate(startRaw, zone: zone?.identifier ?? tzField)

        let venue = text(address, "name", "venue_name") ?? text(o, "VenueName", "venue_name") ?? text(group, "display_name", "name")
        let cityLine = [text(o, "State") ?? text(address, "state"), text(o, "PostalCode") ?? text(address, "postal_code")]
            .compactMap { $0 }.joined(separator: " ")
        let composed = [text(o, "StreetAddress", "street_address") ?? text(address, "street_address", "address1"),
                        text(o, "City") ?? text(address, "city"),
                        cityLine.isEmpty ? nil : cityLine].compactMap { $0 }.joined(separator: ", ")
        let fullAddress = text(address, "full_address", "formatted_address") ?? (composed.isEmpty ? nil : composed)

        var products: [String] = []
        if let p = field(o, ["products", "product"]) { collectStrings(p, into: &products) }
        var tags: [String] = []
        if let t = field(o, ["tags"]) { collectStrings(t, into: &tags) }
        let metadata = object(o, "metadata")
        let haystack = ([typeName, text(o, "activity_type", "type"), text(metadata, "category"), name].compactMap { $0 } + tags)
            .joined(separator: " ").lowercased()

        let id = text(o, "guid", "EventGUID", "id", "local_id") ?? "\(name)|\(startRaw)|\(venue ?? "")"
        var seen = Set<String>()
        return LocalEvent(
            id: id,
            name: name,
            kind: classify(haystack),
            typeName: typeName,
            start: parsed?.0,
            hasTime: parsed?.1 ?? false,
            timeZoneID: zone?.identifier ?? tzField,
            venue: venue,
            address: fullAddress,
            latitude: coords?.0,
            longitude: coords?.1,
            distanceMiles: distance,
            products: products.filter { seen.insert($0).inserted },
            cost: text(o, "Cost", "cost") ?? text(metadata, "cost"),
            link: text(o, "RegistrationLink", "pokemon_url", "event_website") ?? text(metadata, "event_website")
        )
    }

    private static func collectStrings(_ e: Any, into out: inout [String]) {
        if let s = e as? String {
            if !s.trimmingCharacters(in: .whitespaces).isEmpty { out.append(s) }
        } else if let arr = e as? [Any] {
            for x in arr { collectStrings(x, into: &out) }
        } else if let obj = e as? [String: Any] {
            for x in obj.values { collectStrings(x, into: &out) }
        }
    }

    private static func classify(_ t: String) -> EventKind {
        func has(_ words: String...) -> Bool { words.contains { t.contains($0) } }
        if has("prerelease", "pre-release") { return .prerelease }
        if has("league cup", "league_cup", "leaguecup") { return .leagueCup }
        if has("league challenge", "league_challenge", "leaguechallenge") { return .leagueChallenge }
        if has("regional", "international", "world", "special event", "special_event", "championship") { return .championship }
        if has("tournament", "premier") { return .tournament }
        if has("league", "play session", "play_session", "casual") { return .league }
        return .other
    }

    /// True when the datetime string carries its own UTC offset (or a trailing Z).
    static func hasOffset(_ raw: String) -> Bool {
        let s = raw.trimmingCharacters(in: .whitespaces)
        if s.hasSuffix("Z") || s.hasSuffix("z") { return true }
        return s.range(of: #"[+-]\d{2}:?\d{2}$"#, options: .regularExpression) != nil
    }

    /// True when the string carries a non-zero UTC offset — the only kind trusted outright. OutSystems
    /// stamps venue-local wall times with a zero offset (a trailing Z or +00:00), so those are treated
    /// as unmarked and interpreted in the venue's zone.
    static func hasTrustedOffset(_ raw: String) -> Bool {
        let s = raw.trimmingCharacters(in: .whitespaces)
        if s.hasSuffix("Z") || s.hasSuffix("z") { return false }
        guard let m = s.range(of: #"[+-]\d{2}:?\d{2}$"#, options: .regularExpression) else { return false }
        let digits = s[m.lowerBound...].filter(\.isNumber)
        return Int(digits) != 0
    }

    /// Returns the date and whether the source included a time of day.
    static func parseDate(_ raw: String, zone: String?) -> (Date, Bool)? {
        var s = raw.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: " ", with: "T")
        s = s.replacingOccurrences(of: #"\.\d+"#, with: "", options: .regularExpression)
        if s.hasSuffix("Z") || s.hasSuffix("z") { s = String(s.dropLast()) + "+00:00" }
        if s.contains("T") {
            s = s.replacingOccurrences(of: #"([+-]\d{2})(\d{2})$"#, with: "$1:$2", options: .regularExpression)
        }
        // A resolved venue zone outranks a zero offset (see hasTrustedOffset): read the wall clock in it.
        if zone != nil { s = s.replacingOccurrences(of: #"[+-]00:00$"#, with: "", options: .regularExpression) }
        let tz = zone.flatMap(TimeZone.init(identifier:))
        func attempt(_ pattern: String, _ zone: TimeZone?, _ input: String) -> Date? {
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.timeZone = zone ?? TimeZone(identifier: "UTC")
            f.dateFormat = pattern
            return f.date(from: input)
        }
        if let d = attempt("yyyy-MM-dd'T'HH:mm:ssXXXXX", nil, s) { return (d, true) }
        if let d = attempt("yyyy-MM-dd'T'HH:mm:ss", tz, s) { return (d, true) }
        if let d = attempt("yyyy-MM-dd'T'HH:mm", tz, s) { return (d, true) }
        if let d = attempt("yyyy-MM-dd", tz ?? .current, String(s.prefix(10))) { return (d, false) }
        return nil
    }

    static func haversineMiles(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let r = 3958.8
        let dLat = (lat2 - lat1) * .pi / 180, dLon = (lon2 - lon1) * .pi / 180
        let a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * .pi / 180) * cos(lat2 * .pi / 180) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    // MARK: Venue time zones

    private static let zonePref = "events_tz_"
    private static let lock = NSLock()
    nonisolated(unsafe) private static var zoneCache: [String: TimeZone] = [:]
    nonisolated(unsafe) private static var zoneMisses: Set<String> = []
    nonisolated(unsafe) private static var loggedShape = false

    /// Map cell key (~0.25°) and the coordinates of its center, so nearby venues share one lookup.
    private static func zoneCell(lat: Double, lng: Double) -> (String, (Double, Double)) {
        let gLat = Int((lat * 4).rounded())
        let gLng = Int((lng * 4).rounded())
        return ("\(gLat):\(gLng)", (Double(gLat) / 4.0, Double(gLng) / 4.0))
    }

    private static func safeZone(_ id: String) -> TimeZone? {
        guard let tz = TimeZone(identifier: id), tz.identifier == id else { return nil }
        return tz
    }

    /// The venue's zone for coordinates, from memory, disk, or Open-Meteo's timezone lookup.
    static func venueZone(lat: Double, lng: Double) async -> TimeZone? {
        let (key, center) = zoneCell(lat: lat, lng: lng)
        lock.lock()
        if let tz = zoneCache[key] { lock.unlock(); return tz }
        if zoneMisses.contains(key) { lock.unlock(); return nil }
        lock.unlock()
        if let stored = UserDefaults.standard.string(forKey: zonePref + key), stored != "-" {
            guard let tz = safeZone(stored) else {
                lock.lock(); zoneMisses.insert(key); lock.unlock()
                return nil
            }
            lock.lock(); zoneCache[key] = tz; lock.unlock()
            return tz
        }
        var comps = URLComponents(string: "https://api.open-meteo.com/v1/forecast")!
        comps.queryItems = [
            .init(name: "latitude", value: String(center.0)),
            .init(name: "longitude", value: String(center.1)),
            .init(name: "current", value: "temperature_2m"),
            .init(name: "timezone", value: "auto"),
        ]
        let id = (await getJSON(comps.url!) as? [String: Any])?["timezone"] as? String
        guard let id, let tz = safeZone(id) else {
            // Only remember the miss in memory: a transient failure must not poison the zone on disk.
            lock.lock(); zoneMisses.insert(key); lock.unlock()
            return nil
        }
        lock.lock(); zoneCache[key] = tz; lock.unlock()
        UserDefaults.standard.set(tz.identifier, forKey: zonePref + key)
        return tz
    }

    private static let usStates: [String: String] = [
        "AL": "Alabama", "AK": "Alaska", "AZ": "Arizona", "AR": "Arkansas", "CA": "California", "CO": "Colorado",
        "CT": "Connecticut", "DE": "Delaware", "FL": "Florida", "GA": "Georgia", "HI": "Hawaii", "ID": "Idaho",
        "IL": "Illinois", "IN": "Indiana", "IA": "Iowa", "KS": "Kansas", "KY": "Kentucky", "LA": "Louisiana",
        "ME": "Maine", "MD": "Maryland", "MA": "Massachusetts", "MI": "Michigan", "MN": "Minnesota",
        "MS": "Mississippi", "MO": "Missouri", "MT": "Montana", "NE": "Nebraska", "NV": "Nevada",
        "NH": "New Hampshire", "NJ": "New Jersey", "NM": "New Mexico", "NY": "New York", "NC": "North Carolina",
        "ND": "North Dakota", "OH": "Ohio", "OK": "Oklahoma", "OR": "Oregon", "PA": "Pennsylvania",
        "RI": "Rhode Island", "SC": "South Carolina", "SD": "South Dakota", "TN": "Tennessee", "TX": "Texas",
        "UT": "Utah", "VT": "Vermont", "VA": "Virginia", "WA": "Washington", "WV": "West Virginia",
        "WI": "Wisconsin", "WY": "Wyoming", "DC": "District of Columbia",
    ]
}

/// Loads the official Event Locator page in an invisible web view and captures its data responses.
@MainActor
final class EventPageLoader: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
    nonisolated struct Captured: Sendable {
        let url: String
        let body: String
    }

    /// The Event Locator only runs its event search on load when its stored client variables say a search
    /// is in progress (`AreEventsBeingSearched`) in events mode (`IsShowEvents`). A fresh web view has neither,
    /// so the page would just show its landing state. This hook forces both on and captures the data responses.
    /// `__tcgPrime` writes the variables to storage so a reload starts in search mode.
    private static let hookJS = """
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
      function send(u, b){ try { if (u && String(u).indexOf('screenservices') >= 0) { window.webkit.messageHandlers.tcgEvents.postMessage({url:String(u), body:String(b)}); } } catch(e){} }
      var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
      XMLHttpRequest.prototype.open = function(m, u){ this.__tcgUrl = u; return oo.apply(this, arguments); };
      XMLHttpRequest.prototype.send = function(){ var x = this; x.addEventListener('load', function(){ try { send(x.responseURL || x.__tcgUrl, x.responseText); } catch(e){} }); return os.apply(this, arguments); };
      if (window.fetch) { var of = window.fetch; window.fetch = function(){ var a = arguments; return of.apply(this, a).then(function(r){ try { var u = r.url || (a[0] && a[0].url) || a[0]; r.clone().text().then(function(t){ send(u, t); }); } catch(e){} return r; }); }; }
    })();
    """

    /// On a first visit the site's bot check sets its cookies while the page loads, so the page's own data
    /// requests can come back empty. If no event list arrives, the page is reloaded (cookies and search state
    /// are in place by then), which is what a second manual search used to do.
    private static let maxReloads = 3
    private static let reloadAfter: Duration = .seconds(9)

    private var webView: WKWebView?
    private var url: URL?
    private var captured: [Captured] = []
    private var continuation: CheckedContinuation<[Captured], Never>?
    private var quietTask: Task<Void, Never>?
    private var timeoutTask: Task<Void, Never>?
    private var watchdogTask: Task<Void, Never>?
    private var sawEventList = false
    private var sawLocations = false
    private var reloads = 0

    func load(_ url: URL, timeout: Duration = .seconds(50)) async -> [Captured] {
        await withTaskCancellationHandler {
            await withCheckedContinuation { cont in
                continuation = cont
                start(url, timeout: timeout)
            }
        } onCancel: {
            Task { @MainActor in self.finish() }
        }
    }

    private func start(_ url: URL, timeout: Duration) {
        self.url = url
        let config = WKWebViewConfiguration()
        let controller = WKUserContentController()
        controller.addUserScript(WKUserScript(source: Self.hookJS, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        controller.add(self, name: "tcgEvents")
        config.userContentController = controller
        config.websiteDataStore = .default()

        let web = WKWebView(frame: CGRect(x: 0, y: 0, width: 1, height: 1), configuration: config)
        web.alpha = 0.01
        web.isUserInteractionEnabled = false
        web.navigationDelegate = self
        web.customUserAgent = Leaderboard.userAgent
        webView = web
        let window = UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow }
            .first
        window?.addSubview(web)
        web.load(URLRequest(url: url))
        armWatchdog()

        timeoutTask = Task { [weak self] in
            try? await Task.sleep(for: timeout)
            self?.finish()
        }
    }

    private func armWatchdog() {
        watchdogTask?.cancel()
        watchdogTask = Task { [weak self] in
            try? await Task.sleep(for: Self.reloadAfter)
            guard !Task.isCancelled else { return }
            await self?.reloadIfNeeded()
        }
    }

    private func reloadIfNeeded() async {
        guard continuation != nil, !sawEventList, let web = webView, let url else { return }
        guard reloads < Self.maxReloads else {
            // Out of retries: settle for whatever arrived (e.g. stores only).
            if sawLocations { scheduleFinish(after: .seconds(3)) }
            return
        }
        reloads += 1
        print("[Events] no event list yet, reloading (\(reloads)/\(Self.maxReloads))")
        _ = try? await web.evaluateJavaScript("window.__tcgPrime ? window.__tcgPrime() : false")
        quietTask?.cancel()
        sawLocations = false
        web.load(URLRequest(url: url))
        armWatchdog()
    }

    private func scheduleFinish(after wait: Duration) {
        quietTask?.cancel()
        quietTask = Task { [weak self] in
            try? await Task.sleep(for: wait)
            guard !Task.isCancelled else { return }
            self?.finish()
        }
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let dict = message.body as? [String: Any],
              let url = dict["url"] as? String,
              let body = dict["body"] as? String else { return }
        captured.append(Captured(url: url, body: body))
        print("[Events] captured \(url.split(separator: "/").last ?? "") (\(body.count) bytes)")
        if url.localizedCaseInsensitiveContains("GetEventList") {
            // A cold session's first event list can come back empty while the site's bot check is still
            // settling. Only accept it once it holds events, or after a reload — otherwise the reload
            // below never happens and the search shows no results.
            let events = EventLocator.countEventObjects(in: body)
            if events > 0 || reloads >= 1 {
                sawEventList = true
                watchdogTask?.cancel()
                // Event lists arrive in batches; finish once they go quiet.
                scheduleFinish(after: .seconds(3))
            } else {
                print("[Events] event list came back empty; waiting for reload")
                if reloads >= Self.maxReloads { scheduleFinish(after: .seconds(3)) }
            }
        } else if url.localizedCaseInsensitiveContains("GetLocations") {
            sawLocations = true
            if reloads >= Self.maxReloads && !sawEventList { scheduleFinish(after: .seconds(10)) }
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        // Fallback in case the document-start script didn't run (e.g. after a bot-check redirect).
        webView.evaluateJavaScript(Self.hookJS, completionHandler: nil)
    }

    private func finish() {
        guard let cont = continuation else { return }
        continuation = nil
        quietTask?.cancel()
        timeoutTask?.cancel()
        watchdogTask?.cancel()
        if let web = webView {
            web.stopLoading()
            web.configuration.userContentController.removeScriptMessageHandler(forName: "tcgEvents")
            web.removeFromSuperview()
        }
        webView = nil
        cont.resume(returning: captured)
    }
}
