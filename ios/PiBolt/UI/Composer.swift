import PiBoltProtocol
import SwiftUI

/// The pill composer: a + circle (attach images, session actions) on the left, the field, a microphone while it is
/// empty, and one circle on the right that sends (an arrow) or, while the agent runs with nothing typed, stops it (a
/// filled square). While it runs, a typed message steers; holding the send button offers "Queue as follow-up". A `/`
/// at the start lists the commands (`suggestions`); while a command's arguments are typed, its usage (`hint`).
struct Composer: View {
    @Binding var text: String
    var attachments: Attachments
    var dictation: Dictation
    var running: Bool
    var connected: Bool
    var placeholder: String
    var suggestions: [PaletteEntry]
    var hint: PaletteEntry?
    var send: (StreamingBehavior) -> Void
    var stop: () -> Void
    var pick: (PaletteEntry) -> Void
    var attachMenu: () -> AnyView
    @FocusState private var focused: Bool
    @State private var dictationBase = ""

    private var hasText: Bool { !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var hasContent: Bool { hasText || !attachments.images.isEmpty }
    private var canSend: Bool { hasContent && connected && !attachments.encoding }

    var body: some View {
        VStack(spacing: 6) {
            if !suggestions.isEmpty {
                SuggestionList(entries: suggestions, pick: pick)
            } else if let hint, let usage = hint.hint {
                HStack(spacing: 6) {
                    Text(hint.label).font(Pi.monoSmall).foregroundStyle(Pi.ink2)
                    Text(usage).font(Pi.monoSmall).foregroundStyle(Pi.ink3)
                    Spacer()
                }
                .padding(.horizontal, 26)
            }
            if case let .failed(message) = dictation.state {
                Text(message).font(.system(size: 13)).foregroundStyle(Pi.danger).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 26)
            }
            VStack(spacing: 0) {
                if !attachments.images.isEmpty { AttachmentStrip(attachments: attachments) }
                HStack(alignment: .bottom, spacing: 8) {
                    Menu { attachMenu() } label: {
                        ZStack {
                            Circle().fill(Pi.surface3)
                            Image(systemName: "plus").font(.system(size: 16, weight: .medium)).foregroundStyle(Pi.ink1)
                        }
                        .frame(width: 36, height: 36)
                    }
                    .frame(width: 44, height: 44)
                    .accessibilityLabel("Attach and session actions")

                    TextField(dictation.isListening ? "Listening…" : placeholder, text: $text, axis: .vertical)
                        .font(.system(size: 17))
                        .foregroundStyle(Pi.ink1)
                        .lineLimit(1 ... 8)
                        .focused($focused)
                        .padding(.vertical, 11)
                        .submitLabel(.return)

                    if dictation.isListening || (!hasText && !running) {
                        micButton.frame(width: 44, height: 44)
                    }
                    primaryButton
                        .frame(width: 44, height: 44)
                }
                .padding(.horizontal, 6)
                .padding(.vertical, 4)
            }
            .background(
                RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .fill(LinearGradient(colors: [Pi.surface2, Pi.surface1], startPoint: .top, endPoint: .bottom))
            )
            .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).strokeBorder(Pi.lineStrong, lineWidth: 1))
            .padding(.horizontal, 12)
        }
        .padding(.bottom, 8)
        .padding(.top, 4)
        .onDisappear { dictation.stop() }
    }

    private var micButton: some View {
        Button {
            if dictation.isListening {
                dictation.stop()
            } else {
                dictationBase = text.trimmingCharacters(in: .whitespacesAndNewlines)
                dictation.start { heard in
                    text = dictationBase.isEmpty ? heard : dictationBase + " " + heard
                }
            }
        } label: {
            ZStack {
                if dictation.isListening {
                    Circle().fill(Pi.danger.opacity(0.18))
                    Image(systemName: "stop.fill").font(.system(size: 12, weight: .bold)).foregroundStyle(Pi.danger)
                } else {
                    Image(systemName: "mic").font(.system(size: 17, weight: .medium)).foregroundStyle(Pi.ink2)
                }
            }
            .frame(width: 34, height: 34)
        }
        .buttonStyle(PressScale())
        .accessibilityLabel(dictation.isListening ? "Stop dictation" : "Dictate")
    }

    /// Every way of sending ends a dictation first, so its late words never land in the cleared composer.
    private func submit(_ behavior: StreamingBehavior) {
        dictation.stop()
        send(behavior)
    }

    @ViewBuilder private var primaryButton: some View {
        if running && !hasContent {
            Button(action: stop) {
                ZStack {
                    Circle().fill(Pi.surface3)
                    Circle().strokeBorder(Pi.lineStrong, lineWidth: 1)
                    RoundedRectangle(cornerRadius: 3).fill(Pi.ink1).frame(width: 12, height: 12)
                }
                .frame(width: 34, height: 34)
            }
            .buttonStyle(PressScale())
            .accessibilityLabel("Stop")
            .disabled(!connected)
        } else {
            Button { submit(.steer) } label: {
                ZStack {
                    Circle().fill(Pi.accent)
                    Image(systemName: "arrow.up").font(.system(size: 15, weight: .bold)).foregroundStyle(Pi.onAccent)
                }
                .frame(width: 34, height: 34)
                .opacity(canSend ? 1 : 0.18)
            }
            .buttonStyle(PressScale())
            .disabled(!canSend)
            .accessibilityLabel(running ? "Steer" : "Send")
            .contextMenu {
                if running {
                    Button { submit(.steer) } label: { Label("Steer now", systemImage: "arrow.turn.down.right") }
                    Button { submit(.followUp) } label: { Label("Queue as follow-up", systemImage: "text.append") }
                }
            }
        }
    }
}

/// The `/` list: each command's name, what it does and where it comes from. Tap to put it in the composer.
private struct SuggestionList: View {
    var entries: [PaletteEntry]
    var pick: (PaletteEntry) -> Void

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(entries) { entry in
                    Button { pick(entry) } label: {
                        HStack(alignment: .firstTextBaseline, spacing: 10) {
                            Text(entry.label).font(Pi.mono).foregroundStyle(Pi.ink1).lineLimit(1).layoutPriority(1)
                            Text(entry.description).font(.system(size: 14)).foregroundStyle(Pi.ink3).lineLimit(1)
                            Spacer(minLength: 4)
                            if entry.source != .app {
                                Text(entry.source.rawValue).font(.system(size: 11, weight: .medium)).foregroundStyle(Pi.ink4)
                            }
                        }
                        .padding(.horizontal, 14)
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(entry.label), \(entry.description)")
                }
            }
            .padding(.vertical, 4)
        }
        // About five rows; fewer take only their own height.
        .frame(height: min(CGFloat(entries.count) * 44 + 8, 264))
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Pi.surface1))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Pi.lineStrong, lineWidth: 1))
        .padding(.horizontal, 12)
    }
}
