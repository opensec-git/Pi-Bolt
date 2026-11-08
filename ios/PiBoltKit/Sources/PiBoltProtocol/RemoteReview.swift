import Foundation

// Answers of the read-only review commands (`host.review`, docs/remote-control.md "Review"): the computer's working
// tree as git sees it, and its files. Every path is relative to the session's folder and uses `/`. Parsers are lenient:
// a missing field reads as empty, an entry without a path is dropped, and a failed response throws its error.
// A port of the Android app's `RemoteReview.kt` (android/protocol).

/// A review command to send (`request(type, fields)`). Paths are relative to the session's folder, `""` for the folder
/// itself.
public struct ReviewRequest: Equatable, Sendable {
    public var type: String
    public var fields: [String: JSON]

    /// The largest page of `log` the computer sends.
    public static let maxLog = 200

    public static func status() -> ReviewRequest { ReviewRequest(type: "rc_git_status", fields: [:]) }

    /// `origPath`: the source of a staged rename, so the diff shows the rename.
    public static func diff(_ path: String, staged: Bool, origPath: String? = nil) -> ReviewRequest {
        var fields: [String: JSON] = ["path": .string(path), "staged": .bool(staged)]
        if staged, let origPath { fields["origPath"] = .string(origPath) }
        return ReviewRequest(type: "rc_git_diff", fields: fields)
    }

    public static func log(limit: Int, skip: Int = 0) -> ReviewRequest {
        ReviewRequest(type: "rc_git_log", fields: ["limit": .number(Double(min(max(limit, 1), maxLog))), "skip": .number(Double(max(skip, 0)))])
    }

    public static func show(_ hash: String) -> ReviewRequest { ReviewRequest(type: "rc_git_show", fields: ["hash": .string(hash)]) }

    public static func listDir(_ path: String) -> ReviewRequest { ReviewRequest(type: "rc_list_dir", fields: ["path": .string(path)]) }

    public static func readFile(_ path: String) -> ReviewRequest { ReviewRequest(type: "rc_read_file", fields: ["path": .string(path)]) }
}

/// A review command failed or was refused on the computer; `message` says why, for the user.
public struct ReviewError: Error, Equatable, CustomStringConvertible {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var description: String { message }
}

extension RpcResponse {
    func reviewData() throws -> JSON {
        if !success {
            let text = error?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            throw ReviewError(text.isEmpty ? "The computer could not answer" : text)
        }
        guard let data, data.isObject else { throw ReviewError("The computer sent no answer") }
        return data
    }
}

private func count(_ json: JSON, _ key: String) -> Int {
    json.long(key).map { Int(clamping: $0) } ?? 0
}

private func stringList(_ json: JSON, _ key: String) -> [String] {
    json.arr(key)?.compactMap(\.stringValue) ?? []
}

public struct RemoteLineCount: Hashable, Sendable {
    public var added: Int
    public var removed: Int

    public init(added: Int, removed: Int) {
        self.added = added
        self.removed = removed
    }

    public static let zero = RemoteLineCount(added: 0, removed: 0)

    static func from(_ json: JSON?) -> RemoteLineCount? {
        guard let json, json.isObject else { return nil }
        return RemoteLineCount(added: count(json, "added"), removed: count(json, "removed"))
    }
}

/// `branch` of `rc_git_status`: `head` nil when detached, `oid` nil before the first commit.
public struct RemoteBranch: Equatable, Sendable {
    public var head: String?
    public var oid: String?
    public var upstream: String?
    public var ahead: Int
    public var behind: Int
}

/// One changed path. `index` is the staged change and `worktree` the unstaged one: a porcelain letter (`M A D R C T`,
/// `?` untracked, `U` conflict) or nil. `staged` and `unstaged` are that side's line counts, nil when git has none.
public struct RemoteStatusEntry: Hashable, Sendable, Identifiable {
    public var path: String
    public var origPath: String?
    public var index: Character?
    public var worktree: Character?
    public var kind: String
    public var conflicted: Bool
    public var staged: RemoteLineCount?
    public var unstaged: RemoteLineCount?

