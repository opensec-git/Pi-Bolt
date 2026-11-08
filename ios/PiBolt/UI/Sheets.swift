import PiBoltProtocol
import SwiftUI

/// The computer's projects and sessions (the background service, `host.service`): open one, or start a new one.
struct SessionsSheet: View {
    var controller: SessionController
    @Environment(\.dismiss) private var dismiss
    @State private var projects: [RemoteProject] = []
    @State private var sessions: [String: [RemoteSessionInfo]] = [:]
    /// Projects whose sessions are shown; each is fetched when it opens.
    @State private var expanded: Set<String> = []
    @State private var fetching: Set<String> = []
    @State private var error: String?
    @State private var loading = true

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Sessions").font(.system(size: 22, weight: .semibold)).foregroundStyle(Pi.ink1)
                Spacer()
                GlassButton(systemName: "xmark", label: "Close") { dismiss() }
            }
            .padding(.horizontal, Pi.gutter)
            .padding(.top, 12)
            if controller.state.host?.service != true {
                Text("This is the session in the computer's terminal (/remote-control). To open other sessions from the phone, install the background service on the computer: pi-bolt service install.")
                    .font(.system(size: 15)).foregroundStyle(Pi.ink2)
                    .padding(Pi.gutter)
                Spacer()
            } else if loading {
                HStack { QuadSpinner(size: 16, color: Pi.ink2); Text("Loading…").foregroundStyle(Pi.ink3) }
                    .padding(Pi.gutter)
                Spacer()
            } else if let error {
                Text(error).font(.system(size: 15)).foregroundStyle(Pi.danger).padding(Pi.gutter)
                Spacer()
            } else {
                list
            }
        }
        .task { await load() }
    }

    private var list: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if !controller.canSwitchNow {
                    Text("Pi is working: this computer's service runs one session at a time, so switching would stop the run. Stop it first.")
                        .font(.system(size: 14)).foregroundStyle(Pi.warning).padding(.bottom, 12)
                }
                ForEach(projects) { project in
                    HStack {
                        Button {
                            toggle(project)
                        } label: {
                            HStack(spacing: 8) {
                                Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)).foregroundStyle(Pi.ink4)
                                    .rotationEffect(.degrees(expanded.contains(project.cwd) ? 90 : 0))
                                Text(project.name).font(.system(size: 15, weight: .medium)).foregroundStyle(Pi.ink2)
                                Text("\(project.sessions)").font(.system(size: 13)).foregroundStyle(Pi.ink4)
                                if fetching.contains(project.cwd) { QuadSpinner(size: 11, color: Pi.ink3) }
                            }
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        Spacer()
                        Button {
                            Task {
                                if await controller.newSession(cwd: project.cwd) { dismiss() }
                            }
                        } label: {
                            Label("New", systemImage: "plus").font(.system(size: 14, weight: .medium)).foregroundStyle(Pi.ink1)
                        }
                        .disabled(!controller.canSwitchNow)
                    }
                    .padding(.top, 8)
                    ForEach(expanded.contains(project.cwd) ? sessions[project.cwd] ?? [] : []) { session in
                        Button {
                            Task {
                                if await controller.switchSession(session.path) { dismiss() }
                            }
                        } label: {
                            HStack(spacing: 14) {
                                if session.running {
                                    QuadSpinner(size: 14)
                                } else {
                                    Circle().fill(current(session) ? Pi.ink1 : Pi.ink4).frame(width: 7, height: 7).frame(width: 14)
                                }
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(session.title).font(.system(size: 17)).foregroundStyle(Pi.ink1).lineLimit(1)
                                    Text(meta(session)).font(.system(size: 14)).foregroundStyle(Pi.ink3).lineLimit(1)
                                }
                                Spacer()
                            }
                            .padding(.vertical, 12)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(PressScale())
                        .disabled(!controller.canSwitchNow || current(session))
                        Rectangle().fill(Pi.line).frame(height: 1).padding(.leading, 28)
                    }
                }
            }
            .padding(.horizontal, Pi.gutter)
            .padding(.bottom, 32)
        }
    }

    private func current(_ session: RemoteSessionInfo) -> Bool {
        !session.sessionId.isEmpty && session.sessionId == controller.state.sessionId
    }

    private func meta(_ session: RemoteSessionInfo) -> String {
        var parts: [String] = []
        if current(session) { parts.append("Open here") } else if session.running { parts.append("Working") }
        parts.append("\(session.messageCount) messages")
        if let date = isoDate(session.updatedAt) { parts.append(relativeShort(date)) }
        return parts.joined(separator: " · ")
    }

    private func load() async {
        guard controller.state.host?.service == true else { return }
        loading = true
        defer { loading = false }
        switch await controller.listProjects() {
        case let .success(list):
            projects = list
            // The open session's project starts open (the newest one when the computer's folder is not listed).
            let cwd = controller.state.host?.cwd
            if let first = list.first(where: { $0.cwd == cwd }) ?? list.first { toggle(first) }
        case let .failure(failure):
            error = failure.message
        }
    }

    /// Open or close a project; its sessions (all the computer lists) are fetched the first time it opens.
    private func toggle(_ project: RemoteProject) {
        if expanded.contains(project.cwd) {
            expanded.remove(project.cwd)
            return
        }
        expanded.insert(project.cwd)
        guard sessions[project.cwd] == nil, !fetching.contains(project.cwd) else { return }
        fetching.insert(project.cwd)
        Task {
            switch await controller.listSessions(cwd: project.cwd) {
            case let .success(found): sessions[project.cwd] = found
            case let .failure(failure): controller.toast = Toast(text: failure.message, kind: .error)
            }
            fetching.remove(project.cwd)
        }
    }
}

