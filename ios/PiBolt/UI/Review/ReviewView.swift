import PiBoltProtocol
import SwiftUI

/// Review the computer's working tree without changing it (`host.review`, docs/remote-control.md "Review"): its
/// changes as git sees them, its history, and its files. Every command only reads; nothing here stages, commits or
/// edits. The Swift side of the Android app's RemoteReview screens.
struct ReviewView: View {
    var controller: SessionController
    @Environment(\.dismiss) private var dismiss
    @State private var tab: ReviewTab = .changes
    @State private var path: [ReviewRoute] = []

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                ReviewHeader(title: "Review", subtitle: folderName, leading: .close) { dismiss() }
                ReviewTabs(tab: $tab)
                    .padding(.horizontal, Pi.gutter)
                    .padding(.bottom, 8)
                switch tab {
                case .changes: ChangesTab(controller: controller)
                case .history: HistoryTab(controller: controller)
                case .files: FolderScreen(controller: controller, path: "", embedded: true)
                }
            }
            .background(Pi.canvas.ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: ReviewRoute.self) { route in
                Group {
                    switch route {
                    case let .diff(entry, staged):
                        DiffScreen(controller: controller, entry: entry, staged: staged)
                    case let .commit(hash):
                        CommitScreen(controller: controller, hash: hash)
                    case let .folder(path):
                        FolderScreen(controller: controller, path: path, embedded: false)
                    case let .file(path):
                        FileScreen(controller: controller, path: path)
                    }
                }
                .background(Pi.canvas.ignoresSafeArea())
                .toolbar(.hidden, for: .navigationBar)
            }
        }
    }

    private var folderName: String {
        let cwd = controller.state.host?.cwd ?? ""
        let name = (cwd as NSString).lastPathComponent
        return name.isEmpty ? controller.hostName : "\(name) on \(controller.hostName)"
    }
}

enum ReviewTab: String, CaseIterable, Identifiable {
    case changes = "Changes"
    case history = "History"
    case files = "Files"
    var id: String { rawValue }
}

/// A screen the review pushes. A diff carries its status entry, so the screen can name a rename and an untracked file.
enum ReviewRoute: Hashable {
    case diff(RemoteStatusEntry, staged: Bool)
    case commit(String)
    case folder(String)
    case file(String)
}

/// One read-only review command, with the review's time budget.
@MainActor
func reviewRequest(_ controller: SessionController, _ request: ReviewRequest) async -> RpcResponse {
    await controller.request(request.type, timeout: 30, request.fields)
}

// MARK: Shared pieces

/// The review's title row: a glass close (or back) button, the title and a quiet subtitle.
struct ReviewHeader: View {
    enum Leading { case close, back }
    var title: String
    var subtitle: String?
    var leading: Leading
    var action: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            GlassButton(systemName: leading == .close ? "xmark" : "chevron.left", label: leading == .close ? "Close" : "Back", action: action)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 17, weight: .semibold)).foregroundStyle(Pi.ink1).lineLimit(1).truncationMode(.middle)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle).font(.system(size: 13)).foregroundStyle(Pi.ink3).lineLimit(1).truncationMode(.middle)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .padding(.top, 4)
        .padding(.bottom, 8)
    }
}

/// A pushed review screen's header: back pops the navigation stack.
struct ReviewBackHeader: View {
    var title: String
    var subtitle: String?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ReviewHeader(title: title, subtitle: subtitle, leading: .back) { dismiss() }
    }
}

/// Changes, History, Files: a capsule segmented control in the app's surfaces.
private struct ReviewTabs: View {
    @Binding var tab: ReviewTab

    var body: some View {
        HStack(spacing: 4) {
            ForEach(ReviewTab.allCases) { item in
                Button {
                    tab = item
                } label: {
                    Text(item.rawValue)
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(tab == item ? Pi.ink1 : Pi.ink3)
                        .frame(maxWidth: .infinity, minHeight: 36)
                        .background(Capsule().fill(tab == item ? Pi.surface3 : Color.clear))
                        .contentShape(Capsule())
                }
                .buttonStyle(PressScale())
                .accessibilityAddTraits(tab == item ? .isSelected : [])
            }
        }
        .padding(4)
        .background(Capsule().fill(Pi.surface1))
        .overlay(Capsule().strokeBorder(Pi.line, lineWidth: 1))
    }
}

