import SwiftUI

@Observable
final class PlayersModel {
    enum Sync: Equatable {
        case idle
        case loading(loaded: Int, total: Int)
        case ready(Date, complete: Bool)
        case failed(String)
    }

    var query: String = UserDefaults.standard.string(forKey: Keys.query) ?? "" {
        didSet {
            UserDefaults.standard.set(query, forKey: Keys.query)
            recompute(debounce: true)
        }
    }
    private(set) var division: Division = Division(rawValue: UserDefaults.standard.string(forKey: Keys.division) ?? "") ?? .masters
    private(set) var region: LeaderboardRegion = LeaderboardRegion(rawValue: UserDefaults.standard.string(forKey: Keys.region) ?? "") ?? .global
    private(set) var sync: Sync = .idle
    private(set) var players: [LeaderboardPlayer] = []
    private(set) var total = 0
    private(set) var results: [LeaderboardPlayer] = []
    private(set) var me: LeaderboardPlayer?
    private(set) var season: String?

    @ObservationIgnored private var normalized: [String] = []
    @ObservationIgnored private var cacheIndex: LeaderboardIndex?
    @ObservationIgnored private var loadTask: Task<Void, Never>?
    @ObservationIgnored private var searchTask: Task<Void, Never>?
    @ObservationIgnored private var restored = false

    private enum Keys {
        static let query = "players_query"
        static let division = "players_division"
        static let region = "players_region"
        static let meName = "players_me_name"
        static let meDivision = "players_me_division"
        static let meRank = "players_me_rank"
    }

    var isLoading: Bool { if case .loading = sync { true } else { false } }

    /// Identifies the board on screen; changes whenever division or region does.
    var boardID: String { "\(division.rawValue)|\(region.rawValue)" }

    private func apply(_ list: [LeaderboardPlayer], total: Int) async {
        let norms = await Task.detached(priority: .userInitiated) { list.map { Leaderboard.normalize($0.name) } }.value
        normalized = norms
        players = list
        self.total = total
        resolveMe()
        recompute(debounce: false)
    }

    func setDivision(_ d: Division) {
        guard d != division else { return }
        division = d
        UserDefaults.standard.set(d.rawValue, forKey: Keys.division)
        resetBoard()
    }

    func setRegion(_ r: LeaderboardRegion) {
        guard r != region else { return }
        region = r
        UserDefaults.standard.set(r.rawValue, forKey: Keys.region)
        resetBoard()
    }

    private func resetBoard() {
        loadTask?.cancel()
        cacheIndex = nil
        normalized = []
        players = []
        results = []
        total = 0
        me = nil
        season = nil
        sync = .idle
    }

    /// Shows the cached index right away and refreshes it if it's stale.
    func ensureLoaded() async {
        let d = division
        let r = region
        if cacheIndex == nil, let cached = await Leaderboard.cached(d, r), d == division, r == region {
            cacheIndex = cached
            season = cached.season
            await apply(cached.players, total: cached.total)
            sync = .ready(cached.fetchedAt, complete: cached.complete)
        }
        if let cached = cacheIndex, Leaderboard.isFresh(cached) { return }
        if !isLoading { refresh() }
    }

    func refresh() {
        loadTask?.cancel()
        let d = division
        let r = region
        let board = boardID
        let hadPlayers = !players.isEmpty
        sync = .loading(loaded: 0, total: total)
        loadTask = Task { [weak self] in
            let throttle = Throttle()
            do {
                let index = try await Leaderboard.load(d, region: r) { collected, total in
                    let emit = await throttle.shouldEmit(done: collected.count >= total)
                    let sorted = (!hadPlayers && emit) ? collected.sorted { $0.rank < $1.rank } : nil
                    await MainActor.run {
                        guard let self, self.boardID == board, !Task.isCancelled else { return }
                        self.sync = .loading(loaded: collected.count, total: total)
                        if let sorted { Task { await self.apply(sorted, total: total) } }
                    }
                }
                guard let self, !Task.isCancelled, self.boardID == board else { return }
                self.cacheIndex = index
                self.season = index.season
                await self.apply(index.players, total: index.total)
                self.sync = .ready(index.fetchedAt, complete: index.complete)
            } catch is CancellationError {
                return
            } catch {
                guard let self, !Task.isCancelled, self.boardID == board else { return }
                let message = (error as? LeaderboardError)?.message ?? "Couldn't load the leaderboard. Check your connection and try again."
                if !self.players.isEmpty, let cached = self.cacheIndex {
                    self.sync = .ready(cached.fetchedAt, complete: cached.complete)
                } else {
                    self.sync = .failed(message)
                }
            }
        }
    }

