import PiBoltProtocol
import SwiftUI

/// The working tree as git sees it (`rc_git_status`): the branch, then staged, unstaged and conflicted paths with their
/// line counts. A path opens its diff.
struct ChangesTab: View {
    var controller: SessionController

    var body: some View {
        ReviewLoader(key: "status") {
            try RemoteGitStatus.from(await reviewRequest(controller, .status()))
        } content: { status, reload in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if !status.repo {
                        ReviewNote(systemName: "folder", text: "This folder is not in a git repository. Its files are under Files.")
                            .padding(.top, 8)
                    } else {
                        if let branch = status.branch { BranchLine(branch: branch, total: status.total).padding(.top, 4) }
                        let conflicts = status.entries.filter(\.conflicted)
                        let staged = status.entries.filter { !$0.conflicted && $0.index != nil && $0.index != "?" }
                        let unstaged = status.entries.filter { !$0.conflicted && $0.worktree != nil }
                        if status.entries.isEmpty {
                            ReviewNote(systemName: "checkmark", text: "No changes. The working tree matches the last commit.").padding(.top, 20)
                        }
                        section("Conflicts", conflicts, staged: false)
                        section("Staged", staged, staged: true)
                        section("Not staged", unstaged, staged: false)
                        if status.truncated {
                            ReviewNote(text: "The computer listed only the first changes.").padding(.top, 16)
                        }
                    }
                }
                .padding(.horizontal, Pi.gutter)
                .padding(.bottom, 40)
            }
            .refreshable { reload() }
        }
    }

    @ViewBuilder private func section(_ title: String, _ entries: [RemoteStatusEntry], staged: Bool) -> some View {
        if !entries.isEmpty {
            ReviewSectionTitle(text: title, count: entries.count)
            ForEach(entries) { entry in
                NavigationLink(value: ReviewRoute.diff(entry, staged: staged)) {
                    StatusRow(entry: entry, staged: staged)
                }
                .buttonStyle(PressScale())
                if entry.id != entries.last?.id { ReviewDivider(inset: 40) }
            }
        }
    }
}

/// "main · origin/main · 2 ahead, 1 behind", and the change's total.
private struct BranchLine: View {
    var branch: RemoteBranch
    var total: RemoteLineCount

    var body: some View {
        Card {
            HStack(spacing: 10) {
                Image(systemName: "arrow.triangle.branch").font(.system(size: 14, weight: .semibold)).foregroundStyle(Pi.ink2)
                VStack(alignment: .leading, spacing: 2) {
                    Text(branch.head ?? "Detached at \(branch.oid.map { String($0.prefix(7)) } ?? "no commit")")
                        .font(.system(size: 16, weight: .medium)).foregroundStyle(Pi.ink1).lineLimit(1).truncationMode(.middle)
                    if let detail { Text(detail).font(.system(size: 13)).foregroundStyle(Pi.ink3).lineLimit(1) }
                }
                Spacer(minLength: 8)
                LineCounts(count: total)
            }
            .padding(14)
        }
        .accessibilityElement(children: .combine)
    }

    private var detail: String? {
        var parts: [String] = []
        if let upstream = branch.upstream { parts.append(upstream) }
        if branch.ahead > 0 { parts.append("\(branch.ahead) ahead") }
        if branch.behind > 0 { parts.append("\(branch.behind) behind") }
        if branch.upstream != nil, branch.ahead == 0, branch.behind == 0 { parts.append("up to date") }
        if branch.oid == nil { parts.append("no commits yet") }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}

private struct StatusRow: View {
    var entry: RemoteStatusEntry
    var staged: Bool

    var body: some View {
        HStack(spacing: 12) {
            StatusLetter(letter: letter, conflicted: entry.conflicted)
            PathLabel(path: entry.path, origPath: staged ? entry.origPath : nil)
            Spacer(minLength: 8)
            LineCounts(count: staged ? entry.staged : entry.unstaged)
            Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold)).foregroundStyle(Pi.ink4)
        }
        .padding(.vertical, 11)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(kindLabel) \(entry.path)")
    }

    private var letter: Character {
        if entry.conflicted { return "U" }
        return (staged ? entry.index : entry.worktree) ?? "M"
    }

    private var kindLabel: String {
        switch letter {
        case "A": "Added"
        case "D": "Deleted"
        case "R": "Renamed"
        case "C": "Copied"
        case "T": "Type changed"
        case "?": "Untracked"
        case "U": "Conflict"
        default: "Modified"
        }
    }
}

/// The porcelain letter in a small rounded square: green for new files, red for deleted ones, amber for conflicts.
struct StatusLetter: View {
    var letter: Character
    var conflicted = false

    var body: some View {
        Text(String(letter))
            .font(.system(size: 12, weight: .bold, design: .monospaced))
            .foregroundStyle(tint)
            .frame(width: 26, height: 22)
            .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(tint.opacity(0.14)))
            .accessibilityHidden(true)
    }

    private var tint: Color {
        if conflicted { return Pi.warning }
        switch letter {
        case "A", "?": return Pi.success
        case "D": return Pi.danger
        default: return Pi.ink2
        }
    }
}

/// One path's diff (`rc_git_diff`): unstaged against the index, or staged against `HEAD`; an untracked file shows as
/// all added.
struct DiffScreen: View {
    var controller: SessionController
    var entry: RemoteStatusEntry
    var staged: Bool

    var body: some View {
        VStack(spacing: 0) {
            ReviewBackHeader(title: (entry.path as NSString).lastPathComponent, subtitle: staged ? "Staged · \(entry.path)" : entry.path)
            ReviewLoader(key: "diff:\(entry.path):\(staged)") {
                try RemoteDiff.from(await reviewRequest(controller, .diff(entry.path, staged: staged, origPath: entry.origPath)))
            } content: { diff, _ in
                VStack(alignment: .leading, spacing: 8) {
                    if diff.untracked {
                        ReviewNote(text: "A new file git does not track yet: every line is new.").padding(.horizontal, Pi.gutter)
                    }
                    if diff.truncated {
                        ReviewNote(systemName: "scissors", text: "The diff is long: the computer sent its first part.").padding(.horizontal, Pi.gutter)
                    }
                    if diff.diff.isEmpty {
                        ReviewNote(text: "No changes to show on this side.").padding(.horizontal, Pi.gutter).padding(.top, 8)
                        Spacer()
                    } else {
                        DiffBody(text: diff.diff)
                    }
                }
            }
        }
    }
}
