import MapKit
import SwiftUI

@Observable
final class EventsModel {
    enum Phase {
        case idle
        case loading(String?)
        case loaded(GeoPoint, [LocalEvent])
        case failed(String, GeoPoint?)
    }

    var phase: Phase = .idle
    private var task: Task<Void, Never>?

    var isLoading: Bool { if case .loading = phase { true } else { false } }

    func search(_ query: String, range: Int) {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        task?.cancel()
        phase = .loading(nil)
        task = Task {
            var point: GeoPoint?
            do {
                let p = try await EventLocator.geocode(q)
                point = p
                guard !Task.isCancelled else { return }
                phase = .loading(p.label)
                let events = try await EventLocator.search(near: p, rangeMiles: range)
                guard !Task.isCancelled else { return }
                withAnimation(.snappy) { phase = .loaded(p, events) }
            } catch is CancellationError {
                return
            } catch let error as EventLocatorError {
                guard !Task.isCancelled else { return }
                phase = .failed(error.message, point)
            } catch {
                guard !Task.isCancelled else { return }
                phase = .failed("Couldn't load events right now. Check your connection and try again.", point)
            }
        }
    }
}

struct EventsView: View {
    @AppStorage("events_query") private var query: String = ""
    @AppStorage("events_range") private var range: Int = 25
    @AppStorage("events_game") private var gameFilter: EventGameFilter = .all
    @AppStorage("events_type") private var typeFilter: EventTypeFilter = .all
    @State private var model = EventsModel()
    @State private var selected: LocalEvent?
    @FocusState private var fieldFocused: Bool
    @Environment(\.openURL) private var openURL

    private var visible: [LocalEvent] {
        guard case .loaded(_, let events) = model.phase else { return [] }
        return events.filter { $0.matches(game: gameFilter, type: typeFilter) }
    }