    public var id: String { path }
}

/// `rc_git_status`. `repo` false: the session's folder is not in a git working tree (then `branch` is nil).
public struct RemoteGitStatus: Equatable, Sendable {
    public var repo: Bool
    public var branch: RemoteBranch?
    public var entries: [RemoteStatusEntry]
    public var total: RemoteLineCount
    public var truncated: Bool

    public static func from(_ response: RpcResponse) throws -> RemoteGitStatus {
        let d = try response.reviewData()
        let repo = d.bool("repo") == true
        let branch: RemoteBranch? = repo ? d.obj("branch").map { b in
            RemoteBranch(head: b.str("head"), oid: b.str("oid"), upstream: b.str("upstream"), ahead: count(b, "ahead"), behind: count(b, "behind"))
        } : nil
        let entries: [RemoteStatusEntry] = (d.arr("entries") ?? []).compactMap { o in
            guard o.isObject, let path = o.str("path"), !path.isEmpty else { return nil }
            return RemoteStatusEntry(
                path: path,
                origPath: o.str("origPath").flatMap { $0.isEmpty ? nil : $0 },
                index: letter(o.str("index")),
                worktree: letter(o.str("worktree")),
                kind: o.str("kind") ?? "",
                conflicted: o.bool("conflicted") == true,
                staged: RemoteLineCount.from(o.obj("staged")),
                unstaged: RemoteLineCount.from(o.obj("unstaged"))
            )
        }
        return RemoteGitStatus(
            repo: repo,
            branch: branch,
            entries: repo ? entries : [],
            total: RemoteLineCount.from(d.obj("total")) ?? .zero,
            truncated: d.bool("truncated") == true
        )
    }

    private static func letter(_ s: String?) -> Character? {
        guard let s, s.count == 1, let c = s.first, "MADRCT?U".contains(c) else { return nil }
        return c
    }
}

/// `rc_git_diff`: `diff` is unified diff text (`git diff`), empty when nothing changed.
public struct RemoteDiff: Equatable, Sendable {
    public var path: String
    public var staged: Bool
    public var untracked: Bool
    public var diff: String
    public var truncated: Bool

    public static func from(_ response: RpcResponse) throws -> RemoteDiff {
        let d = try response.reviewData()
        return RemoteDiff(
            path: d.str("path") ?? "",
            staged: d.bool("staged") == true,
            untracked: d.bool("untracked") == true,
            diff: d.str("diff") ?? "",
            truncated: d.bool("truncated") == true
        )
    }
}

/// A commit of `rc_git_log` or `rc_git_show`. `time` is the author time in Unix seconds.
public struct RemoteCommit: Equatable, Sendable, Identifiable {
    public var hash: String
    public var short: String
    public var parents: [String]
    public var author: String
    public var email: String
    public var time: Int64
    public var subject: String
    public var body: String
    /// The first `maxRefs` names pointing at it; `refCount` counts them all.
    public var refs: [String]
    public var refCount: Int

    public var id: String { hash }

    /// Refs kept per commit: the repository decides how many point at one commit, and each is a chip on the screen.
    public static let maxRefs = 8

    /// 4 to 64 lowercase hex characters: never a ref or an option.
    static func isHash(_ s: String) -> Bool {
        (4 ... 64).contains(s.utf8.count) && s.utf8.allSatisfy { (48 ... 57).contains($0) || (97 ... 102).contains($0) }
    }

    /// Nil without a valid `hash`.
    public static func from(_ o: JSON?) -> RemoteCommit? {
        guard let o, o.isObject, let hash = o.str("hash"), isHash(hash) else { return nil }
        return RemoteCommit(
            hash: hash,
            short: o.str("short").flatMap { isHash($0) ? $0 : nil } ?? String(hash.prefix(7)),
            parents: stringList(o, "parents").filter(isHash),
            author: o.str("author") ?? "",
            email: o.str("email") ?? "",
            time: o.long("time") ?? 0,
            subject: o.str("subject") ?? "",
            body: o.str("body") ?? "",
            refs: Array(stringList(o, "refs").prefix(maxRefs)),
            refCount: o.arr("refs")?.count ?? 0
        )
    }
}

