import AVFoundation
import Foundation
import Observation
import Speech

/// Voice input for the composer: the Speech framework's live transcription of the microphone, on this iPhone when it
/// can (`requiresOnDeviceRecognition`; otherwise Apple's server, as the system keyboard's dictation does). The words
/// go into the composer as they are heard; nothing is sent until the user sends. Audio is not kept.
@MainActor
@Observable
final class Dictation {
    enum State: Equatable {
        case idle
        case listening
        case failed(String)
    }

    private(set) var state: State = .idle
    /// The words heard so far in this dictation.
    private(set) var heard = ""

    @ObservationIgnored private let engine = AVAudioEngine()
    @ObservationIgnored private var request: SFSpeechAudioBufferRecognitionRequest?
    @ObservationIgnored private var task: SFSpeechRecognitionTask?
    @ObservationIgnored private var limit: Task<Void, Never>?
    @ObservationIgnored private var onText: ((String) -> Void)?

    var isListening: Bool { state == .listening }

    /// Starts listening; `onText` gets the whole transcription each time it grows.
    func start(onText: @escaping (String) -> Void) {
        guard state != .listening else { return }
        self.onText = onText
        heard = ""
        Task { await begin() }
    }

    func stop() {
        guard state == .listening || task != nil else { return }
        limit?.cancel()
        limit = nil
        if engine.isRunning {
            engine.stop()
            engine.inputNode.removeTap(onBus: 0)
        }
        request?.endAudio()
        task?.finish()
        task = nil
        request = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        if state == .listening { state = .idle }
    }

    private func begin() async {
        guard await Self.speechAllowed() else {
            state = .failed("Speech recognition is off for Pi-Bolt. Turn it on in Settings, Privacy & Security, Speech Recognition.")
            return
        }
        guard await AVAudioApplication.requestRecordPermission() else {
            state = .failed("The microphone is off for Pi-Bolt. Turn it on in Settings, Privacy & Security, Microphone.")
            return
        }
        guard let recognizer = SFSpeechRecognizer(), recognizer.isAvailable else {
            state = .failed("Dictation is not available right now.")
            return
        }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: .duckOthers)
            try session.setActive(true, options: .notifyOthersOnDeactivation)
            let request = SFSpeechAudioBufferRecognitionRequest()
            request.shouldReportPartialResults = true
            request.addsPunctuation = true
            if recognizer.supportsOnDeviceRecognition { request.requiresOnDeviceRecognition = true }
            self.request = request
            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak request] buffer, _ in
                request?.append(buffer)
            }
            engine.prepare()
            try engine.start()
            state = .listening
            task = recognizer.recognitionTask(with: request) { [weak self] result, error in
                let text = result?.bestTranscription.formattedString
                let final = result?.isFinal == true
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    if let text {
                        self.heard = text
                        self.onText?(text)
                    }
                    if final || error != nil { self.stop() }
                }
            }
            // Recognition on Apple's server stops after about a minute: end cleanly before it does.
            limit = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 55_000_000_000)
                guard !Task.isCancelled else { return }
                self?.stop()
            }
        } catch {
            stop()
            state = .failed("Could not start dictation.")
        }
    }

    private static func speechAllowed() async -> Bool {
        switch SFSpeechRecognizer.authorizationStatus() {
        case .authorized: return true
        case .notDetermined:
            return await withCheckedContinuation { continuation in
                SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0 == .authorized) }
            }
        default: return false
        }
    }
}