    private func recompute(debounce: Bool) {
        searchTask?.cancel()
        let q = query
        let list = players
        let norms = normalized
        searchTask = Task { [weak self] in
            if debounce { try? await Task.sleep(for: .milliseconds(160)) }
            guard !Task.isCancelled else { return }
            let found = await Task.detached(priority: .userInitiated) {
                q.trimmingCharacters(in: .whitespaces).isEmpty ? Array(list.prefix(10)) : Leaderboard.search(list, normalized: norms, query: q)
            }.value
            guard !Task.isCancelled, let self else { return }
            withAnimation(.snappy) { self.results = found }
        }
    }

    // MARK: Me

    func isMe(_ p: LeaderboardPlayer) -> Bool { me?.id == p.id }

    func toggleMe(_ p: LeaderboardPlayer) {
        let defaults = UserDefaults.standard
        if isMe(p) {
            [Keys.meName, Keys.meDivision, Keys.meRank].forEach(defaults.removeObject(forKey:))
            withAnimation(.snappy) { me = nil }
        } else {
            defaults.set(p.name, forKey: Keys.meName)
            defaults.set(division.rawValue, forKey: Keys.meDivision)
            defaults.set(p.rank, forKey: Keys.meRank)
            withAnimation(.snappy) { me = p }
        }
    }

    private func resolveMe() {
        let defaults = UserDefaults.standard
        guard let name = defaults.string(forKey: Keys.meName), defaults.string(forKey: Keys.meDivision) == division.rawValue else {
            me = nil
            return
        }
        let hint = defaults.integer(forKey: Keys.meRank)
        let match = players.filter { $0.name == name }.min { abs($0.rank - hint) < abs($1.rank - hint) }
        if let match { defaults.set(match.rank, forKey: Keys.meRank) }
        me = match
    }
}

/// Limits how often partial results are re-sorted while indexing.
private actor Throttle {
    private var last = Date.distantPast
    func shouldEmit(done: Bool) -> Bool {
        let now = Date()
        guard done || now.timeIntervalSince(last) > 0.7 else { return false }
        last = now
        return true
    }
}

// MARK: - Formatting

private extension Int {
    var grouped: String { formatted(.number.grouping(.automatic)) }
}

private func topPercent(rank: Int, total: Int) -> String {
    guard total > 0 else { return "—" }
    let p = Double(rank) * 100 / Double(total)
    if p < 1 { return String(format: "Top %.2f%%", p) }
    if p < 10 { return String(format: "Top %.1f%%", p) }
    return "Top \(max(1, Int(p)))%"
}

private func rankTint(_ rank: Int) -> Color? {
    switch rank {
    case 1: Theme.gold
    case 2: Theme.silver
    case 3: Theme.bronze
    default: nil
    }
}

// MARK: - View

struct PlayersView: View {
    @State private var model = PlayersModel()
    @State private var selected: LeaderboardPlayer?
    @FocusState private var fieldFocused: Bool
    @Environment(\.openURL) private var openURL

