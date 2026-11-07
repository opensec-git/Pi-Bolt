import PiBoltProtocol
import SwiftUI

/// The pill composer: a + circle (session actions) on the left, the field, and one white circle on the right that sends
/// (an arrow) or, while the agent runs with nothing typed, stops it (a filled square). While it runs, a typed message
/// steers; holding the send button offers "Queue as follow-up".
struct Composer: View {
    @Binding var text: String
    var running: Bool
    var connected: Bool
    var placeholder: String
    var send: (StreamingBehavior) -> Void
    var stop: () -> Void
    var menu: () -> AnyView
    @FocusState private var focused: Bool

    private var hasText: Bool { !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            Menu { menu() } label: {
                ZStack {
                    Circle().fill(Pi.surface3)
                    Image(systemName: "plus").font(.system(size: 16, weight: .medium)).foregroundStyle(Pi.ink1)
                }
                .frame(width: 36, height: 36)
            }
            .frame(width: 44, height: 44)
            .accessibilityLabel("Session actions")

            TextField(placeholder, text: $text, axis: .vertical)
                .font(.system(size: 17))
                .foregroundStyle(Pi.ink1)
                .lineLimit(1 ... 8)
                .focused($focused)
                .padding(.vertical, 11)
                .submitLabel(.return)

            primaryButton
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 4)
        .background(
            RoundedRectangle(cornerRadius: 26, style: .continuous)
                .fill(LinearGradient(colors: [Pi.surface2, Pi.surface1], startPoint: .top, endPoint: .bottom))
        )
        .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).strokeBorder(Pi.lineStrong, lineWidth: 1))
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
        .padding(.top, 4)
    }

    @ViewBuilder private var primaryButton: some View {
        if running && !hasText {
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
            Button { send(.steer) } label: {
                ZStack {
                    Circle().fill(Pi.accent)
                    Image(systemName: "arrow.up").font(.system(size: 15, weight: .bold)).foregroundStyle(Pi.onAccent)
                }
                .frame(width: 34, height: 34)
                .opacity(hasText && connected ? 1 : 0.18)
            }
            .buttonStyle(PressScale())
            .disabled(!hasText || !connected)
            .accessibilityLabel(running ? "Steer" : "Send")
            .contextMenu {
                if running {
                    Button { send(.steer) } label: { Label("Steer now", systemImage: "arrow.turn.down.right") }
                    Button { send(.followUp) } label: { Label("Queue as follow-up", systemImage: "text.append") }
                }
            }
        }
    }
}