/// The models the computer offers.
struct ModelSheet: View {
    var controller: SessionController
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Model").font(.system(size: 22, weight: .semibold)).foregroundStyle(Pi.ink1)
                Spacer()
                GlassButton(systemName: "xmark", label: "Close") { dismiss() }
            }
            .padding(.horizontal, Pi.gutter)
            .padding(.top, 12)
            if controller.models.isEmpty {
                HStack { QuadSpinner(size: 16, color: Pi.ink2); Text("Loading…").foregroundStyle(Pi.ink3) }.padding(Pi.gutter)
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(controller.models) { model in
                            Button {
                                controller.setModel(model)
                                dismiss()
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(model.label).font(.system(size: 17)).foregroundStyle(Pi.ink1)
                                        Text(model.provider).font(.system(size: 13)).foregroundStyle(Pi.ink3)
                                    }
                                    Spacer()
                                    if controller.state.model?.id == model.id, controller.state.model?.provider == model.provider {
                                        Image(systemName: "checkmark").foregroundStyle(Pi.ink1)
                                    }
                                }
                                .padding(.vertical, 12)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            Rectangle().fill(Pi.line).frame(height: 1)
                        }
                    }
                    .padding(.horizontal, Pi.gutter)
                }
            }
        }
        .task { controller.loadModels() }
    }
}

