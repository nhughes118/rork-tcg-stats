import Charts
import SwiftUI

/// Card usage, trend, top decks and tournament results.
struct CardResultsView: View {
    @Environment(AppModel.self) private var model
    let cardKey: String
    var isTabRoot: Bool = false

    @State private var window: StatsWindow = .eightWeeks

    var body: some View {
        let stats = model.stats(for: cardKey, window: window)
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if isTabRoot {
                    BrandHeader { menu(stats: stats) }
                        .padding(.top, 4)
                }
                SourcePicker()
                if let stats {
                    HeroSection(stats: stats, window: window)
                    TrendSection(stats: stats, window: $window)
                    TopDecksSection(stats: stats, cardKey: stats.card.key, window: window)
                    TournamentsSection(stats: stats, cardKey: stats.card.key, window: window)
                    footer(stats: stats)
                } else {
                    ResultsPlaceholder(card: model.identity(for: cardKey))
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .background { CanvasBackground() }
        .navigationTitle(isTabRoot ? "" : (stats?.card.name ?? "Card"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(isTabRoot ? .hidden : .visible, for: .navigationBar)
        .toolbar {
            if !isTabRoot {
                ToolbarItem(placement: .topBarTrailing) { menu(stats: stats) }
            }
        }
        .refreshable { await model.load(force: true) }
    }

    private func menu(stats: CardStats?) -> some View {
        Menu {
            Button {
                model.selectedTab = .scan
            } label: {
                Label("Scan another card", systemImage: "camera.viewfinder")
            }
            if let url = stats?.card.limitlessURL {
                Link(destination: url) {
                    Label("Open on Limitless TCG", systemImage: "safari")
                }
            }
            Button {
                Task { await model.load(force: true) }
            } label: {
                Label("Refresh data", systemImage: "arrow.clockwise")
            }
        } label: {
            Image(systemName: "ellipsis")
                .font(.body.weight(.semibold))
                .foregroundStyle(Theme.ink)
                .frame(width: 44, height: 44)
                .background(.regularMaterial, in: .circle)
        }
        .accessibilityLabel("More")
    }

    private func footer(stats: CardStats) -> some View {
        let scope = model.source == .official
            ? "top-\(LimitlessClient.officialListLimit) decklists from \(stats.tournamentCount) official tournaments"
            : "\(stats.totalDecks) decklists from \(stats.tournamentCount) Standard tournaments"
        return VStack(spacing: 4) {
            Text("Based on \(scope)")
            Text("Data from Limitless TCG · \(model.source.label)")
                .fontWeight(.semibold)
        }
        .font(.caption)
        .foregroundStyle(Theme.muted)
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .padding(.top, 4)
    }
}

private struct HeroSection: View {
    let stats: CardStats
    let window: StatsWindow
    @State private var appeared: Bool = false

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            CardImageView(card: stats.card, large: true, cornerRadius: 12)
                .frame(width: 150)
                .shadow(color: .black.opacity(0.22), radius: 14, x: 4, y: 10)
                .rotationEffect(.degrees(appeared ? -6 : 0))
                .scaleEffect(appeared ? 1 : 0.92)
                .padding(.top, 8)
                .padding(.leading, 6)
                .zIndex(1)

            VStack(alignment: .leading, spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(stats.card.name)
                        .font(.system(.title, weight: .bold))
                        .foregroundStyle(Theme.ink)
                        .lineLimit(2)
                        .minimumScaleFactor(0.7)
                    Text(stats.card.printLabel)
                        .font(.subheadline)
                        .foregroundStyle(Theme.muted)
                }
                .padding(.leading, 18)

                HStack(spacing: 12) {
                    UsageRing(value: stats.usage, lineWidth: 10)
                        .frame(width: 104, height: 104)
                    VStack(alignment: .leading, spacing: 6) {
                        if let delta = stats.delta {
                            HStack(spacing: 3) {
                                Image(systemName: delta >= 0 ? "arrowtriangle.up.fill" : "arrowtriangle.down.fill")
                                    .font(.caption2)
                                Text("\(delta >= 0 ? "+" : "")\(CardText.percent(delta))")
                                    .monospacedDigit()
                            }
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(delta >= 0 ? Theme.green : Theme.red)
                            Text("vs last month")
                                .font(.caption)
                                .foregroundStyle(Theme.muted)
                        }
                        Text("\(String(format: "%.1f", stats.averageCopies)) avg copies")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(Theme.blue)
                    }
                }
                .padding(.vertical, 12)
                .padding(.leading, 28)
                .padding(.trailing, 12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .cardSurface(radius: 24)
                .padding(.leading, -12)
            }
            .padding(.top, 10)
        }
        .onAppear {
            withAnimation(.spring(response: 0.7, dampingFraction: 0.7)) { appeared = true }
        }
    }
}

private struct TrendSection: View {
    let stats: CardStats
    @Binding var window: StatsWindow

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("Usage Trend")
                    .font(.title3.weight(.bold))
                    .foregroundStyle(Theme.ink)
                Spacer()
                Picker("Range", selection: $window.animation(.snappy)) {
                    ForEach(StatsWindow.allCases) { w in
                        Text(w.label).tag(w)
                    }
                }
                .pickerStyle(.segmented)
                .frame(width: 180)
            }
            if stats.trend.count >= 2 {
                Chart(stats.trend) { point in
                    AreaMark(
                        x: .value("Week", point.weekStart),
                        y: .value("Usage", point.usage * 100)
                    )
                    .interpolationMethod(.monotone)
                    .foregroundStyle(
                        LinearGradient(colors: [Theme.red.opacity(0.22), Theme.red.opacity(0.02)], startPoint: .top, endPoint: .bottom)
                    )
                    LineMark(
                        x: .value("Week", point.weekStart),
                        y: .value("Usage", point.usage * 100)
                    )
                    .interpolationMethod(.monotone)
                    .foregroundStyle(Theme.red)
                    .lineStyle(StrokeStyle(lineWidth: 2.5, lineCap: .round))
                    PointMark(
                        x: .value("Week", point.weekStart),
                        y: .value("Usage", point.usage * 100)
                    )
                    .foregroundStyle(Theme.red)
                    .symbolSize(point.id == stats.trend.last?.id ? 70 : 28)
                }
                .chartYAxis {
                    AxisMarks(position: .leading) { value in
                        AxisGridLine().foregroundStyle(Theme.hairline)
                        AxisValueLabel {
                            if let v = value.as(Double.self) { Text("\(Int(v))%") }
                        }
                    }
                }
                .chartXAxis {
                    AxisMarks(values: .stride(by: .weekOfYear, count: stats.trend.count > 6 ? 2 : 1)) { _ in
                        AxisGridLine().foregroundStyle(Theme.hairline)
                        AxisValueLabel(format: .dateTime.month(.abbreviated).day())
                    }
                }
                .chartYScale(domain: 0...yMax)
                .frame(height: 170)
            } else {
                Text("Not enough weeks of data for a trend yet.")
                    .font(.subheadline)
                    .foregroundStyle(Theme.muted)
                    .frame(maxWidth: .infinity, minHeight: 80)
            }
        }
        .padding(16)
        .cardSurface()
    }

    private var yMax: Double {
        let peak = (stats.trend.map(\.usage).max() ?? 0) * 100
        return max(5, (peak * 1.25 / 5).rounded(.up) * 5)
    }
}

