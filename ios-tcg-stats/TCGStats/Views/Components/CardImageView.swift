import SwiftUI

/// Card artwork from the Limitless CDN with a styled placeholder.
struct CardImageView: View {
    let card: CardIdentity?
    var large: Bool = false
    var cornerRadius: CGFloat = 6

    var body: some View {
        Color(hex: 0xEFE9DC)
            .aspectRatio(63.0 / 88.0, contentMode: .fit)
            .overlay {
                AsyncImage(url: card?.imageURL(large: large), transaction: Transaction(animation: .easeOut(duration: 0.25))) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable().aspectRatio(contentMode: .fill)
                    default:
                        Image(systemName: icon)
                            .font(.system(size: large ? 40 : 14, weight: .semibold))
                            .foregroundStyle(Theme.muted.opacity(0.5))
                    }
                }
                .allowsHitTesting(false)
            }
            .clipShape(.rect(cornerRadius: cornerRadius))
            .overlay {
                RoundedRectangle(cornerRadius: cornerRadius)
                    .strokeBorder(Color.black.opacity(0.08), lineWidth: 0.5)
            }
            .accessibilityLabel(card?.name ?? "Card")
    }

    private var icon: String {
        switch card?.category {
        case .trainer: "person.fill"
        case .energy: "bolt.fill"
        default: "sparkles"
        }
    }
}

/// Deck archetype thumbnail built from Limitless Pokémon icons.
struct DeckIconView: View {
    let icons: [String]
    var size: CGFloat = 48

    var body: some View {
        RoundedRectangle(cornerRadius: 10)
            .fill(
                LinearGradient(colors: [Color(hex: 0xFFF3E6), Color(hex: 0xF3EBDD)], startPoint: .top, endPoint: .bottom)
            )
            .frame(width: size, height: size)
            .overlay {
                HStack(spacing: -size * 0.18) {
                    ForEach(Array(icons.prefix(2).enumerated()), id: \.offset) { _, icon in
                        AsyncImage(url: URL(string: "https://r2.limitlesstcg.net/pokemon/gen9/\(icon).png")) { phase in
                            if let image = phase.image {
                                image.resizable().interpolation(.none).scaledToFit()
                            } else {
                                Color.clear
                            }
                        }
                        .frame(width: icons.count > 1 ? size * 0.6 : size * 0.8, height: icons.count > 1 ? size * 0.6 : size * 0.8)
                    }
                    if icons.isEmpty {
                        Image(systemName: "rectangle.stack.fill")
                            .foregroundStyle(Theme.muted.opacity(0.5))
                    }
                }
            }
            .overlay {
                RoundedRectangle(cornerRadius: 10).strokeBorder(Theme.gold.opacity(0.3), lineWidth: 1)
            }
            .accessibilityHidden(true)
    }
}

/// Animated circular usage gauge.
struct UsageRing: View {
    let value: Double
    var lineWidth: CGFloat = 12
    var showsLabel: Bool = true
    var caption: String? = "of decks"

    @State private var progress: Double = 0

    var body: some View {
        ZStack {
            Circle().stroke(Theme.hairline, lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: max(progress, 0.005))
                .stroke(
                    AngularGradient(colors: [Theme.red.opacity(0.75), Theme.red], center: .center),
                    style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
                )
                .rotationEffect(.degrees(-90))
            if showsLabel {
                VStack(spacing: 0) {
                    Text(CardText.percent(value))
                        .font(.system(.title, design: .rounded, weight: .bold))
                        .monospacedDigit()
                        .foregroundStyle(Theme.ink)
                        .minimumScaleFactor(0.5)
                        .lineLimit(1)
                        .contentTransition(.numericText())
                    if let caption {
                        Text(caption)
                            .font(.caption)
                            .foregroundStyle(Theme.muted)
                    }
                }
                .padding(lineWidth + 4)
            }
        }
        .onAppear {
            withAnimation(.spring(response: 1.0, dampingFraction: 0.85).delay(0.1)) { progress = value }
        }
        .onChange(of: value) { _, newValue in
            withAnimation(.spring(response: 0.6, dampingFraction: 0.85)) { progress = newValue }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Used in \(CardText.percent(value)) of decks")
    }
}

struct SectionHeader: View {
    let title: String
    var action: (() -> Void)?

    var body: some View {
        HStack {
            Text(title)
                .font(.title3.weight(.bold))
                .foregroundStyle(Theme.ink)
            Spacer()
            if let action {
                Button("See All", action: action)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(Theme.blue)
            }
        }
    }
}

struct PlacingBadge: View {
    let placing: Int?

    var body: some View {
        Text(CardText.ordinal(placing))
            .font(.subheadline.weight(.bold))
            .monospacedDigit()
            .foregroundStyle(placing.map { $0 <= 4 } == true ? Theme.placeColor(placing) : Theme.muted)
            .frame(width: 44, alignment: .leading)
    }
}

struct TrophyIcon: View {
    let placing: Int?

    var body: some View {
        Image(systemName: (placing ?? 99) <= 4 ? "trophy.fill" : "medal.fill")
            .font(.title3)
            .foregroundStyle(Theme.placeColor(placing))
            .frame(width: 36)
            .accessibilityHidden(true)
    }
}