enum ReviewPhase<T> {
    case loading
    case failed(String)
    case done(T)
}

/// Runs `load` when it appears (and again on `reload`), and shows a spinner, the computer's error with Retry, or
/// `content`. A reload keeps the content on screen until the new answer arrives.
struct ReviewLoader<T, Content: View>: View {
    var key: String
    var load: @MainActor () async throws -> T
    @ViewBuilder var content: (T, @escaping () -> Void) -> Content
    @State private var phase: ReviewPhase<T> = .loading
    @State private var attempt = 0

    var body: some View {
        Group {
            switch phase {
            case .loading:
                ReviewLoading()
            case let .failed(message):
                ReviewFailure(message: message) { attempt += 1 }
            case let .done(value):
                content(value) { attempt += 1 }
            }
        }
        .task(id: "\(key)#\(attempt)") {
            if case .failed = phase { phase = .loading }
            do {
                let value = try await load()
                if !Task.isCancelled { phase = .done(value) }
            } catch let error as ReviewError {
                if !Task.isCancelled { phase = .failed(error.message) }
            } catch {
                if !Task.isCancelled { phase = .failed("The computer could not answer") }
            }
        }
    }
}

struct ReviewLoading: View {
    var body: some View {
        VStack {
            HStack(spacing: 10) {
                QuadSpinner(size: 16, color: Pi.ink2)
                Text("Loading…").font(.system(size: 15)).foregroundStyle(Pi.ink3)
            }
            .padding(Pi.gutter)
            Spacer()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct ReviewFailure: View {
    var message: String
    var retry: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 13)).foregroundStyle(Pi.danger).padding(.top, 2)
                Text(message).font(.system(size: 15)).foregroundStyle(Pi.ink2).frame(maxWidth: .infinity, alignment: .leading)
            }
            Button("Retry", action: retry)
                .buttonStyle(SecondaryButtonStyle())
                .frame(maxWidth: 160)
            Spacer()
        }
        .padding(Pi.gutter)
    }
}

/// A quiet line under a list or above a diff: cut output, files left out, a folder outside git.
struct ReviewNote: View {
    var systemName: String = "info.circle"
    var text: String
    var tint: Color = Pi.ink3

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: systemName).font(.system(size: 12, weight: .semibold)).foregroundStyle(tint).padding(.top, 2)
            Text(text).font(.system(size: 14)).foregroundStyle(Pi.ink3).frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// A section title in a review list.
struct ReviewSectionTitle: View {
    var text: String
    var count: Int?

    var body: some View {
        HStack(spacing: 6) {
            Text(text).font(.system(size: 15, weight: .medium)).foregroundStyle(Pi.ink3)
            if let count { Text("\(count)").font(.system(size: 13)).foregroundStyle(Pi.ink4) }
            Spacer()
        }
        .padding(.top, 20)
        .padding(.bottom, 4)
    }
}

/// "+3 −1" in the diff colours; nothing for no lines.
struct LineCounts: View {
    var count: RemoteLineCount?

    var body: some View {
        if let count, count.added > 0 || count.removed > 0 {
            HStack(spacing: 6) {
                if count.added > 0 { Text("+\(count.added)").foregroundStyle(Pi.success) }
                if count.removed > 0 { Text("−\(count.removed)").foregroundStyle(Pi.danger) }
            }
            .font(.system(size: 13, weight: .medium, design: .monospaced))
        }
    }
}

/// A file path as a list shows it: the name, then its folder in a quieter ink.
struct PathLabel: View {
    var path: String
    var origPath: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text((path as NSString).lastPathComponent).font(.system(size: 16)).foregroundStyle(Pi.ink1).lineLimit(1).truncationMode(.middle)
            let folder = (path as NSString).deletingLastPathComponent
            if let origPath {
                Text("from \(origPath)").font(.system(size: 13)).foregroundStyle(Pi.ink3).lineLimit(1).truncationMode(.middle)
            } else if !folder.isEmpty {
                Text(folder).font(.system(size: 13)).foregroundStyle(Pi.ink3).lineLimit(1).truncationMode(.middle)
            }
        }
    }
}

struct ReviewDivider: View {
    var inset: CGFloat = 0
    var body: some View { Rectangle().fill(Pi.line).frame(height: 1).padding(.leading, inset) }
}

func byteSize(_ bytes: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
}
