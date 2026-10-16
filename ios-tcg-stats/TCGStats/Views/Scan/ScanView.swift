import AVFoundation
import SwiftUI

struct ScanView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @State private var viewModel = ScanViewModel()
    @State private var isSearching: Bool = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            switch viewModel.cameraState {
            case .ready:
                CameraPreviewView(session: viewModel.camera.session)
                    .ignoresSafeArea()
                ScanFrameOverlay(isLocked: viewModel.detectedKey != nil)
                    .ignoresSafeArea()
            case .unknown:
                ProgressView().tint(.white)
            case .denied:
                unavailable(
                    icon: "camera.fill",
                    title: "Camera access is off",
                    message: "Allow camera access in Settings to scan cards, or search by name.",
                    primary: ("Open Settings", {
                        if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                    })
                )
            case .noDevice, .failed:
                unavailable(
                    icon: "camera.metering.unknown",
                    title: "No camera available",
                    message: "This device has no camera we can use. You can still look up any card by name.",
                    primary: nil
                )
            }
        }
        .overlay(alignment: .top) { topBar }
        .safeAreaInset(edge: .bottom) { bottomPanel }
        .task {
            await viewModel.start(matcher: model.matcher)
        }
        .onDisappear { viewModel.stop() }
        .onChange(of: model.isLoaded) { _, _ in
            viewModel.updateMatcher(model.matcher)
        }
        .sheet(isPresented: $isSearching) { CardSearchView() }
    }

    private var topBar: some View {
        HStack {
            if viewModel.cameraState == .ready && viewModel.camera.hasTorch {
                circleButton(viewModel.isTorchOn ? "bolt.fill" : "bolt.slash.fill", label: "Flashlight") {
                    viewModel.toggleTorch()
                }
            }
            Spacer()
            circleButton("magnifyingglass", label: "Search cards") { isSearching = true }
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
    }

    private func circleButton(_ icon: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.body.weight(.semibold))
                .foregroundStyle(.white)
                .frame(width: 46, height: 46)
                .background(.ultraThinMaterial.opacity(0.9), in: .circle)
                .environment(\.colorScheme, .dark)
        }
        .buttonStyle(PressableStyle())
        .accessibilityLabel(label)
    }

    @ViewBuilder
    private var bottomPanel: some View {
        VStack(spacing: 12) {
            if let key = viewModel.detectedKey, let card = model.dataset?.cards[key] {
                DetectedBanner(card: card, usage: model.dataset?.recentUsage[key] ?? 0) {
                    model.showResults(for: key)
                    viewModel.clearDetection()
                } onDismiss: {
                    withAnimation(.snappy) { viewModel.clearDetection() }
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
            } else if viewModel.cameraState == .ready {
                statusPill
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 10)
    }

    @ViewBuilder
    private var statusPill: some View {
        Group {
            switch model.loadState {
            case .loading(let p):
                HStack(spacing: 8) {
                    ProgressView().tint(.white).controlSize(.small)
                    Text("Loading tournament data · \(Int(p * 100))%")
                }
            case .failed:
                Button {
                    Task { await model.load(force: true) }
                } label: {
                    Label("Couldn't load data · Retry", systemImage: "arrow.clockwise")
                }
            default:
                Text("Align your card in the frame")
            }
        }
        .font(.subheadline.weight(.semibold))
        .foregroundStyle(.white)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.black.opacity(0.45), in: .capsule)
    }

    private func unavailable(icon: String, title: String, message: String, primary: (String, () -> Void)?) -> some View {
        VStack(spacing: 16) {
            Image(systemName: icon)
                .font(.system(size: 44))
                .foregroundStyle(Theme.red)
            Text(title)
                .font(.title2.weight(.bold))
                .foregroundStyle(Theme.ink)
            Text(message)
                .font(.body)
                .foregroundStyle(Theme.muted)
                .multilineTextAlignment(.center)
            if let primary {
                Button(primary.0, action: primary.1)
                    .buttonStyle(.borderedProminent)
                    .tint(Theme.blue)
            }
            Button {
                isSearching = true
            } label: {
                Label("Search by Name", systemImage: "magnifyingglass")
                    .font(.headline)
                    .frame(minWidth: 200, minHeight: 50)
                    .foregroundStyle(.white)
                    .background(Theme.blue, in: .capsule)
            }
            .buttonStyle(PressableStyle())
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background { CanvasBackground() }
    }
}

private struct DetectedBanner: View {
    let card: CardIdentity
    let usage: Double
    let onView: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            CardImageView(card: card, cornerRadius: 4)
                .frame(width: 48)
                .shadow(color: .black.opacity(0.15), radius: 3, y: 2)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(card.name) detected")
                    .font(.headline)
                    .foregroundStyle(Theme.ink)
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
                Text("\(card.printLabel) · \(CardText.percent(usage)) of decks")
                    .font(.subheadline)
                    .foregroundStyle(Theme.muted)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            Button(action: onView) {
                Text("View results")
                    .font(.headline)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 18)
                    .frame(minHeight: 48)
                    .background(Theme.blue, in: .capsule)
            }
            .buttonStyle(PressableStyle())
        }
        .padding(14)
        .cardSurface(radius: 24)
        .overlay(alignment: .topTrailing) {
            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(Theme.muted)
                    .frame(width: 24, height: 24)
                    .background(Theme.canvas, in: .circle)
            }
            .offset(x: 6, y: -6)
            .accessibilityLabel("Dismiss")
        }
    }
}

