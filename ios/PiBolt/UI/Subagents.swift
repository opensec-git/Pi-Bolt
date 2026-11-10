import PiBoltProtocol
import SwiftUI

/// Words for subagent runs, as the Android app's `SubagentFormat` (ui/session/subagent).
enum SubagentFormat {
    /// "Explore", "General-purpose": types read capitalised.
    static func typeLabel(_ type: String) -> String {
        let t = type.trimmingCharacters(in: .whitespaces).isEmpty ? "Agent" : type
        return t.prefix(1).uppercased() + t.dropFirst()
    }

    /// The task title the model gave the call, else its prompt's first line (cut), else the type.
    static func title(_ run: Subagent) -> String {
        if !run.description.isEmpty { return run.description }
        if let line = firstLine(run.prompt) { return line.count > 60 ? String(line.prefix(59)).trimmingCharacters(in: .whitespaces) + "…" : line }
        return typeLabel(run.type)
    }

    static func firstLine(_ text: String?) -> String? {
        text?.split(separator: "\n").lazy.map { $0.trimmingCharacters(in: .whitespaces) }.first { !$0.isEmpty }
    }

    /// The card's live line: what it does; that it waits; the first line of the answer; why it failed or stopped.
    static func activityLine(_ run: Subagent) -> String {
        let activity = run.activity.map { $0.replacingOccurrences(of: "`", with: "").replacingOccurrences(of: "**", with: "") }
        switch run.status {
        case .queued:
            if let activity, activity.hasPrefix("queued") { return capitalised(activity) }
            return run.background ? "Queued in the background" : "Starting…"
        case .running:
            return activity.map(capitalised) ?? (run.background ? "Working in the background" : "Starting…")
        case .done:
            return firstLine(run.result) ?? "Done"
        case .failed:
            return firstLine(run.error).map { "Failed: \($0)" } ?? firstLine(run.result) ?? "Failed"
        case .stopped:
            return firstLine(run.error) ?? "Stopped"
        }
    }

    /// "4 tools · 12.3k tokens · turn 2/10"; "No tools yet" before the first.
    static func metrics(_ run: Subagent) -> String {
        var parts: [String] = []
        switch run.toolUses {
        case 0: parts.append(run.isActive ? "No tools yet" : "No tools")
        case 1: parts.append("1 tool")
        default: parts.append("\(run.toolUses) tools")
        }
        if let tokens = run.tokens { parts.append("\(tokens) tokens") }
        if !run.isActive, run.turns > 0 {
            parts.append(run.turns == 1 ? "1 turn" : "\(run.turns) turns")
        } else if run.turns > 1 || (run.turns > 0 && run.maxTurns != nil) {
            parts.append(run.maxTurns.map { "turn \(run.turns)/\($0)" } ?? "turn \(run.turns)")
        }
        if !run.isActive, let ms = run.durationMs, ms > 0 { parts.append(duration(ms)) }
        return parts.joined(separator: " · ")
    }

    /// "9.1s", "1m 4s".
    static func duration(_ ms: Int64) -> String {
        if ms < 60000 { return String(format: "%.1fs", Double(ms) / 1000) }
        let seconds = ms / 1000
        if seconds < 3600 { return "\(seconds / 60)m \(seconds % 60)s" }
        return "\(seconds / 3600)h \(seconds % 3600 / 60)m"
    }

    static func statusWord(_ status: SubagentStatus) -> String {
        switch status {
        case .queued: "Queued"
        case .running: "Running"
        case .done: "Finished"
        case .failed: "Failed"
        case .stopped: "Stopped"
        }
    }

    private static func capitalised(_ s: String) -> String {
        let t = s.trimmingCharacters(in: .whitespaces)
        return t.prefix(1).uppercased() + t.dropFirst()
    }
}

/// What a transcript row needs to show a subagent: the runs, and how to open one.
struct SubagentContext {
    var runs = SubagentRuns()
    var open: (String) -> Void = { _ in }
}

private struct SubagentContextKey: EnvironmentKey {
    static let defaultValue = SubagentContext()
}

extension EnvironmentValues {
    var subagents: SubagentContext {
        get { self[SubagentContextKey.self] }
        set { self[SubagentContextKey.self] = newValue }
    }
}

