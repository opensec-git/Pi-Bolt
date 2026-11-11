import PhotosUI
import PiBoltProtocol
import SwiftUI

/// The session's sheets, one at a time.
enum SessionSheet: Identifiable, Equatable {
    case sessions, models, thinking, rename, subagents
    case subagent(String)

    var id: String {
        switch self {
        case .sessions: "sessions"
        case .models: "models"
        case .thinking: "thinking"
        case .rename: "rename"
        case .subagents: "subagents"
        case let .subagent(key): "subagent:\(key)"
        }
    }
}

/// A session on the computer: glass back and more buttons, the transcript, the link state, the todo panel and the
/// pill composer.
struct SessionView: View {
    @Bindable var controller: SessionController
    var close: () -> Void
    @State private var draft = ""
    @State private var sheet: SessionSheet?
    @State private var reviewing = false
    @State private var atBottom = true
    @State private var attachments = Attachments()
    @State private var dictation = Dictation()
    @State private var pickingPhotos = false
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var camera = false

    var body: some View {
        VStack(spacing: 0) {
            header
            LinkBanner(controller: controller)
            transcript
                .environment(\.subagents, SubagentContext(runs: controller.state.subagents, open: { sheet = .subagent($0) }))
            if !controller.state.queue.isEmpty { queueBar }
            if !controller.state.todos.visible.isEmpty { TodoPanel(todos: controller.state.todos) }
            Composer(
                text: $draft,
                attachments: attachments,
                dictation: dictation,
                running: controller.state.isRunning,
                connected: controller.link.isConnected,
                placeholder: placeholder,
                suggestions: attachments.images.isEmpty ? CommandPalette.filter(draft, palette) : [],
                hint: CommandPalette.hintFor(draft, palette),
                send: submit,
                stop: { controller.abort() },
                pick: { draft = $0.insert },
                attachMenu: { AnyView(attachMenuItems) }
            )
        }
        .background(Pi.canvas.ignoresSafeArea())
        .onChange(of: draft) { _, text in
            if text.hasPrefix("/") { controller.refreshCommands() }
        }
        .onChange(of: controller.composerRestore) { _, restored in
            guard let restored else { return }
            draft = draft.isEmpty ? restored : restored + "\n\n" + draft
            controller.composerRestore = nil
        }
        .onChange(of: controller.composerRestoreImages) { _, restored in
            guard !restored.isEmpty else { return }
            attachments.restore(restored)
            controller.composerRestoreImages = []
        }
        .onChange(of: attachments.error) { _, error in
            guard let error else { return }
            controller.toast = Toast(text: error, kind: .error)
            attachments.error = nil
        }
        .onChange(of: controller.requestedSheet, initial: true) { _, requested in
            guard let requested else { return }
            sheet = requested
            controller.requestedSheet = nil
        }
        .onChange(of: controller.requestedReview, initial: true) { _, requested in
            guard requested else { return }
            reviewing = true
            controller.requestedReview = false
        }
        .onChange(of: photoItems) { _, items in
            guard !items.isEmpty else { return }
            attachments.load(items)
            photoItems = []
        }
        .photosPicker(isPresented: $pickingPhotos, selection: $photoItems, maxSelectionCount: max(attachments.slotsLeft, 1), matching: .images)
        .fullScreenCover(isPresented: $camera) {
            CameraPicker { attachments.add($0) }.ignoresSafeArea()
        }
        .fullScreenCover(isPresented: $reviewing) {
            ReviewView(controller: controller)
        }
        .sheet(item: $sheet) { which in
            switch which {
            case .sessions:
                SessionsSheet(controller: controller)
                    .presentationDetents([.large])
                    .presentationBackground(Pi.surface1)
            case .models:
                ModelSheet(controller: controller)
                    .presentationDetents([.medium, .large])
                    .presentationBackground(Pi.surface1)
            case .thinking:
                ThinkingSheet(controller: controller)
                    .presentationDetents([.medium])
                    .presentationBackground(Pi.surface1)
            case .rename:
                RenameSheet(current: controller.state.sessionName ?? "") { controller.rename($0) }
                    .presentationDetents([.height(260)])
                    .presentationBackground(Pi.surface1)
            case .subagents:
                SubagentsSheet(controller: controller) { sheet = .subagent($0) }
                    .presentationDetents([.medium, .large])
                    .presentationBackground(Pi.surface1)
            case let .subagent(key):
                SubagentScreen(controller: controller, key: key)
                    .presentationDetents([.large])
                    .presentationBackground(Pi.canvas)
            }
        }
        .sheet(item: Binding(get: { sheet == nil ? controller.state.dialogs.first : nil }, set: { _ in })) { dialog in
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

    private var palette: [PaletteEntry] { CommandPalette.entries(controller.state.commands) }

    /// Send what the composer holds: one of the app's own commands runs here; anything else goes to Pi.
    private func submit(_ behavior: StreamingBehavior) {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        let images = attachments.ready
        guard !text.isEmpty || !images.isEmpty, !attachments.encoding else { return }
        if images.isEmpty, let call = CommandPalette.parse(text, controller.state.commands) {
            draft = ""
            run(call)
            return
        }
        if !images.isEmpty, controller.state.model?.acceptsImages == false {
            controller.toast = Toast(text: "\(controller.state.model?.label ?? "This model") does not read images. Choose another model or remove them.", kind: .warning)
            return
        }
        draft = ""
        attachments.clear()
        controller.send(text, images: images, behavior: behavior)
    }

    private func run(_ call: BuiltinCall) {
        switch call.builtin {
        case .compact:
            controller.compact(instructions: call.args.isEmpty ? nil : call.args)
        case .name:
            if call.args.isEmpty { sheet = .rename } else { controller.rename(call.args) }
        case .model:
            sheet = .models
        case .thinking:
            if call.args.isEmpty { sheet = .thinking } else { controller.setThinkingLevel(call.args.lowercased()) }
        case .copy:
            if let last = controller.state.allItems.last(where: { $0.assistantText != nil })?.assistantText?.text, !last.isEmpty {
                UIPasteboard.general.string = last
                controller.toast = Toast(text: "Copied the last reply")
            } else {
                controller.toast = Toast(text: "No reply to copy yet", kind: .warning)
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

    @ViewBuilder private var attachMenuItems: some View {
        Section {
            Button { pickingPhotos = true } label: { Label("Photo library", systemImage: "photo.on.rectangle") }
                .disabled(attachments.slotsLeft == 0)
            if CameraPicker.available {
                Button { camera = true } label: { Label("Take photo", systemImage: "camera") }
                    .disabled(attachments.slotsLeft == 0)
            }
            Button { attachments.paste() } label: { Label("Paste image", systemImage: "doc.on.clipboard") }
                .disabled(attachments.slotsLeft == 0)
        }
        Section { moreMenuItems }
    }

    @ViewBuilder private var moreMenuItems: some View {
        Button { sheet = .sessions } label: { Label("Sessions", systemImage: "list.bullet") }
        Button { sheet = .rename } label: { Label("Rename", systemImage: "pencil") }
            .disabled(!controller.link.isConnected)
        Button { sheet = .models } label: { Label("Model", systemImage: "cpu") }
        if controller.state.model?.reasoning != false {
            Button { sheet = .thinking } label: { Label("Thinking: \(controller.state.thinkingLevel ?? "default")", systemImage: "brain") }
                .disabled(!controller.link.isConnected)
        }
        if controller.state.host?.review == true {
            Button { reviewing = true } label: { Label("Review changes and files", systemImage: "doc.text.magnifyingglass") }
                .disabled(!controller.link.isConnected)
        }
        if !controller.state.subagents.isEmpty {
            Button { sheet = .subagents } label: { Label("Subagents (\(controller.state.subagents.count))", systemImage: "person.2") }
        }
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