private struct TopDecksSection: View {
    @Environment(AppModel.self) private var model
    let stats: CardStats
    let cardKey: String
    let window: StatsWindow

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            SectionHeader(title: "Top Decks", action: stats.topDecks.count > 3 ? { push(.allDecks(cardKey: cardKey, window: window)) } : nil)
                .padding(.bottom, 6)
            if stats.topDecks.isEmpty {
                emptyRow("No decks in this period")
            }
            ForEach(Array(stats.topDecks.prefix(3).enumerated()), id: \.element.id) { index, deck in
                if index > 0 { Divider().overlay(Theme.hairline) }
                Button {
                    push(.deck(cardKey: cardKey, deckName: deck.name, window: window))
                } label: {
                    DeckRow(rank: index + 1, deck: deck)
                }
                .buttonStyle(PressableStyle())
            }
        }
        .padding(16)
        .cardSurface()
    }

    private func push(_ route: AppRoute) {
        if model.selectedTab == .history { model.historyPath.append(route) } else { model.resultsPath.append(route) }
    }
}

struct DeckRow: View {
    let rank: Int?
    let deck: DeckShare

    var body: some View {
        HStack(spacing: 12) {
            if let rank {
                Text("\(rank)")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.muted)
                    .frame(width: 18)
            }
            DeckIconView(icons: deck.icons, size: 46)
            VStack(alignment: .leading, spacing: 2) {
                Text(deck.name)
                    .font(.body.weight(.medium))
                    .foregroundStyle(Theme.ink)
                    .lineLimit(1)
                Text("\(deck.count) decks · best \(CardText.ordinal(deck.bestPlacing))")
                    .font(.caption)
                    .foregroundStyle(Theme.muted)
            }
            Spacer(minLength: 8)
            Text(CardText.percent(deck.share))
                .font(.body.weight(.bold))
                .monospacedDigit()
                .foregroundStyle(Theme.ink)
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Theme.muted.opacity(0.6))
        }
        .padding(.vertical, 6)
        .contentShape(.rect)
    }
}

private struct TournamentsSection: View {
    @Environment(AppModel.self) private var model
    let stats: CardStats
    let cardKey: String
    let window: StatsWindow

