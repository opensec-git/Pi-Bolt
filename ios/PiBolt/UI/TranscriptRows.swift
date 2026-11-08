import PiBoltProtocol
import SwiftUI

/// One transcript row.
struct ChatRow: View {
    var item: ChatItem
    @Environment(\.subagents) private var subagents

    var body: some View {
        switch item {
        case let .user(user): UserBubble(item: user)
        case let .assistant(text): MarkdownView(text: text.text)
        case let .thinking(thinking): ThinkingRow(item: thinking)
        case let .tool(tool):
            // An `Agent` call is a subagent run: its card, once the run is known (the main transcript; a subagent's own
            // transcript has no runs of its own here).
            if SubagentReducer.isAgentCall(tool.name), let run = subagents.runs[tool.toolCallId] {
                SubagentCard(run: run) { subagents.open(run.key) }
            } else {
                ToolRow(item: tool)
            }
        case let .notice(notice): NoticeRow(item: notice)
        case let .subagentNotice(notice): SubagentNoticeRow(item: notice)
        }
    }
}

/// The user's message: a soft rounded bubble on the right.
private struct UserBubble: View {
    var item: UserItem

    var body: some View {
        HStack {
            Spacer(minLength: 48)
            VStack(alignment: .leading, spacing: 6) {
                if item.imageCount > 0 {
                    Label("\(item.imageCount) image\(item.imageCount > 1 ? "s" : "")", systemImage: "photo")
                        .font(.system(size: 13)).foregroundStyle(Pi.ink3)
                }
                Text(item.text).font(.system(size: 16)).foregroundStyle(Pi.ink1).lineSpacing(3).textSelection(.enabled)
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 13)
            .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Pi.surface2))
            .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(Pi.lineStrong, lineWidth: 1))
        }
    }
}

/// Reasoning: its own collapsible line. "Thinking" while it streams, then "Thought"; tap to read it.
private struct ThinkingRow: View {
    var item: ThinkingItem
    @State private var open = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                withAnimation(.easeOut(duration: 0.18)) { open.toggle() }
            } label: {
                HStack(spacing: 8) {
                    if item.streaming {
                        QuadSpinner(size: 12, color: Pi.ink3)
                    } else {
                        Image(systemName: "sparkle").font(.system(size: 12)).foregroundStyle(Pi.ink3)
                    }
                    Text(item.redacted ? "Thinking (hidden)" : item.streaming ? "Thinking" : "Thought")
                        .font(.system(size: 14, weight: .medium)).foregroundStyle(Pi.ink3)
                    Image(systemName: "chevron.right").font(.system(size: 10, weight: .semibold)).foregroundStyle(Pi.ink4)
                        .rotationEffect(.degrees(open ? 90 : 0))
                }
                .frame(minHeight: 32)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(item.redacted)
            if open || (item.streaming && !item.text.isEmpty) {
                Text(item.text).font(.system(size: 14)).foregroundStyle(Pi.ink3).lineSpacing(2)
                    .lineLimit(open ? nil : 3)
                    .padding(.leading, 20)
            }
        }
    }
}