    private var groups: [(key: String, events: [LocalEvent])] {
        var order: [String] = []
        var map: [String: [LocalEvent]] = [:]
        for e in visible {
            if map[e.dayKey] == nil { order.append(e.dayKey) }
            map[e.dayKey, default: []].append(e)
        }
        return order.map { ($0, map[$0] ?? []) }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    Text("Pokémon events near you")
                        .font(.subheadline)
                        .foregroundStyle(Theme.muted)
                        .padding(.horizontal, 4)
                        .padding(.top, -6)
                    searchCard
                    content
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 28)
            }
            .scrollDismissesKeyboard(.interactively)
            .background { CanvasBackground() }
            .navigationTitle("Events")
            .sheet(item: $selected) { event in
                EventDetailSheet(event: event, pokemonURL: detailURL(for: event))
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
                    .presentationBackground(Theme.canvas)
            }
        }
    }

    private func pokemonURL(_ point: GeoPoint) -> URL {
        EventLocator.pageURL(latitude: point.latitude, longitude: point.longitude, rangeMiles: range)
    }

    private func detailURL(for event: LocalEvent) -> URL {
        if let link = event.link, let url = URL(string: link) { return url }
        if case .loaded(let p, _) = model.phase { return pokemonURL(p) }
        return URL(string: "https://events.pokemon.com/EventLocator/")!
    }

    private func submit() {
        fieldFocused = false
        model.search(query, range: range)
    }

    // MARK: Search card

    private var searchCard: some View {
        VStack(spacing: 12) {
            HStack(spacing: 10) {
                HStack(spacing: 8) {
                    Image(systemName: "mappin.and.ellipse")
                        .foregroundStyle(Theme.red)
                    TextField("Zip code or city", text: $query)
                        .focused($fieldFocused)
                        .submitLabel(.search)
                        .textInputAutocapitalization(.words)
                        .autocorrectionDisabled()
                        .onSubmit(submit)
                    if !query.isEmpty {
                        Button { query = "" } label: {
                            Image(systemName: "xmark.circle.fill").foregroundStyle(Theme.muted)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Clear")
                    }
                }
                .padding(.horizontal, 12)
                .frame(height: 46)
                .background(Color(.tertiarySystemFill), in: .rect(cornerRadius: 12))

                Button(action: submit) {
                    Text("Search")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 16)
                        .frame(height: 46)
                        .background(Theme.red.opacity(query.trimmingCharacters(in: .whitespaces).isEmpty || model.isLoading ? 0.4 : 1),
                                    in: .rect(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .disabled(query.trimmingCharacters(in: .whitespaces).isEmpty || model.isLoading)
                .sensoryFeedback(.impact(weight: .light), trigger: model.isLoading) { _, new in new }
            }

            Picker("Distance", selection: $range) {
                ForEach(EventLocator.distanceOptions, id: \.self) { Text("\($0) mi").tag($0) }
            }
            .pickerStyle(.segmented)
            .onChange(of: range) { _, _ in
                if case .idle = model.phase { return }
                model.search(query, range: range)
            }

            filterRow("Game") {
                Picker("Game", selection: $gameFilter.animation(.snappy)) {
                    ForEach(EventGameFilter.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
            }

            filterRow("Type") {
                Picker("Type", selection: $typeFilter.animation(.snappy)) {
                    ForEach(EventTypeFilter.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
            }
            .sensoryFeedback(.selection, trigger: typeFilter)
            .sensoryFeedback(.selection, trigger: gameFilter)
        }
        .padding(14)
        .cardSurface()
    }

    private func filterRow<C: View>(_ title: String, @ViewBuilder control: () -> C) -> some View {
        HStack(spacing: 10) {
            Text(title)
                .font(.caption.weight(.bold))
                .tracking(0.6)
                .textCase(.uppercase)
                .foregroundStyle(Theme.muted)
                .frame(width: 42, alignment: .leading)
            control()
        }
    }

    private var filterDescription: String {
        var parts: [String] = []
        if let g = gameFilter.game { parts.append(g.rawValue) }
        switch typeFilter {
        case .all: break
        case .cp: parts.append("Championship Points (Challenges & Cups)")
        case .other: parts.append("non-CP")
        }
        return parts.joined(separator: " · ")
    }

    // MARK: Content

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .idle:
            idleState
        case .loading(let place):
            HStack(spacing: 14) {
                ProgressView().tint(Theme.red).controlSize(.regular)
                VStack(alignment: .leading, spacing: 2) {
                    Text(place.map { "Checking events near \($0)…" } ?? "Finding location…")
                        .font(.headline).foregroundStyle(Theme.ink)
                        .contentTransition(.opacity)
                    Text("This can take a few seconds").font(.footnote).foregroundStyle(Theme.muted)
                }
                Spacer(minLength: 0)
            }
            .padding(18)
            .cardSurface()
            .animation(.default, value: place)
        case .failed(let message, let point):
            messageState(icon: "wifi.slash", title: "Couldn't load events", message: message,
                         retry: { model.search(query, range: range) }, url: point.map(pokemonURL))
        case .loaded(let point, let events):
            if visible.isEmpty {
                let filtered = gameFilter != .all || typeFilter != .all
                let extra = filtered && !events.isEmpty
                    ? " matching \(filterDescription). \(events.count) other event\(events.count == 1 ? "" : "s") hidden by filters."
                    : ". Try a wider distance."
                messageState(icon: "calendar.badge.exclamationmark", title: "No events found",
                             message: "Nothing within \(range) mi of \(point.label)\(extra)",
                             retry: nil, url: pokemonURL(point))
            } else {
                Text("\(visible.count) event\(visible.count == 1 ? "" : "s") within \(range) mi of \(point.label)")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Theme.muted)
                    .padding(.top, 6)
                    .padding(.horizontal, 4)
                ForEach(groups, id: \.key) { group in
                    Text(group.events.first?.dayLabel ?? "")
                        .font(.title3.bold())
                        .foregroundStyle(Theme.ink)
                        .padding(.top, 10)
                        .padding(.horizontal, 4)
                    ForEach(group.events) { event in
                        Button { selected = event } label: { EventRow(event: event) }
                            .buttonStyle(PressableCardStyle())
                    }
                }
                Button { openURL(pokemonURL(point)) } label: {
                    Label("See all on Pokémon.com", systemImage: "arrow.up.right.square")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Theme.blue)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var idleState: some View {
        VStack(spacing: 10) {
            ZStack {
                Circle().fill(Theme.gold.opacity(0.18))
                Circle().strokeBorder(Theme.gold, lineWidth: 2)
                Image(systemName: "mappin.and.ellipse")
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundStyle(Theme.red)
            }
            .frame(width: 72, height: 72)
            Text("Find your next tournament")
                .font(.title2.bold()).foregroundStyle(Theme.ink)
            Text("Prereleases, League Challenges, League Cups and more, straight from Pokémon's official event locator.")
                .font(.subheadline).foregroundStyle(Theme.muted)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 40)
        .padding(.horizontal, 24)
    }

    private func messageState(icon: String, title: String, message: String, retry: (() -> Void)?, url: URL?) -> some View {
        VStack(spacing: 8) {
            Image(systemName: icon).font(.system(size: 34)).foregroundStyle(Theme.muted)
            Text(title).font(.title3.bold()).foregroundStyle(Theme.ink)
            Text(message).font(.subheadline).foregroundStyle(Theme.muted).multilineTextAlignment(.center)
            HStack(spacing: 10) {
                if let retry {
                    Button("Try Again", action: retry)
                        .buttonStyle(.borderedProminent)
                        .tint(Theme.blue)
                }
                if let url {
                    Button("Open on Pokémon.com") { openURL(url) }
                        .buttonStyle(.bordered)
                        .tint(Theme.blue)
                }
            }
            .padding(.top, 6)
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        .cardSurface()
    }
}

private struct PressableCardStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.snappy(duration: 0.18), value: configuration.isPressed)
    }
}

private struct DateBadge: View {
    let event: LocalEvent

    var body: some View {
        VStack(spacing: 0) {
            Text(event.monthAbbrev)
                .font(.caption2.weight(.bold))
                .tracking(0.8)
                .foregroundStyle(event.kind.textColor)
            Text(event.dayNumber)
                .font(.title2.bold())
                .foregroundStyle(Theme.ink)
        }
        .frame(width: 52)
        .padding(.vertical, 6)
        .background(event.kind.color.opacity(0.12), in: .rect(cornerRadius: 12))
        .overlay { RoundedRectangle(cornerRadius: 12).strokeBorder(event.kind.color.opacity(0.35)) }
    }
}

private struct KindBadge: View {
    let event: LocalEvent

    var body: some View {
        Text(event.badgeLabel.uppercased())
            .font(.system(size: 10, weight: .bold))
            .tracking(0.6)
            .lineLimit(1)
            .foregroundStyle(event.kind.textColor)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(event.kind.color.opacity(0.14), in: .rect(cornerRadius: 6))
    }
}

private struct EventRow: View {
    let event: LocalEvent

    var body: some View {
        HStack(spacing: 12) {
            DateBadge(event: event)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    KindBadge(event: event)
                    let meta = [event.gameLabel, event.distanceMiles.map(LocalEvent.milesLabel)].compactMap { $0 }
                    if !meta.isEmpty {
                        Text(meta.joined(separator: " · ")).font(.caption.weight(.semibold)).foregroundStyle(Theme.muted)
                    }
                }
                Text(event.name)
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(Theme.ink)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                let sub = [event.timeLabel, event.venue].compactMap { $0 }.joined(separator: " · ")
                if !sub.isEmpty {
                    Text(sub).font(.footnote).foregroundStyle(Theme.muted).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Theme.muted.opacity(0.6))
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface()
        .contentShape(.rect)
    }
}

private struct EventDetailSheet: View {
    let event: LocalEvent
    let pokemonURL: URL
    @Environment(\.openURL) private var openURL

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 14) {
                    DateBadge(event: event)
                    VStack(alignment: .leading, spacing: 4) {
                        KindBadge(event: event)
                        Text(event.name).font(.title2.bold()).foregroundStyle(Theme.ink)
                    }
                }
                VStack(alignment: .leading, spacing: 14) {
                    line("clock", event.fullDateLabel, event.zoneLabel)
                    if let venue = event.venue {
                        line("storefront", venue, event.distanceMiles.map { "\(LocalEvent.milesLabel($0)) away" })
                    }
                    if let address = event.address { line("mappin", address, nil) }
                    if let cost = event.cost { line("dollarsign.circle", cost, nil) }
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .cardSurface()

                Button(action: openDirections) {
                    Label("Directions", systemImage: "arrow.triangle.turn.up.right.diamond.fill")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .frame(height: 50)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.roundedRectangle(radius: 14))
                .tint(Theme.red)
                .disabled(event.address == nil && event.latitude == nil)

                Button { openURL(pokemonURL) } label: {
                    Label("View on Pokémon.com", systemImage: "arrow.up.right.square")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .frame(height: 50)
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.roundedRectangle(radius: 14))
                .tint(Theme.blue)
            }
            .padding(20)
        }
    }

    private func line(_ icon: String, _ text: String, _ secondary: String?) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon).foregroundStyle(Theme.blue).frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(text).font(.subheadline).foregroundStyle(Theme.ink)
                if let secondary { Text(secondary).font(.footnote).foregroundStyle(Theme.muted) }
            }
        }
    }

    private func openDirections() {
        let name = event.venue ?? event.name
        if let lat = event.latitude, let lng = event.longitude {
            let item = MKMapItem(placemark: MKPlacemark(coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lng)))
            item.name = name
            item.openInMaps(launchOptions: [MKLaunchOptionsDirectionsModeKey: MKLaunchOptionsDirectionsModeDriving])
        } else {
            let q = [event.venue, event.address].compactMap { $0 }.joined(separator: ", ")
            var comps = URLComponents(string: "https://maps.apple.com/")!
            comps.queryItems = [URLQueryItem(name: "daddr", value: q)]
            if let url = comps.url { openURL(url) }
        }
    }
}
