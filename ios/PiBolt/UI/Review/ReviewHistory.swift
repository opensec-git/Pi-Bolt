import PiBoltProtocol
import SwiftUI

/// The branch's commits, newest first (`rc_git_log`), 50 at a time. A commit opens its patch.
struct HistoryTab: View {
    var controller: SessionController
    static let page = 50
    @State private var phase: ReviewPhase<RemoteLog> = .loading
    @State private var loadingMore = false
    @State private var moreError: String?
    @State private var attempt = 0

    var body: some View {
        Group {
            switch phase {
            case .loading:
                ReviewLoading()
            case let .failed(message):
                ReviewFailure(message: message) { attempt += 1 }
            case let .done(log):
                list(log)
            }
        }
        .task(id: attempt) { await loadFirst() }
    }

    private func list(_ log: RemoteLog) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if !log.repo {
                    ReviewNote(systemName: "folder", text: "This folder is not in a git repository: it has no history.").padding(.top, 8)
                } else if log.commits.isEmpty {
                    ReviewNote(text: "No commits yet.").padding(.top, 8)
                }
                ForEach(log.commits) { commit in
                    NavigationLink(value: ReviewRoute.commit(commit.hash)) {
                        CommitRow(commit: commit)
                    }
                    .buttonStyle(PressScale())
                    if commit.id != log.commits.last?.id { ReviewDivider() }
                }
                if log.more {
                    VStack(alignment: .leading, spacing: 8) {
                        if let moreError { Text(moreError).font(.system(size: 14)).foregroundStyle(Pi.danger) }
                        Button {
                            Task { await loadMore(log) }
                        } label: {
                            HStack(spacing: 8) {
                                if loadingMore { QuadSpinner(size: 13, color: Pi.ink2) }
                                Text(loadingMore ? "Loading…" : "Load older commits")
                            }
                        }
                        .buttonStyle(SecondaryButtonStyle())
                        .disabled(loadingMore)
                    }
                    .padding(.top, 16)
                }
            }
            .padding(.horizontal, Pi.gutter)
            .padding(.bottom, 40)
        }
        .refreshable { await loadFirst() }
    }

    private func loadFirst() async {
        do {
            let log = try RemoteLog.from(await reviewRequest(controller, .log(limit: Self.page)))
            if !Task.isCancelled { phase = .done(log) }
        } catch let error as ReviewError {
            guard !Task.isCancelled else { return }
            // A failed refresh keeps the list it had.
            if case .done = phase { return }
            phase = .failed(error.message)
        } catch {}
    }

    private func loadMore(_ log: RemoteLog) async {
        guard !loadingMore else { return }
        loadingMore = true
        moreError = nil
        defer { loadingMore = false }
        do {
            let next = try RemoteLog.from(await reviewRequest(controller, .log(limit: Self.page, skip: log.commits.count)))
            var merged = log
            // A commit made meanwhile shifts the pages: one already shown is not listed twice.
            let known = Set(log.commits.map(\.hash))
            merged.commits += next.commits.filter { !known.contains($0.hash) }
            merged.more = next.more
            phase = .done(merged)
        } catch let error as ReviewError {
            moreError = error.message
        } catch {}
    }
}

private struct CommitRow: View {
    var commit: RemoteCommit

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(commit.subject.isEmpty ? "(no message)" : commit.subject)
                .font(.system(size: 16)).foregroundStyle(Pi.ink1).lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: 6) {
                Text(commit.short).font(Pi.monoSmall).foregroundStyle(Pi.ink2)
                Text(meta).font(.system(size: 13)).foregroundStyle(Pi.ink3).lineLimit(1)
            }
            if !commit.refs.isEmpty { RefChips(refs: commit.refs, total: commit.refCount) }
        }
        .padding(.vertical, 11)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private var meta: String {
        var parts: [String] = []
        if !commit.author.isEmpty { parts.append(commit.author) }
        if commit.time > 0 { parts.append(relativeShort(Date(timeIntervalSince1970: TimeInterval(commit.time)))) }
        return parts.joined(separator: " · ")
    }
}

/// `HEAD -> main`, `origin/main`, `tag: v1.0` as small capsules.
private struct RefChips: View {
    var refs: [String]
    var total: Int

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(refs, id: \.self) { ref in chip(ref) }
                if total > refs.count { chip("+\(total - refs.count)") }
            }
        }
    }

    private func chip(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 12, weight: .medium))
            .foregroundStyle(Pi.ink2)
            .lineLimit(1)
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(Capsule().fill(Pi.surface2))
            .overlay(Capsule().strokeBorder(Pi.line, lineWidth: 1))
    }
}

/// One commit (`rc_git_show`): its message, author and refs, the files left out as secrets, then its patch.
struct CommitScreen: View {
    var controller: SessionController
    var hash: String

    var body: some View {
        VStack(spacing: 0) {
            ReviewBackHeader(title: "Commit \(String(hash.prefix(7)))", subtitle: nil)
            ReviewLoader(key: "show:\(hash)") {
                try RemoteShow.from(await reviewRequest(controller, .show(hash)))
            } content: { show, _ in
                VStack(alignment: .leading, spacing: 0) {
                    if let commit = show.commit { header(commit).padding(.horizontal, Pi.gutter).padding(.bottom, 8) }
                    VStack(alignment: .leading, spacing: 6) {
                        if !show.withheld.isEmpty {
                            ReviewNote(systemName: "lock", text: "Left out because they look like secrets: \(show.withheld.joined(separator: ", ")).")
                        }
                        if show.truncated {
                            ReviewNote(systemName: "scissors", text: "The patch is long: the computer sent its first part.")
                        }
                    }
                    .padding(.horizontal, Pi.gutter)
                    if show.diff.isEmpty {
                        ReviewNote(text: "This commit changes no file contents.").padding(Pi.gutter)
                        Spacer()
                    } else {
                        DiffBody(text: show.diff)
                    }
                }
            }
        }
    }

    private func header(_ commit: RemoteCommit) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(commit.subject.isEmpty ? "(no message)" : commit.subject)
                .font(.system(size: 18, weight: .semibold)).foregroundStyle(Pi.ink1).textSelection(.enabled)
            if !commit.body.isEmpty {
                Text(commit.body).font(.system(size: 15)).foregroundStyle(Pi.ink2).lineLimit(12).textSelection(.enabled)
            }
            Text(authorLine(commit)).font(.system(size: 13)).foregroundStyle(Pi.ink3)
            Text(commit.hash).font(Pi.monoSmall).foregroundStyle(Pi.ink4).lineLimit(1).truncationMode(.middle).textSelection(.enabled)
            if !commit.refs.isEmpty { RefChips(refs: commit.refs, total: commit.refCount) }
        }
    }

    private func authorLine(_ commit: RemoteCommit) -> String {
        var parts: [String] = []
        if !commit.author.isEmpty { parts.append(commit.email.isEmpty ? commit.author : "\(commit.author) <\(commit.email)>") }
        if commit.time > 0 {
            parts.append(Date(timeIntervalSince1970: TimeInterval(commit.time)).formatted(date: .abbreviated, time: .shortened))
        }
        if commit.parents.count > 1 { parts.append("merge of \(commit.parents.count) parents") }
        return parts.joined(separator: " · ")
    }
}