/// A dialog an extension raised (`extension_ui_request`): select, confirm, input or editor. The same dialog is open in
/// the terminal; whichever side answers first wins.
struct DialogSheet: View {
    var dialog: DialogRequest
    var controller: SessionController
    @State private var value = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(dialog.title.isEmpty ? "Pi asks" : dialog.title)
                    .font(.system(size: 20, weight: .semibold)).foregroundStyle(Pi.ink1)
                if let message = dialog.message, !message.isEmpty {
                    Text(message).font(.system(size: 15)).foregroundStyle(Pi.ink2)
                }
                switch dialog.kind {
                case .confirm:
                    HStack(spacing: 12) {
                        Button("No") { controller.answer(dialog, confirmed: false) }.buttonStyle(SecondaryButtonStyle())
                        Button("Yes") { controller.answer(dialog, confirmed: true) }.buttonStyle(PrimaryButtonStyle())
                    }
                case .select:
                    VStack(spacing: 8) {
                        ForEach(dialog.options, id: \.self) { option in
                            Button(option) { controller.answer(dialog, value: option) }.buttonStyle(SecondaryButtonStyle())
                        }
                        Button("Cancel") { controller.cancel(dialog) }.foregroundStyle(Pi.ink3).padding(.top, 4)
                    }
                case .input, .editor:
                    TextField(dialog.placeholder ?? "", text: $value, axis: .vertical)
                        .lineLimit(dialog.kind == .editor ? 6 ... 14 : 1 ... 4)
                        .font(dialog.kind == .editor ? Pi.mono : .system(size: 16))
                        .foregroundStyle(Pi.ink1)
                        .padding(12)
                        .background(RoundedRectangle(cornerRadius: 12).fill(Pi.surface2))
                        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Pi.lineStrong, lineWidth: 1))
                    HStack(spacing: 12) {
                        Button("Cancel") { controller.cancel(dialog) }.buttonStyle(SecondaryButtonStyle())
                        Button("Submit") { controller.answer(dialog, value: value) }.buttonStyle(PrimaryButtonStyle())
                    }
                }
            }
            .padding(Pi.gutter)
        }
        .onAppear { value = dialog.prefill ?? "" }
    }
}

/// The thinking levels the session's model offers (`get_available_thinking_levels`); the current one is checked.
struct ThinkingSheet: View {
    var controller: SessionController
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Thinking").font(.system(size: 22, weight: .semibold)).foregroundStyle(Pi.ink1)
                Spacer()
                GlassButton(systemName: "xmark", label: "Close") { dismiss() }
            }
            .padding(.horizontal, Pi.gutter)
            .padding(.top, 12)
            Text("How much the model reasons before it answers. More is slower and costs more.")
                .font(.system(size: 14)).foregroundStyle(Pi.ink3)
                .padding(.horizontal, Pi.gutter)
                .padding(.bottom, 8)
            if controller.thinkingLevels.isEmpty {
                HStack { QuadSpinner(size: 16, color: Pi.ink2); Text("Loading…").foregroundStyle(Pi.ink3) }.padding(Pi.gutter)
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(controller.thinkingLevels, id: \.self) { level in
                            Button {
                                controller.setThinkingLevel(level)
                                dismiss()
                            } label: {
                                HStack {
                                    Text(level.prefix(1).uppercased() + level.dropFirst()).font(.system(size: 17)).foregroundStyle(Pi.ink1)
                                    Spacer()
                                    if controller.state.thinkingLevel == level {
                                        Image(systemName: "checkmark").foregroundStyle(Pi.ink1)
                                    }
                                }
                                .padding(.vertical, 12)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            Rectangle().fill(Pi.line).frame(height: 1)
                        }
                    }
                    .padding(.horizontal, Pi.gutter)
                }
            }
        }
        .task { controller.loadThinkingLevels() }
    }
}

/// The session's name on the computer (`set_session_name`); empty clears it.
struct RenameSheet: View {
    var current: String
    var save: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Rename session").font(.system(size: 22, weight: .semibold)).foregroundStyle(Pi.ink1)
            TextField("Name", text: $name)
                .font(.system(size: 17))
                .foregroundStyle(Pi.ink1)
                .focused($focused)
                .submitLabel(.done)
                .onSubmit(commit)
                .padding(14)
                .background(RoundedRectangle(cornerRadius: 14).fill(Pi.surface2))
                .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Pi.lineStrong, lineWidth: 1))
            HStack(spacing: 12) {
                Button("Cancel") { dismiss() }.buttonStyle(SecondaryButtonStyle())
                Button("Save", action: commit).buttonStyle(PrimaryButtonStyle())
            }
            Spacer(minLength: 0)
        }
        .padding(Pi.gutter)
        .onAppear {
            name = current
            focused = true
        }
    }

    private func commit() {
        save(name)
        dismiss()
    }
}