    var body: some View {
        let isOfficial = model.source == .official
        let ordered = isOfficial ? stats.appearances.sorted { $0.date > $1.date } : stats.topAppearances
        let top = Array(ordered.prefix(4))
        VStack(alignment: .leading, spacing: 4) {
            SectionHeader(title: isOfficial ? "Recent Tournament Results" : "Top Tournament Results", action: stats.appearances.count > top.count ? { push(.allTournaments(cardKey: cardKey, window: window)) } : nil)
                .padding(.bottom, 6)
            if top.isEmpty {
                emptyRow("Not played in tournaments this period")
            }
            ForEach(Array(top.enumerated()), id: \.element.id) { index, appearance in
                if index > 0 { Divider().overlay(Theme.hairline) }
                Button {
                    push(.tournament(id: appearance.tournamentID, cardKey: cardKey))
                } label: {
                    TournamentRow(appearance: appearance)
                }
                .buttonStyle(PressableStyle())
            }
        }
        .padding(16)
        .cardSurface()
    }

    private func push(_ route: AppRoute) {
        if model.selectedTab == .history { model.historyPath.append(route) } else { model.resultsPath.append(route) }
    }
}

struct TournamentRow: View {
    let appearance: TournamentAppearance

    var body: some View {
        HStack(spacing: 12) {
            TrophyIcon(placing: appearance.best?.placing)
            VStack(alignment: .leading, spacing: 3) {
                Text(appearance.name)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Theme.ink)
                    .lineLimit(1)
                HStack(spacing: 6) {
                    Text(CardText.ordinal(appearance.best?.placing))
                        .fontWeight(.semibold)
                        .foregroundStyle(Theme.ink.opacity(0.8))
                    Text(appearance.best?.deckName ?? "")
                        .lineLimit(1)
                }
                .font(.subheadline)
                .foregroundStyle(Theme.muted)
                Text("\(appearance.date.formatted(.dateTime.month(.abbreviated).day())) · \(appearance.players) players · \(CardText.percent(appearance.usage, digits: 0)) usage")
                    .font(.caption)
                    .foregroundStyle(Theme.muted.opacity(0.85))
            }
            Spacer(minLength: 8)
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Theme.muted.opacity(0.6))
        }
        .padding(.vertical, 6)
        .contentShape(.rect)
    }
}

@ViewBuilder
func emptyRow(_ text: String) -> some View {
    Text(text)
        .font(.subheadline)
        .foregroundStyle(Theme.muted)
        .frame(maxWidth: .infinity, minHeight: 56)
}

private struct ResultsPlaceholder: View {
    @Environment(AppModel.self) private var model
    let card: CardIdentity?

    var body: some View {
        VStack(spacing: 16) {
            if let card {
                CardImageView(card: card, large: true, cornerRadius: 12)
                    .frame(width: 140)
                    .rotationEffect(.degrees(-5))
                    .shadow(color: .black.opacity(0.18), radius: 12, y: 8)
                Text(card.name).font(.title2.weight(.bold)).foregroundStyle(Theme.ink)
            }
            switch model.loadState {
            case .failed(let message):
                Text(message).font(.subheadline).foregroundStyle(Theme.muted).multilineTextAlignment(.center)
                Button("Try Again") { Task { await model.load(force: true) } }
                    .buttonStyle(.borderedProminent)
                    .tint(Theme.blue)
            case .loaded:
                Text(model.source == .official
                     ? "This card hasn't appeared in recent official tournament decklists. Try Online."
                     : "This card hasn't appeared in recent online tournament decklists. Try Official.")
                    .font(.subheadline).foregroundStyle(Theme.muted).multilineTextAlignment(.center)
            default:
                LoadingCard()
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 40)
    }
}

/// Segmented toggle between online and official (in-person) tournament data.
struct SourcePicker: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 6) {
            Picker("Tournament source", selection: Binding(
                get: { model.source },
                set: { model.selectSource($0) }
            )) {
                ForEach(DataSource.allCases) { source in
                    Text(source.label).tag(source)
                }
            }
            .pickerStyle(.segmented)
            .tint(Theme.red)
            Text(model.source.tagline)
                .font(.caption2.weight(.medium))
                .foregroundStyle(Theme.muted)
                .frame(maxWidth: .infinity)
        }
        .accessibilityIdentifier("sourcePicker")
    }
}

/// Progress tile shown while tournament data downloads.
struct LoadingCard: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let progress: Double = {
            if case .loading(let p) = model.loadState { return p }
            return 0
        }()
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("Gathering tournament decklists…")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.ink)
            }
            ProgressView(value: progress)
                .tint(Theme.red)
                .animation(.easeOut, value: progress)
            Text("Reading recent Standard events from Limitless TCG")
                .font(.caption)
                .foregroundStyle(Theme.muted)
        }
        .padding(16)
        .frame(maxWidth: 340)
        .cardSurface()
    }
}