/// `rc_git_log`: newest first; `more` when older commits follow.
public struct RemoteLog: Equatable, Sendable {
    public var repo: Bool
    public var commits: [RemoteCommit]
    public var more: Bool

    public static func from(_ response: RpcResponse) throws -> RemoteLog {
        let d = try response.reviewData()
        return RemoteLog(repo: d.bool("repo") == true, commits: (d.arr("commits") ?? []).compactMap(RemoteCommit.from), more: d.bool("more") == true)
    }
}

/// `rc_git_show`: the patch against the first parent; `withheld` files that look like secrets were left out of it.
public struct RemoteShow: Equatable, Sendable {
    public var commit: RemoteCommit?
    public var diff: String
    public var truncated: Bool
    public var withheld: [String]

    public static func from(_ response: RpcResponse) throws -> RemoteShow {
        let d = try response.reviewData()
        return RemoteShow(commit: RemoteCommit.from(d.obj("commit")), diff: d.str("diff") ?? "", truncated: d.bool("truncated") == true, withheld: stringList(d, "withheld"))
    }
}

/// One entry of `rc_list_dir`. `dir` and `file` come from `kind` (a symlink has its target's); `outside`: a symlink out
/// of the session's folder, which the computer will not open. `mtime` is Unix milliseconds.
public struct RemoteDirEntry: Equatable, Sendable, Identifiable {
    public var name: String
    public var dir: Bool
    public var file: Bool
    public var symlink: Bool
    public var outside: Bool
    public var size: Int64
    public var mtime: Int64

    public var id: String { name }
}

/// `rc_list_dir`: `path` is the folder listed, `""` for the session's folder.
public struct RemoteListing: Equatable, Sendable {
    public var path: String
    public var entries: [RemoteDirEntry]
    public var truncated: Bool

    public static func from(_ response: RpcResponse) throws -> RemoteListing {
        let d = try response.reviewData()
        let entries: [RemoteDirEntry] = (d.arr("entries") ?? []).compactMap { o in
            // A name is one path segment: anything else is not shown (or opened).
            guard o.isObject, let name = o.str("name"), !name.isEmpty, name != ".", name != "..", !name.contains("/"), !name.contains("\\") else { return nil }
            let kind = o.str("kind")
            return RemoteDirEntry(
                name: name,
                dir: kind == "dir",
                file: kind == "file",
                symlink: o.bool("symlink") == true,
                outside: o.bool("outside") == true,
                size: o.long("size") ?? 0,
                mtime: o.long("mtime") ?? 0
            )
        }
        return RemoteListing(path: d.str("path") ?? "", entries: entries, truncated: d.bool("truncated") == true)
    }
}

/// `rc_read_file`: `content` is nil for a binary file; `truncated` when only the first part was sent.
public struct RemoteFile: Equatable, Sendable {
    public var path: String
    public var size: Int64
    public var mtime: Int64
    public var binary: Bool
    public var truncated: Bool
    public var content: String?

    public static func from(_ response: RpcResponse) throws -> RemoteFile {
        let d = try response.reviewData()
        let binary = d.bool("binary") == true
        return RemoteFile(
            path: d.str("path") ?? "",
            size: d.long("size") ?? 0,
            mtime: d.long("mtime") ?? 0,
            binary: binary,
            truncated: d.bool("truncated") == true,
            content: binary ? nil : d.str("content") ?? ""
        )
    }
}

/// Joins a folder (`RemoteListing.path`) and an entry name into the path the review commands take.
public func remoteChild(_ folder: String, _ name: String) -> String {
    folder.isEmpty ? name : "\(folder)/\(name)"
}