/// Dimmed surround with card-shaped corner brackets and a scanning sweep.
private struct ScanFrameOverlay: View {
    let isLocked: Bool
    @State private var sweep: Bool = false

    var body: some View {
        GeometryReader { geo in
            let width = min(geo.size.width * 0.78, 360)
            let height = width * 88 / 63
            let rect = CGRect(
                x: (geo.size.width - width) / 2,
                y: (geo.size.height - height) / 2 - 30,
                width: width,
                height: height
            )
            ZStack {
                Path { p in
                    p.addRect(CGRect(origin: .zero, size: geo.size))
                    p.addRoundedRect(in: rect, cornerSize: CGSize(width: 22, height: 22))
                }
                .fill(Color.black.opacity(0.35), style: FillStyle(eoFill: true))

                CornerBrackets()
                    .stroke(isLocked ? Theme.gold : .white, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                    .frame(width: rect.width + 12, height: rect.height + 12)
                    .position(x: rect.midX, y: rect.midY)
                    .scaleEffect(isLocked ? 0.98 : 1)
                    .animation(.spring(response: 0.35, dampingFraction: 0.6), value: isLocked)

                if !isLocked {
                    LinearGradient(colors: [.clear, Theme.gold.opacity(0.55), .clear], startPoint: .leading, endPoint: .trailing)
                        .frame(width: rect.width - 24, height: 2)
                        .shadow(color: Theme.gold, radius: 6)
                        .position(x: rect.midX, y: sweep ? rect.maxY - 16 : rect.minY + 16)
                }
            }
        }
        .allowsHitTesting(false)
        .onAppear {
            withAnimation(.easeInOut(duration: 1.8).repeatForever(autoreverses: true)) { sweep = true }
        }
    }
}

private struct CornerBrackets: Shape {
    func path(in rect: CGRect) -> Path {
        let len: CGFloat = 36
        let r: CGFloat = 24
        var p = Path()
        // Top-left
        p.move(to: CGPoint(x: rect.minX, y: rect.minY + len + r))
        p.addLine(to: CGPoint(x: rect.minX, y: rect.minY + r))
        p.addQuadCurve(to: CGPoint(x: rect.minX + r, y: rect.minY), control: CGPoint(x: rect.minX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.minX + r + len, y: rect.minY))
        // Top-right
        p.move(to: CGPoint(x: rect.maxX - r - len, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX - r, y: rect.minY))
        p.addQuadCurve(to: CGPoint(x: rect.maxX, y: rect.minY + r), control: CGPoint(x: rect.maxX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.minY + r + len))
        // Bottom-right
        p.move(to: CGPoint(x: rect.maxX, y: rect.maxY - r - len))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - r))
        p.addQuadCurve(to: CGPoint(x: rect.maxX - r, y: rect.maxY), control: CGPoint(x: rect.maxX, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.maxX - r - len, y: rect.maxY))
        // Bottom-left
        p.move(to: CGPoint(x: rect.minX + r + len, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.minX + r, y: rect.maxY))
        p.addQuadCurve(to: CGPoint(x: rect.minX, y: rect.maxY - r), control: CGPoint(x: rect.minX, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.minX, y: rect.maxY - r - len))
        return p
    }
}

/// Hosts an `AVCaptureVideoPreviewLayer`.
struct CameraPreviewView: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewUIView {
        let view = PreviewUIView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: PreviewUIView, context: Context) {}

    final class PreviewUIView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer {
            // swiftlint:disable:next force_cast
            layer as! AVCaptureVideoPreviewLayer
        }
    }
}