    private var searching: Bool { !model.query.trimmingCharacters(in: .whitespaces).isEmpty }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    Text("Championship Points leaderboard")
                        .font(.subheadline)
                        .foregroundStyle(Theme.muted)
                        .padding(.horizontal, 4)
                        .padding(.top, -6)
                    searchCard
                    if let me = model.me, !searching {
                        Button { open(me) } label: { MeTile(player: me, total: model.total) }
                            .buttonStyle(PlayersPressStyle())
                            .transition(.scale(scale: 0.96).combined(with: .opacity))
                    }
                    content
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 28)
            }
            .scrollDismissesKeyboard(.interactively)
            .background { CanvasBackground() }
            .navigationTitle("Players")
            .task(id: model.boardID) { await model.ensureLoaded() }
            .sensoryFeedback(.selection, trigger: selected)
            .sheet(item: $selected) { player in
                PlayerDetailSheet(
                    player: player,
                    players: model.players,
                    total: model.total,
                    division: model.division,
                    region: model.region,
                    isMe: model.isMe(player),
                    onToggleMe: { model.toggleMe(player) }
                )
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .presentationBackground(Theme.canvas)
            }
        }
    }

    private func open(_ p: LeaderboardPlayer) {
        fieldFocused = false
        selected = p
    }

    // MARK: Search card

    private var searchCard: some View {
        VStack(spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(Theme.red)
                TextField("Search player name", text: $model.query)
                    .focused($fieldFocused)
                    .submitLabel(.search)
                    .textInputAutocapitalization(.words)
                    .autocorrectionDisabled()
                    .onSubmit { fieldFocused = false }
                if !model.query.isEmpty {
                    Button { model.query = "" } label: {
                        Image(systemName: "xmark.circle.fill").foregroundStyle(Theme.muted)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Clear")
                }
            }
            .padding(.horizontal, 12)
            .frame(height: 46)
            .background(Color(.tertiarySystemFill), in: .rect(cornerRadius: 12))

            Picker("Division", selection: Binding(get: { model.division }, set: { model.setDivision($0) })) {
                ForEach(Division.allCases) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
            .sensoryFeedback(.selection, trigger: model.division)

            regionChips

            syncLine
        }
        .padding(14)
        .cardSurface()
        .animation(.snappy, value: model.sync)
    }

    /// Horizontally scrolling region pills; the selected one is filled ink.
    private var regionChips: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                Image(systemName: "globe")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.blue)
                    .accessibilityLabel("Region")
                ForEach(LeaderboardRegion.allCases) { region in
                    let on = region == model.region
                    Button { model.setRegion(region) } label: {
                        Text(region.label)
                            .font(.footnote.weight(.semibold))
                            .lineLimit(1)
                            .foregroundStyle(on ? .white : Theme.ink)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 7)
                            .background(on ? Theme.ink : .clear, in: .capsule)
                            .overlay { Capsule().strokeBorder(on ? Theme.ink : Theme.hairline) }
                    }
                    .buttonStyle(PlayersPressStyle())
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
        }
        .scrollIndicators(.hidden)
        .animation(.snappy, value: model.region)
        .sensoryFeedback(.selection, trigger: model.region)
    }

    @ViewBuilder
    private var syncLine: some View {
        switch model.sync {
        case .loading(let loaded, let total):
            VStack(alignment: .leading, spacing: 6) {
                Text(total > 0 ? "Loading leaderboard \(loaded.grouped) / \(total.grouped)" : "Connecting to Pokémon.com…")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Theme.muted)
                    .contentTransition(.numericText())
                if total > 0 {
                    ProgressView(value: min(1, Double(loaded) / Double(total)))
                        .tint(Theme.red)
                } else {
                    ProgressView(value: 0.08).tint(Theme.red)
                }
            }
        case .ready(let date, let complete):
            HStack {
                Text("\(model.players.count.grouped) players · \(model.season.map { "\($0) season · " } ?? "")updated \(date.formatted(.relative(presentation: .named)))\(complete ? "" : " · partial")")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Theme.muted)
                Spacer(minLength: 8)
                Button { model.refresh() } label: {
                    Label("Refresh", systemImage: "arrow.clockwise")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Theme.blue)
                }
                .buttonStyle(.plain)
            }
        case .failed(let message):
            if !model.players.isEmpty {
                Text(message).font(.caption.weight(.semibold)).foregroundStyle(Theme.red)
            }
        case .idle:
            EmptyView()
        }
    }

    // MARK: Content

    @ViewBuilder
    private var content: some View {
        if case .failed(let message) = model.sync, model.players.isEmpty {
            messageState(icon: "wifi.slash", title: "Couldn't load the leaderboard", message: message, retry: { model.refresh() }, showLink: true)
        } else if model.players.isEmpty {
            warmingUp
        } else {
            let count = model.results.count
            let plus = count >= 200 ? "+" : ""
            let header: String = !searching
                ? "Top 10 · \(model.division.label) · \(model.region.label)"
                : count == 0 ? "" : model.isLoading ? "\(count)\(plus) matches so far" : "\(count)\(plus) match\(count == 1 ? "" : "es")"
            if !header.isEmpty {
                Text(header)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Theme.muted)
                    .padding(.top, 6)
                    .padding(.horizontal, 4)
            }
            if searching && model.results.isEmpty {
                let q = model.query.trimmingCharacters(in: .whitespaces)
                messageState(
                    icon: "person.crop.circle.badge.questionmark",
                    title: model.isLoading ? "Still searching…" : "No players found",
                    message: model.isLoading
                        ? "Nobody named “\(q)” yet. The rest of the leaderboard is still loading."
                        : "Nobody named “\(q)” in \(model.division.label)\(model.region == .global ? "" : " (\(model.region.label))"). Only players with Championship Points this season are listed.",
                    retry: nil,
                    showLink: false
                )
            } else {
                ForEach(model.results) { player in
                    Button { open(player) } label: { PlayerRow(player: player, isMe: model.isMe(player)) }
                        .buttonStyle(PlayersPressStyle())
                }
            }
            Button { openURL(model.division.pageURL) } label: {
                Label("Full leaderboard on Pokémon.com", systemImage: "arrow.up.right.square")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.blue)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
            }
            .buttonStyle(.plain)
        }
    }

    private var warmingUp: some View {
        VStack(spacing: 10) {
            ZStack {
                Circle().fill(Theme.gold.opacity(0.18))
                Circle().strokeBorder(Theme.gold, lineWidth: 2)
                if model.isLoading {
                    ProgressView().tint(Theme.red).controlSize(.large)
                } else {
                    Image(systemName: "trophy.fill")
                        .font(.system(size: 30, weight: .semibold))
                        .foregroundStyle(Theme.red)
                }
            }
            .frame(width: 72, height: 72)
            Text("Who's leading the season?")
                .font(.title2.bold()).foregroundStyle(Theme.ink)
                .multilineTextAlignment(.center)
            Text("Look up any player's Championship Points on the official Play! Pokémon leaderboard. The first load indexes every ranked player, then searches are instant.")
                .font(.subheadline).foregroundStyle(Theme.muted)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 40)
        .padding(.horizontal, 24)
    }

    private func messageState(icon: String, title: String, message: String, retry: (() -> Void)?, showLink: Bool) -> some View {
        VStack(spacing: 8) {
            Image(systemName: icon).font(.system(size: 34)).foregroundStyle(Theme.muted)
            Text(title).font(.title3.bold()).foregroundStyle(Theme.ink).multilineTextAlignment(.center)
            Text(message).font(.subheadline).foregroundStyle(Theme.muted).multilineTextAlignment(.center)
            if retry != nil || showLink {
                HStack(spacing: 10) {
                    if let retry {
                        Button("Try Again", action: retry)
                            .buttonStyle(.borderedProminent)
                            .tint(Theme.blue)
                    }
                    if showLink {
                        Button("Open on Pokémon.com") { openURL(model.division.pageURL) }
                            .buttonStyle(.bordered)
                            .tint(Theme.blue)
                    }
                }
                .padding(.top, 6)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        .cardSurface()
    }
}

private struct PlayersPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.snappy(duration: 0.18), value: configuration.isPressed)
    }
}