struct SubagentGlyph: View {
    var status: SubagentStatus
    var size: CGFloat = 14

    var body: some View {
        switch status {
        case .queued: Image(systemName: "clock").font(.system(size: size - 1, weight: .medium)).foregroundStyle(Pi.ink3)
        case .running: QuadSpinner(size: size, color: Pi.ink2)
        case .done: Image(systemName: "checkmark").font(.system(size: size - 2, weight: .semibold)).foregroundStyle(Pi.success)
        case .failed: Image(systemName: "xmark").font(.system(size: size - 2, weight: .bold)).foregroundStyle(Pi.danger)
        case .stopped: Image(systemName: "minus").font(.system(size: size - 2, weight: .bold)).foregroundStyle(Pi.ink4)
        }
    }
}

/// An `Agent` call in the transcript: the run's title, its type and what it does now (or how it ended), and its
/// metrics. Tap for the run's screen.
struct SubagentCard: View {
    var run: Subagent
    var open: () -> Void

    var body: some View {
        Button(action: open) {
            HStack(alignment: .top, spacing: 12) {
                SubagentGlyph(status: run.status).frame(width: 18).padding(.top, 3)
                VStack(alignment: .leading, spacing: 4) {
                    Text(SubagentFormat.title(run)).font(.system(size: 15, weight: .medium)).foregroundStyle(Pi.ink1).lineLimit(2)
                    Text(meta).font(.system(size: 13)).foregroundStyle(Pi.ink3).lineLimit(1)
                    Text(SubagentFormat.activityLine(run)).font(.system(size: 14)).foregroundStyle(run.status == .failed ? Pi.danger : Pi.ink2).lineLimit(2)
                }
                Spacer(minLength: 4)
                Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)).foregroundStyle(Pi.ink4).padding(.top, 4)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Pi.surface1))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Pi.line, lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScale())
        .accessibilityLabel("\(SubagentFormat.title(run)), \(SubagentFormat.typeLabel(run.type)) agent, \(SubagentFormat.statusWord(run.status))")
    }

    private var meta: String {
        var parts = [SubagentFormat.typeLabel(run.type)]
        if run.background { parts.append("background") }
        parts.append(SubagentFormat.metrics(run))
        return parts.joined(separator: " · ")
    }
}

/// Background agents ended (`subagent-notification`): which, with a way to open each.
struct SubagentNoticeRow: View {
    var item: SubagentNoticeItem
    @Environment(\.subagents) private var subagents

    var body: some View {
        let runs = item.runKeys.compactMap { subagents.runs[$0] }
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Image(systemName: "person.2.fill").font(.system(size: 12)).foregroundStyle(Pi.ink3)
                Text(runs.count > 1 ? "\(runs.count) background agents ended" : "A background agent ended")
                    .font(.system(size: 14, weight: .medium)).foregroundStyle(Pi.ink3)
            }
            ForEach(runs) { run in
                Button { subagents.open(run.key) } label: {
                    HStack(spacing: 8) {
                        SubagentGlyph(status: run.status, size: 12)
                        Text(SubagentFormat.title(run)).font(.system(size: 14)).foregroundStyle(Pi.ink2).lineLimit(1)
                        Image(systemName: "chevron.right").font(.system(size: 10, weight: .semibold)).foregroundStyle(Pi.ink4)
                    }
                    .frame(minHeight: 32)
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// Every run of the session, newest first.
struct SubagentsSheet: View {
    var controller: SessionController
    var open: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Subagents").font(.system(size: 22, weight: .semibold)).foregroundStyle(Pi.ink1)
                Spacer()
                GlassButton(systemName: "xmark", label: "Close") { dismiss() }
            }
            .padding(.horizontal, Pi.gutter)
            .padding(.top, 12)
            let runs = controller.state.subagents.values.reversed()
            if runs.isEmpty {
                Text("No subagents in this session. They appear when Pi starts one with the subagents package.")
                    .font(.system(size: 15)).foregroundStyle(Pi.ink3).padding(Pi.gutter)
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(spacing: 10) {
                        ForEach(Array(runs)) { run in
                            SubagentCard(run: run) { open(run.key) }
                        }
                    }
                    .padding(.horizontal, Pi.gutter)
                    .padding(.vertical, 12)
                }
            }
        }
    }
}

