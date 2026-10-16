import AVFoundation
import SwiftUI
import UIKit

@Observable
final class ScanViewModel {
    enum CameraState: Equatable {
        case unknown, ready, denied, noDevice, failed
    }

    var cameraState: CameraState = .unknown
    var detectedKey: String?
    var isTorchOn: Bool = false

    let camera = CameraService()
    @ObservationIgnored private var candidateKey: String?
    @ObservationIgnored private var candidateHits: Int = 0
    @ObservationIgnored private var missCount: Int = 0

    func start(matcher: CardMatcher?) async {
        if cameraState == .unknown {
            let status = AVCaptureDevice.authorizationStatus(for: .video)
            var granted = status == .authorized
            if status == .notDetermined {
                granted = await AVCaptureDevice.requestAccess(for: .video)
            }
            guard granted else {
                cameraState = .denied
                return
            }
            switch await camera.configure() {
            case .ready: cameraState = .ready
            case .noDevice: cameraState = .noDevice
            case .failed: cameraState = .failed
            }
        }
        guard cameraState == .ready else { return }
        updateMatcher(matcher)
        camera.start()
    }

    func stop() {
        camera.setHandler(nil)
        camera.stop()
        if isTorchOn { toggleTorch() }
    }

    func updateMatcher(_ matcher: CardMatcher?) {
        guard let matcher else {
            camera.setHandler(nil)
            return
        }
        camera.setHandler { [weak self] lines in
            let key = matcher.match(lines)
            Task { @MainActor [weak self] in
                self?.handle(key)
            }
        }
    }

    func toggleTorch() {
        isTorchOn.toggle()
        camera.setTorch(isTorchOn)
    }

    func clearDetection() {
        detectedKey = nil
        candidateKey = nil
        candidateHits = 0
    }

    private func handle(_ key: String?) {
        guard let key else {
            missCount += 1
            if missCount > 8 { candidateKey = nil; candidateHits = 0 }
            return
        }
        missCount = 0
        if key == candidateKey {
            candidateHits += 1
        } else {
            candidateKey = key
            candidateHits = 1
        }
        if candidateHits >= 2, detectedKey != key {
            withAnimation(.spring(response: 0.45, dampingFraction: 0.8)) {
                detectedKey = key
            }
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        }
    }
}