/// A tool call: one collapsed line with a status glyph, a verb and what it works on; tap for arguments and output.
struct ToolRow: View {
    var item: ToolItem
    @State private var open = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                withAnimation(.easeOut(duration: 0.18)) { open.toggle() }
            } label: {
                HStack(spacing: 10) {
                    glyph.frame(width: 16)
                    Text(verb).font(.system(size: 15, weight: .medium)).foregroundStyle(item.isError ? Pi.danger : Pi.ink2)
                    Text(subject).font(Pi.mono).foregroundStyle(Pi.ink3).lineLimit(1).truncationMode(.middle)
                    Spacer(minLength: 4)
                    if let ms = item.durationMs, !item.isActive {
                        Text(duration(ms)).font(.system(size: 12)).foregroundStyle(Pi.ink4).monospacedDigit()
                    }
                    Image(systemName: "chevron.right").font(.system(size: 10, weight: .semibold)).foregroundStyle(Pi.ink4)
                        .rotationEffect(.degrees(open ? 90 : 0))
                }
                .frame(minHeight: 36)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(verb) \(subject)")
            if open {
                VStack(alignment: .leading, spacing: 8) {
                    if let args = item.args {
                        Text(args.displayText).font(Pi.monoSmall).foregroundStyle(Pi.ink2)
                    } else if !item.argsText.isEmpty {
                        Text(item.argsText).font(Pi.monoSmall).foregroundStyle(Pi.ink2)
                    }
                    let output = item.result ?? item.output
                    if !output.isEmpty {
                        Rectangle().fill(Pi.line).frame(height: 1)
                        Text(String(output.suffix(6000))).font(Pi.monoSmall).foregroundStyle(item.isError ? Pi.danger : Pi.ink1)
                            .textSelection(.enabled)
                    }
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Pi.surface1))
                .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Pi.line, lineWidth: 1))
            } else if item.status == .running, !item.output.isEmpty {
                Text(item.output.split(separator: "\n").last.map(String.init) ?? "")
                    .font(Pi.monoSmall).foregroundStyle(Pi.ink4).lineLimit(1).padding(.leading, 26)
            }
        }
    }

    @ViewBuilder private var glyph: some View {
        switch item.status {
        case .streaming, .pending, .running: QuadSpinner(size: 13, color: Pi.ink2)
        case .done: Image(systemName: "checkmark").font(.system(size: 12, weight: .semibold)).foregroundStyle(Pi.ink3)
        case .error: Image(systemName: "xmark").font(.system(size: 12, weight: .bold)).foregroundStyle(Pi.danger)
        case .cancelled: Image(systemName: "minus").font(.system(size: 12, weight: .bold)).foregroundStyle(Pi.ink4)
        }
    }

    private var verb: String {
        let name = item.name
        switch item.status {
        case .streaming, .pending: return "Preparing \(name)"
        case .running: return "Running \(name)"
        case .done: return name
        case .error: return "Failed \(name)"
        case .cancelled: return "Stopped \(name)"
        }
    }

    /// What the call works on: a command, a path or a pattern.
    private var subject: String {
        guard let args = item.args else { return "" }
        for key in ["command", "path", "file_path", "pattern", "url", "query", "description", "prompt"] {
            if let value = args.str(key) { return value.replacingOccurrences(of: "\n", with: " ") }
        }
        // Another tool: its first string argument, by name.
        let first = args.objectValue?.keys.sorted().lazy.compactMap { args.str($0) }.first
        return first?.replacingOccurrences(of: "\n", with: " ") ?? ""
    }

    private func duration(_ ms: Int64) -> String {
        ms < 1000 ? "\(ms)ms" : String(format: "%.1fs", Double(ms) / 1000)
    }
}

private struct NoticeRow: View {
    var item: NoticeItem
    @State private var open = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Image(systemName: icon).font(.system(size: 12, weight: .semibold)).foregroundStyle(color)
                Text(item.title).font(.system(size: 14, weight: .medium)).foregroundStyle(color)
            }
            if !item.text.isEmpty {
                Text(item.text).font(.system(size: 14)).foregroundStyle(Pi.ink3).lineLimit(open ? nil : 4)
                    .onTapGesture { open.toggle() }
            }
        }
        .padding(.vertical, 2)
    }

    private var icon: String {
        switch item.kind {
        case .error: "exclamationmark.triangle.fill"
        case .aborted: "stop.fill"
        case .compaction: "arrow.down.right.and.arrow.up.left"
        case .summary: "text.alignleft"
        case .info, .custom: "info.circle"
        }
    }

    private var color: Color {
        switch item.kind {
        case .error: Pi.danger
        case .aborted: Pi.warning
        default: Pi.ink3
        }
    }
}
