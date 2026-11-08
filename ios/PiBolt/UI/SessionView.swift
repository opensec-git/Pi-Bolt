import PiBoltProtocol
import SwiftUI

/// A session on the computer: glass back and more buttons, the transcript, the link state, and the pill composer.
struct SessionView: View {
    @Bindable var controller: SessionController
    var close: () -> Void
    @State private var draft = ""
    @State private var showSessions = false
    @State private var showModels = false
    @State private var atBottom = true

    var body: some View {
        VStack(spacing: 0) {
            header
            LinkBanner(controller: controller)
            transcript
            if !controller.state.queue.isEmpty { queueBar }
            Composer(
                text: $draft,
                running: controller.state.isRunning,
                connected: controller.link.isConnected,
                placeholder: placeholder,
                send: { behavior in
                    let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !text.isEmpty else { return }
                    draft = ""
                    controller.send(text, behavior: behavior)
                },
                stop: { controller.abort() },
                menu: { AnyView(moreMenuItems) }
            )
        }
        .background(Pi.canvas.ignoresSafeArea())
        .onChange(of: controller.composerRestore) { _, restored in
            guard let restored else { return }
            draft = draft.isEmpty ? restored : restored + "\n\n" + draft
            controller.composerRestore = nil
        }
        .sheet(isPresented: $showSessions) {
            SessionsSheet(controller: controller)
                .presentationDetents([.large])
                .presentationBackground(Pi.surface1)
        }
        .sheet(isPresented: $showModels) {
            ModelSheet(controller: controller)
                .presentationDetents([.medium, .large])
                .presentationBackground(Pi.surface1)
        }
        .sheet(item: Binding(get: { controller.state.dialogs.first }, set: { _ in })) { dialog in
            DialogSheet(dialog: dialog, controller: controller)
                .presentationDetents([.medium, .large])
                .presentationBackground(Pi.surface1)
                .interactiveDismissDisabled()
        }
        .overlay(alignment: .bottom) {
            if let toast = controller.toast {
                ToastView(toast: toast)
                    .padding(.bottom, 96)
                    .transition(.opacity)
                    .task(id: toast.id) {
                        try? await Task.sleep(nanoseconds: 3_500_000_000)
                        if controller.toast?.id == toast.id { controller.toast = nil }
                    }
            }
        }
    }

    private var placeholder: String {
        if !controller.link.isConnected { return "Waiting for the computer…" }
        if controller.state.isRunning { return "Steer…" }
        return controller.state.items.isEmpty ? "Ask Pi…" : "Follow up…"
    }

