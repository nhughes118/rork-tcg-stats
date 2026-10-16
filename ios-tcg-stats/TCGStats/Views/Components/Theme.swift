import SwiftUI

enum Theme {
    static let canvas = Color(hex: 0xF7F4EC)
    static let surface = Color.white
    static let red = Color(hex: 0xE3350D)
    static let blue = Color(hex: 0x2A75BB)
    static let gold = Color(hex: 0xE8B84A)
    static let silver = Color(hex: 0x9AA3AE)
    static let bronze = Color(hex: 0xC27C4A)
    static let ink = Color(hex: 0x15202E)
    static let muted = Color(hex: 0x6B7380)
    static let hairline = Color(hex: 0xECE7DC)
    static let green = Color(hex: 0x1F9D55)

    static func placeColor(_ placing: Int?) -> Color {
        switch placing {
        case 1: gold
        case 2: silver
        case 3, 4: bronze
        default: muted
        }
    }
}

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity
        )
    }
}

/// White rounded tile with a thin foil edge and soft layered shadow.
struct CardSurface: ViewModifier {
    var radius: CGFloat = 20

    func body(content: Content) -> some View {
        content
            .background(Theme.surface, in: .rect(cornerRadius: radius))
            .overlay {
                RoundedRectangle(cornerRadius: radius)
                    .strokeBorder(
                        LinearGradient(
                            colors: [Theme.gold.opacity(0.35), Color.white.opacity(0.2), Theme.hairline],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ),
                        lineWidth: 1
                    )
            }
            .shadow(color: Color(hex: 0x6B5A3A).opacity(0.06), radius: 2, y: 1)
            .shadow(color: Color(hex: 0x6B5A3A).opacity(0.08), radius: 16, y: 8)
    }
}

extension View {
    func cardSurface(radius: CGFloat = 20) -> some View {
        modifier(CardSurface(radius: radius))
    }
}

/// Springy press feedback for buttons.
struct PressableStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

/// Canvas background with the faint ball motif in the top-right corner.
struct CanvasBackground: View {
    var body: some View {
        Theme.canvas
            .overlay(alignment: .topTrailing) {
                BallMotif()
                    .frame(width: 260, height: 260)
                    .offset(x: 90, y: -70)
                    .opacity(0.22)
                    .allowsHitTesting(false)
            }
            .ignoresSafeArea()
    }
}

struct BallMotif: View {
    var body: some View {
        GeometryReader { geo in
            let s = min(geo.size.width, geo.size.height)
            ZStack {
                Circle().fill(Theme.red)
                Rectangle()
                    .fill(Theme.canvas)
                    .frame(height: s * 0.08)
                Circle()
                    .fill(Theme.canvas)
                    .frame(width: s * 0.34, height: s * 0.34)
                Circle()
                    .strokeBorder(Theme.red, lineWidth: s * 0.05)
                    .frame(width: s * 0.22, height: s * 0.22)
            }
            .frame(width: s, height: s)
        }
    }
}

/// Small app logo: red/white ball.
struct LogoMark: View {
    var size: CGFloat = 34

    var body: some View {
        ZStack {
            Circle().fill(Color.white)
            Circle()
                .trim(from: 0.5, to: 1)
                .fill(Theme.red)
            Rectangle().fill(Theme.ink).frame(height: size * 0.08)
            Circle().fill(Color.white).frame(width: size * 0.36)
            Circle().strokeBorder(Theme.ink, lineWidth: size * 0.07).frame(width: size * 0.36)
            Circle().strokeBorder(Theme.ink, lineWidth: size * 0.06)
        }
        .frame(width: size, height: size)
        .shadow(color: .black.opacity(0.12), radius: 4, y: 2)
    }
}

struct BrandHeader<Trailing: View>: View {
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(spacing: 10) {
            LogoMark()
            VStack(alignment: .leading, spacing: 0) {
                Text("TCG Stats")
                    .font(.title3.weight(.bold))
                    .foregroundStyle(Theme.ink)
                Text("Scan. Discover. Play.")
                    .font(.caption)
                    .foregroundStyle(Theme.muted)
            }
            Spacer()
            trailing()
        }
    }
}
