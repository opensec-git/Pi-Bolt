import PiBoltProtocol
import SwiftUI

/// One folder of the session's folder (`rc_list_dir`), in the computer's order. A folder opens the next level, a file
/// its text; a link that leads outside the session's folder is listed but does not open (the computer refuses it).
struct FolderScreen: View {
    var controller: SessionController
    /// Relative to the session's folder, `""` for the folder itself.
    var path: String
    /// The Files tab itself: no header of its own (the review's header is above it).
    var embedded: Bool

    var body: some View {
        VStack(spacing: 0) {
            if !embedded {
                ReviewBackHeader(title: (path as NSString).lastPathComponent, subtitle: path)
            }
            ReviewLoader(key: "dir:\(path)") {
                try RemoteListing.from(await reviewRequest(controller, .listDir(path)))
            } content: { listing, reload in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        if listing.entries.isEmpty {
                            ReviewNote(text: "This folder is empty.").padding(.top, 8)
                        }
                        ForEach(listing.entries) { entry in
                            row(entry, folder: listing.path)
                            if entry.id != listing.entries.last?.id { ReviewDivider(inset: 36) }
                        }
                        if listing.truncated {
                            ReviewNote(text: "The computer listed only the first entries of this folder.").padding(.top, 16)
                        }
                    }
                    .padding(.horizontal, Pi.gutter)
                    .padding(.bottom, 40)
                }
                .refreshable { reload() }
            }
        }
    }

    @ViewBuilder private func row(_ entry: RemoteDirEntry, folder: String) -> some View {
        let child = remoteChild(folder, entry.name)
        if entry.outside || (!entry.dir && !entry.file) {
            FileRow(entry: entry).opacity(0.55)
        } else {
            NavigationLink(value: entry.dir ? ReviewRoute.folder(child) : ReviewRoute.file(child)) {
                FileRow(entry: entry)
            }
            .buttonStyle(PressScale())
        }
    }
}

private struct FileRow: View {
    var entry: RemoteDirEntry

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 15)).foregroundStyle(entry.dir ? Pi.ink2 : Pi.ink3).frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.name).font(.system(size: 16)).foregroundStyle(Pi.ink1).lineLimit(1).truncationMode(.middle)
                if let detail { Text(detail).font(.system(size: 13)).foregroundStyle(Pi.ink3).lineLimit(1) }
            }
            Spacer(minLength: 8)
            if entry.dir, !entry.outside {
                Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold)).foregroundStyle(Pi.ink4)
            }
        }
        .padding(.vertical, 10)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private var icon: String {
        if entry.outside { return "arrow.up.forward.square" }
        if entry.dir { return entry.symlink ? "folder.badge.questionmark" : "folder" }
        if entry.file { return entry.symlink ? "link" : "doc" }
        return "questionmark.square.dashed"
    }

    private var detail: String? {
        if entry.outside { return "Link outside the project: not opened" }
        var parts: [String] = []
        if entry.symlink { parts.append("Link") }
        if entry.file { parts.append(byteSize(entry.size)) }
        if entry.mtime > 0 { parts.append(relativeShort(Date(timeIntervalSince1970: TimeInterval(entry.mtime) / 1000))) }
        if !entry.dir && !entry.file { parts.append("Not a file or folder") }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}

/// A file's text (`rc_read_file`), with line numbers. A binary file is described, not shown; a long file shows its
/// first part.
struct FileScreen: View {
    var controller: SessionController
    var path: String
    static let maxLines = 20000

    var body: some View {
        VStack(spacing: 0) {
            ReviewBackHeader(title: (path as NSString).lastPathComponent, subtitle: path)
            ReviewLoader(key: "file:\(path)") {
                try RemoteFile.from(await reviewRequest(controller, .readFile(path)))
            } content: { file, _ in
                VStack(alignment: .leading, spacing: 8) {
                    if let content = file.content {
                        if file.truncated {
                            ReviewNote(systemName: "scissors", text: "\(byteSize(file.size)): the computer sent the first part.").padding(.horizontal, Pi.gutter)
                        }
                        if content.isEmpty {
                            ReviewNote(text: "This file is empty.").padding(.horizontal, Pi.gutter).padding(.top, 8)
                            Spacer()
                        } else {
                            FileText(text: content)
                        }
                    } else {
                        ReviewNote(systemName: "doc", text: "A binary file of \(byteSize(file.size)). It is not shown here.").padding(Pi.gutter)
                        Spacer()
                    }
                }
            }
        }
    }
}

private struct FileText: View {
    var text: String

    var body: some View {
        var lines = text.components(separatedBy: "\n")
        if lines.count > 1, lines.last == "" { lines.removeLast() }
        let total = lines.count
        let shown = Array(lines.prefix(FileScreen.maxLines))
        let digits = String(shown.count).count
        return GeometryReader { proxy in
            ScrollView([.vertical, .horizontal]) {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(shown.enumerated()), id: \.offset) { index, line in
                        HStack(alignment: .top, spacing: 12) {
                            Text(String(index + 1).leftPadded(digits))
                                .foregroundStyle(Pi.ink4)
                                .accessibilityHidden(true)
                            Text(line.isEmpty ? " " : line).foregroundStyle(Pi.ink1)
                        }
                        .font(.system(size: 12, design: .monospaced))
                        .fixedSize()
                        .padding(.horizontal, 14)
                        .padding(.vertical, 1)
                        .frame(minWidth: proxy.size.width, alignment: .leading)
                    }
                    if total > shown.count {
                        ReviewNote(systemName: "scissors", text: "\(total - shown.count) more lines are not shown here.")
                            .frame(width: max(proxy.size.width - 2 * Pi.gutter, 100), alignment: .leading)
                            .padding(.horizontal, Pi.gutter)
                            .padding(.vertical, 12)
                    }
                }
                .padding(.bottom, 32)
                .textSelection(.enabled)
            }
        }
    }
}

private extension String {
    func leftPadded(_ width: Int) -> String {
        count >= width ? self : String(repeating: " ", count: width - count) + self
    }
}