private struct RankBadge: View {
    let rank: Int
    var size: CGFloat = 40

    var body: some View {
        let tint = rankTint(rank)
        let text = "#\(rank.grouped)"
        let font: Font = size > 50 ? .title3.bold()
            : text.count > 6 ? .system(size: 11, weight: .bold)
            : text.count > 4 ? .system(size: 13, weight: .bold)
            : .system(size: 15, weight: .bold)
        Text(text)
            .font(font)
            .monospacedDigit()
            .lineLimit(1)
            .foregroundStyle(tint == nil ? Theme.ink : .white)
            .padding(.horizontal, 6)
            .frame(minWidth: size, minHeight: size)
            .background(tint ?? Theme.canvas, in: .capsule)
            .overlay { if tint == nil { Capsule().strokeBorder(Theme.hairline) } }
    }
}

private struct PlayerRow: View {
    let player: LeaderboardPlayer
    let isMe: Bool

    var body: some View {
        HStack(spacing: 12) {
            RankBadge(rank: player.rank)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(player.name)
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(Theme.ink)
                        .lineLimit(1)
                    if isMe {
                        Image(systemName: "star.fill").font(.caption).foregroundStyle(Theme.gold)
                    }
                }
                if let country = player.country {
                    Text(country).font(.footnote).foregroundStyle(Theme.muted).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            VStack(alignment: .trailing, spacing: 0) {
                Text(player.points.grouped)
                    .font(.title3.bold())
                    .monospacedDigit()
                    .foregroundStyle(Theme.red)
                Text("CP")
                    .font(.system(size: 11, weight: .bold))
                    .tracking(0.6)
                    .foregroundStyle(Theme.muted)
            }
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Theme.muted.opacity(0.6))
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface()
        .overlay {
            if isMe { RoundedRectangle(cornerRadius: 20).strokeBorder(Theme.gold, lineWidth: 2) }
        }
        .contentShape(.rect)
    }
}