/// One run: its task, what it does now, its own conversation as the computer streams it (Pi-Bolt 1.1.5 and later),
/// and its answer. The stream is asked for while this screen is open.
struct SubagentScreen: View {
    var controller: SessionController
    var key: String
    @Environment(\.dismiss) private var dismiss
    @State private var promptOpen = false

    var body: some View {
        let run = controller.state.subagents[key] ?? Subagent(key: key)
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(SubagentFormat.title(run)).font(.system(size: 18, weight: .semibold)).foregroundStyle(Pi.ink1).lineLimit(2)
                    Text([SubagentFormat.typeLabel(run.type), run.model, run.agentId.map { String($0.prefix(8)) }].compactMap { $0 }.joined(separator: " · "))
                        .font(.system(size: 13)).foregroundStyle(Pi.ink3).lineLimit(1)
                }
                Spacer()
                GlassButton(systemName: "xmark", label: "Close") { dismiss() }
            }
            .padding(.horizontal, Pi.gutter)
            .padding(.top, 12)
            .padding(.bottom, 8)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 16) {
                        status(run)
                        if let note = note(run) {
                            Text(note).font(.system(size: 14)).foregroundStyle(Pi.ink3)
                        }
                        if !run.prompt.isEmpty, run.transcript?.items.isEmpty != false {
                            prompt(run.prompt)
                        }
                        ForEach(run.transcript?.allItems ?? []) { item in
                            ChatRow(item: item).id(item.key)
                        }
                        if let result = run.result, run.transcript?.items.contains(where: { $0.assistantText != nil }) != true, !run.isActive {
                            MarkdownView(text: result)
                        }
                        Color.clear.frame(height: 1).id("bottom")
                    }
                    .padding(.horizontal, Pi.gutter)
                    .padding(.bottom, 24)
                }
                .onChange(of: run.transcript?.allItems.count) { _, _ in proxy.scrollTo("bottom", anchor: .bottom) }
            }
        }
        .background(Pi.canvas.ignoresSafeArea())
        .onAppear { controller.watchSubagent(key, true) }
        .onDisappear { controller.watchSubagent(key, false) }
    }

    private func status(_ run: Subagent) -> some View {
        HStack(alignment: .top, spacing: 10) {
            SubagentGlyph(status: run.status).padding(.top, 2)
            VStack(alignment: .leading, spacing: 3) {
                Text(run.isActive ? SubagentFormat.activityLine(run) : SubagentFormat.statusWord(run.status))
                    .font(.system(size: 15, weight: .medium)).foregroundStyle(run.status == .failed ? Pi.danger : Pi.ink2)
                Text(SubagentFormat.metrics(run)).font(.system(size: 13)).foregroundStyle(Pi.ink3)
                if let error = run.error, run.status == .failed || run.status == .stopped {
                    Text(error).font(.system(size: 13)).foregroundStyle(Pi.ink3)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func prompt(_ text: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Button {
                withAnimation(.easeOut(duration: 0.18)) { promptOpen.toggle() }
            } label: {
                HStack(spacing: 6) {
                    Text("Task").font(.system(size: 13, weight: .medium)).foregroundStyle(Pi.ink3)
                    Image(systemName: "chevron.right").font(.system(size: 10, weight: .semibold)).foregroundStyle(Pi.ink4)
                        .rotationEffect(.degrees(promptOpen ? 90 : 0))
                }
                .frame(minHeight: 28)
            }
            .buttonStyle(.plain)
            Text(text).font(.system(size: 15)).foregroundStyle(Pi.ink2).lineLimit(promptOpen ? nil : 4).textSelection(.enabled)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Pi.surface1))
    }

    private func note(_ run: Subagent) -> String? {
        if controller.state.host?.streamsSubagents != true {
            let version = controller.state.host?.version ?? ""
            return "This agent's own conversation shows here with Pi-Bolt 1.1.5 or later on the computer\(version.isEmpty ? "" : " (it runs \(version))"). Until then: its task, progress and answer."
        }
        if run.transcript?.allItems.isEmpty != false {
            return run.isActive ? "Loading its messages…" : (run.transcript == nil ? "Loading its messages…" : "Its messages were not saved. Showing the task and answer.")
        }
        return nil
    }
}
