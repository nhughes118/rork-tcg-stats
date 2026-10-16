import AVFoundation
import Vision

/// Runs the capture session and performs throttled on-device text recognition.
nonisolated final class CameraService: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    enum SetupResult: Sendable { case ready, noDevice, failed }

    let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "tcgstats.camera.session")
    private let videoQueue = DispatchQueue(label: "tcgstats.camera.video")
    private var device: AVCaptureDevice?
    private var isConfigured = false
    private var bufferOrientation: CGImagePropertyOrientation = .right
    private var lastProcessed: CFAbsoluteTime = 0
    private let lock = NSLock()
    private var handler: (@Sendable ([RecognizedLine]) -> Void)?

    func setHandler(_ handler: (@Sendable ([RecognizedLine]) -> Void)?) {
        lock.lock(); self.handler = handler; lock.unlock()
    }

    var hasTorch: Bool { device?.hasTorch ?? false }

    func configure() async -> SetupResult {
        await withCheckedContinuation { continuation in
            sessionQueue.async {
                continuation.resume(returning: self.configureSync())
            }
        }
    }

    private func configureSync() -> SetupResult {
        if isConfigured { return .ready }
        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInWideAngleCamera, .builtInDualCamera, .builtInTripleCamera, .external],
            mediaType: .video,
            position: .unspecified
        )
        let devices = discovery.devices
        guard let camera = devices.first(where: { $0.position == .back }) ?? devices.first else {
            return .noDevice
        }
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        if session.canSetSessionPreset(.hd1280x720) { session.sessionPreset = .hd1280x720 }
        guard let input = try? AVCaptureDeviceInput(device: camera), session.canAddInput(input) else {
            return .failed
        }
        session.addInput(input)
        let output = AVCaptureVideoDataOutput()
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: videoQueue)
        guard session.canAddOutput(output) else { return .failed }
        session.addOutput(output)

        if camera.deviceType != .external, let connection = output.connection(with: .video), connection.isVideoRotationAngleSupported(90) {
            connection.videoRotationAngle = 90
            bufferOrientation = .up
        } else {
            bufferOrientation = camera.deviceType == .external ? .up : .right
        }
        if camera.isFocusModeSupported(.continuousAutoFocus), (try? camera.lockForConfiguration()) != nil {
            camera.focusMode = .continuousAutoFocus
            camera.unlockForConfiguration()
        }
        device = camera
        isConfigured = true
        return .ready
    }

    func start() {
        sessionQueue.async {
            guard self.isConfigured, !self.session.isRunning else { return }
            self.session.startRunning()
        }
    }

    func stop() {
        sessionQueue.async {
            guard self.session.isRunning else { return }
            self.session.stopRunning()
        }
    }

    func setTorch(_ on: Bool) {
        sessionQueue.async {
            guard let device = self.device, device.hasTorch, (try? device.lockForConfiguration()) != nil else { return }
            device.torchMode = on ? .on : .off
            device.unlockForConfiguration()
        }
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let now = CFAbsoluteTimeGetCurrent()
        guard now - lastProcessed > 0.6, let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        lastProcessed = now
        lock.lock(); let handler = self.handler; lock.unlock()
        guard let handler else { return }

        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = false
        request.minimumTextHeight = 0.015
        let imageHandler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: bufferOrientation, options: [:])
        do {
            try imageHandler.perform([request])
        } catch {
            return
        }
        let lines: [RecognizedLine] = (request.results ?? []).compactMap { obs in
            guard let top = obs.topCandidates(1).first else { return nil }
            return RecognizedLine(text: top.string, midY: Double(obs.boundingBox.midY))
        }
        handler(lines)
    }
}