private struct MeTile: View {
    let player: LeaderboardPlayer
    let total: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 6) {
                Image(systemName: "star.fill").font(.caption).foregroundStyle(Theme.gold)
                Text("YOU").font(.system(size: 11, weight: .bold)).tracking(1).foregroundStyle(Theme.ink)
                Spacer()
                Text(topPercent(rank: player.rank, total: total))
                    .font(.caption.weight(.bold))
                    .foregroundStyle(Theme.blue)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(Theme.blue.opacity(0.1), in: .rect(cornerRadius: 6))
            }
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(player.name).font(.title3.bold()).foregroundStyle(Theme.ink).lineLimit(1)
                    Text("Rank #\(player.rank.grouped)" + (player.country.map { " · \($0)" } ?? ""))
                        .font(.subheadline).foregroundStyle(Theme.muted)
                }
                Spacer(minLength: 8)
                VStack(alignment: .trailing, spacing: 0) {
                    Text(player.points.grouped)
                        .font(.system(size: 34, weight: .bold))
                        .monospacedDigit()
                        .foregroundStyle(Theme.red)
                    Text("CHAMPIONSHIP POINTS")
                        .font(.system(size: 10, weight: .bold))
                        .tracking(0.6)
                        .foregroundStyle(Theme.muted)
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface()
        .overlay { RoundedRectangle(cornerRadius: 20).strokeBorder(Theme.gold, lineWidth: 2) }
        .contentShape(.rect)
    }
}

private struct PlayerDetailSheet: View {
    let player: LeaderboardPlayer
    let players: [LeaderboardPlayer]
    let total: Int
    let division: Division
    let region: LeaderboardRegion
    let isMe: Bool
    let onToggleMe: () -> Void
    @Environment(\.openURL) private var openURL
    @State private var tracked = false

    private var countryRank: Int? {
        guard let c = player.country else { return nil }
        return players.reduce(1) { $1.country == c && $1.rank < player.rank ? $0 + 1 : $0 }
    }

    private var above: LeaderboardPlayer? { players.last { $0.rank < player.rank } }
    private var leader: LeaderboardPlayer? { players.first }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 14) {
                    RankBadge(rank: player.rank, size: 56)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(player.name).font(.title2.bold()).foregroundStyle(Theme.ink)
                        Text([player.country, division.label, region == .global ? nil : region.label].compactMap { $0 }.joined(separator: " · "))
                            .font(.subheadline).foregroundStyle(Theme.muted)
                    }
                }
                VStack(spacing: 0) {
                    Text(player.points.grouped)
                        .font(.system(size: 52, weight: .bold))
                        .monospacedDigit()
                        .foregroundStyle(Theme.red)
                    Text("CHAMPIONSHIP POINTS")
                        .font(.caption.weight(.bold))
                        .tracking(1)
                        .foregroundStyle(Theme.muted)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 18)
                .cardSurface()

                Grid(horizontalSpacing: 10, verticalSpacing: 10) {
                    GridRow {
                        stat("#\(player.rank.grouped)", "of \(total.grouped) players")
                        stat(topPercent(rank: player.rank, total: total), "percentile", tint: Theme.blue)
                    }
                    GridRow {
                        stat(countryRank.map { "#\($0.grouped)" } ?? "—", player.country.map { "in \($0)" } ?? "country rank")
                        if let above {
                            stat("+\(max(1, above.points - player.points + 1).grouped)", "CP to pass #\(above.rank.grouped)")
                        } else {
                            stat("Leader", "No one ahead", tint: Theme.gold)
                        }
                    }
                }

                if let leader, leader.id != player.id {
                    Text("\((leader.points - player.points).grouped) CP behind #1 \(leader.name)")
                        .font(.footnote)
                        .foregroundStyle(Theme.muted)
                        .frame(maxWidth: .infinity)
                }

                if player.inCutoff {
                    Text("Inside the Worlds invite cutoff")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Theme.ink)
                        .frame(maxWidth: .infinity)
                        .padding(10)
                        .background(Theme.gold.opacity(0.2), in: .rect(cornerRadius: 10))
                }

                Button {
                    tracked.toggle()
                    onToggleMe()
                } label: {
                    Label(isMe ? "Stop Tracking" : "Track as Me", systemImage: isMe ? "star.slash" : "star.fill")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .frame(height: 50)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.roundedRectangle(radius: 14))
                .tint(isMe ? Theme.ink : Theme.red)
                .sensoryFeedback(.success, trigger: tracked)

                Button { openURL(division.pageURL) } label: {
                    Label("Leaderboard on Pokémon.com", systemImage: "arrow.up.right.square")
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

    private func stat(_ value: String, _ label: String, tint: Color = Theme.ink) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.title3.bold()).monospacedDigit().foregroundStyle(tint).lineLimit(1).minimumScaleFactor(0.7)
            Text(label).font(.caption).foregroundStyle(Theme.muted).lineLimit(2)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface()
    }
}
