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
        /// Asking for permissions and starting the microphone.
        case starting
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
    @ObservationIgnored private var startup: Task<Void, Never>?
    @ObservationIgnored private var tapInstalled = false
    /// Counts dictations: a recognition callback of a stopped one (queued, or its final result) is ignored.
    @ObservationIgnored private var generation = 0

    /// Starting or listening: the microphone button stops it.
    var isListening: Bool { state == .listening || state == .starting }

    /// Starts listening; `onText` gets the whole transcription each time it grows.
    func start(onText: @escaping (String) -> Void) {
        guard state != .listening, state != .starting else { return }
        generation += 1
        self.onText = onText
        heard = ""
        state = .starting
        startup = Task { await begin() }
    }

    func stop() {
        // A start still waiting for permissions does not go on to record.
        startup?.cancel()
        startup = nil
        generation += 1
        onText = nil
        if state == .starting { state = .idle }
        guard state == .listening || task != nil || tapInstalled else { return }
        limit?.cancel()
        limit = nil
        if engine.isRunning { engine.stop() }
        // Also after a failed start: a second tap on the same bus would trap.
        if tapInstalled {
            engine.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
        request?.endAudio()
        task?.finish()
        task = nil
        request = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        if state == .listening { state = .idle }
    }

    private func begin() async {
        let speech = await Self.speechAllowed()
        guard !Task.isCancelled, state == .starting else { return }
        guard speech else {
            state = .failed("Speech recognition is off for Pi-Bolt. Turn it on in Settings, Privacy & Security, Speech Recognition.")
            return
        }
        let microphone = await AVAudioApplication.requestRecordPermission()
        guard !Task.isCancelled, state == .starting else { return }
        guard microphone else {
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
            // No input (a Simulator without a microphone): a tap with this format would trap.
            guard format.sampleRate > 0, format.channelCount > 0 else {
                self.request = nil
                try? session.setActive(false, options: .notifyOthersOnDeactivation)
                state = .failed("No microphone is available.")
                return
            }
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak request] buffer, _ in
                request?.append(buffer)
            }
            tapInstalled = true
            engine.prepare()
            try engine.start()
            state = .listening
            let current = generation
            task = recognizer.recognitionTask(with: request) { [weak self] result, error in
                let text = result?.bestTranscription.formattedString
                let final = result?.isFinal == true
                Task { @MainActor [weak self] in
                    guard let self, self.generation == current else { return }
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
                guard !Task.isCancelled, let self, self.generation == current else { return }
                self.stop()
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