    private var header: some View {
        HStack(spacing: 12) {
            GlassButton(systemName: "chevron.left", label: "Back", action: close)
            VStack(spacing: 2) {
                Text(title).font(.system(size: 17, weight: .semibold)).foregroundStyle(Pi.ink1).lineLimit(1)
                Text(subtitle).font(.system(size: 13)).foregroundStyle(Pi.ink3).lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            Menu { moreMenuItems } label: { GlassCircle(systemName: "ellipsis") }
                .frame(minWidth: 48, minHeight: 48)
                .accessibilityLabel("More")
        }
        .padding(.horizontal, 12)
        .padding(.top, 4)
        .padding(.bottom, 6)
    }

    @ViewBuilder private var moreMenuItems: some View {
        Button { showSessions = true } label: { Label("Sessions", systemImage: "list.bullet") }
        Button { showModels = true } label: { Label("Model", systemImage: "cpu") }
        Button { controller.compact() } label: { Label("Compact context", systemImage: "arrow.down.right.and.arrow.up.left") }
            .disabled(!controller.link.isConnected || controller.state.isRunning)
        if !controller.state.queue.isEmpty {
            Button { controller.clearQueue() } label: { Label("Clear queue", systemImage: "tray") }
        }
        Button { controller.retryNow() } label: { Label("Reconnect", systemImage: "arrow.clockwise") }
            .disabled(controller.link.isConnected)
    }

    private var title: String {
        if let name = controller.state.sessionName, !name.isEmpty { return name }
        return controller.hostName
    }

    private var subtitle: String {
        var parts: [String] = []
        if controller.state.sessionName?.isEmpty == false { parts.append(controller.hostName) }
        if let model = controller.state.model { parts.append(model.label) }
        if let cwd = controller.state.host?.cwd, !cwd.isEmpty { parts.append((cwd as NSString).lastPathComponent) }
        return parts.joined(separator: " · ")
    }

    private var transcript: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 16) {
                    if controller.state.hello && controller.state.allItems.isEmpty {
                        emptySession
                    }
                    ForEach(controller.state.items) { item in
                        ChatRow(item: item).id(item.key)
                    }
                    ForEach(controller.state.streaming) { item in
                        ChatRow(item: item).id(item.key)
                    }
                    if controller.state.isRunning || controller.state.isCompacting {
                        WorkingLine(state: controller.state)
                    }
                    Color.clear.frame(height: 1).id("bottom")
                        .onAppear { atBottom = true }
                        .onDisappear { atBottom = false }
                }
                .padding(.horizontal, Pi.gutter)
                .padding(.vertical, 12)
            }
            .scrollDismissesKeyboard(.interactively)
            .defaultScrollAnchor(.bottom)
            .onChange(of: controller.state.allItems.count) { _, _ in
                if atBottom { proxy.scrollTo("bottom", anchor: .bottom) }
            }
            .onChange(of: controller.state.streaming) { _, _ in
                if atBottom { proxy.scrollTo("bottom", anchor: .bottom) }
            }
            .overlay(alignment: .bottom) {
                if !atBottom {
                    Button {
                        withAnimation { proxy.scrollTo("bottom", anchor: .bottom) }
                    } label: {
                        Label("New messages", systemImage: "arrow.down").font(.system(size: 13, weight: .medium))
                            .foregroundStyle(Pi.ink1)
                            .padding(.horizontal, 14).padding(.vertical, 8)
                            .background(Capsule().fill(Pi.surface3))
                            .overlay(Capsule().strokeBorder(Pi.lineStrong, lineWidth: 1))
                    }
                    .padding(.bottom, 8)
                    .transition(.opacity)
                }
            }
        }
    }

    private var emptySession: some View {
        VStack(alignment: .leading, spacing: 10) {
            BrandMark(size: 40)
            Text("New session on \(controller.hostName)").font(.system(size: 22, weight: .semibold)).foregroundStyle(Pi.ink1)
            Text("Ask Pi to read, change or run anything in \(controller.state.host?.cwd ?? "the project").")
                .font(.system(size: 15)).foregroundStyle(Pi.ink3)
        }
        .padding(.top, 40)
    }

    private var queueBar: some View {
        HStack(spacing: 8) {
            Image(systemName: "tray.full").font(.system(size: 12)).foregroundStyle(Pi.warning)
            Text("\(controller.state.queue.count) queued").font(.system(size: 13, weight: .medium)).foregroundStyle(Pi.ink2)
            Text((controller.state.queue.steering + controller.state.queue.followUp).first ?? "")
                .font(.system(size: 13)).foregroundStyle(Pi.ink3).lineLimit(1)
            Spacer()
            Button("Clear") { controller.clearQueue() }.font(.system(size: 13, weight: .medium)).foregroundStyle(Pi.ink1)
        }
        .padding(.horizontal, Pi.gutter)
        .padding(.vertical, 8)
    }
}

/// The live turn: the Quad and what it is doing, with the elapsed time.
private struct WorkingLine: View {
    var state: SessionState
    @State private var started = Date()

    var body: some View {
        HStack(spacing: 10) {
            QuadSpinner(size: 14, color: Pi.ink2)
            TimelineView(.periodic(from: .now, by: 1)) { context in
                Text("\(label) · \(Int(context.date.timeIntervalSince(started)))s")
                    .font(.system(size: 14, weight: .medium)).foregroundStyle(Pi.ink3).monospacedDigit()
            }
        }
        .padding(.top, 2)
    }

    private var label: String {
        if state.isCompacting { return "Compacting" }
        if let retry = state.retry { return "Retrying (\(retry.attempt)/\(retry.maxAttempts))" }
        return "Working"
    }
}

/// Connecting, reconnecting or failed: what happened and a way to retry.
private struct LinkBanner: View {
    var controller: SessionController

    var body: some View {
        switch controller.link {
        case .connected:
            EmptyView()
        case .connecting:
            banner(icon: nil, text: controller.pairing ? "Pairing with \(controller.hostName)…" : "Connecting to \(controller.hostName)…", tint: Pi.ink2, action: nil)
        case let .retrying(_, _, seconds):
            banner(icon: nil, text: seconds == 0 ? "Reconnecting…" : "Can't reach \(controller.hostName). Retrying in \(seconds)s", tint: Pi.warning, action: "Retry")
        case let .failed(_, message):
            banner(icon: "exclamationmark.triangle.fill", text: message, tint: Pi.danger, action: "Retry")
        }
    }

    private func banner(icon: String?, text: String, tint: Color, action: String?) -> some View {
        HStack(alignment: .top, spacing: 10) {
            if let icon {
                Image(systemName: icon).font(.system(size: 13)).foregroundStyle(tint).padding(.top, 2)
            } else {
                QuadSpinner(size: 13, color: tint).padding(.top, 2)
            }
            Text(text).font(.system(size: 14)).foregroundStyle(Pi.ink2).frame(maxWidth: .infinity, alignment: .leading)
            if let action {
                Button(action) { controller.retryNow() }.font(.system(size: 14, weight: .semibold)).foregroundStyle(Pi.ink1)
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Pi.surface1))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Pi.line, lineWidth: 1))
        .padding(.horizontal, Pi.gutter)
        .padding(.bottom, 6)
    }
}
