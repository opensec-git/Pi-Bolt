import Foundation

/// One row of a unified diff (`git diff`, `git show`), for the review screens to colour.
public struct DiffRow: Equatable, Sendable {
    public enum Kind: Equatable, Sendable {
        /// `diff --git a/x b/x`: a file starts. `text` is its path (the new one).
        case file
        /// The lines between a file's start and its first hunk (`index`, modes, renames, `---`, `+++`, binary).
        case meta
        /// `@@ -a,b +c,d @@ …`, as is.
        case hunk
        case added
        case removed
        case context
        /// `\ No newline at end of file`.
        case noNewline
        /// Text outside any file (a commit header, stat lines), as is.
        case other
    }

    public var kind: Kind
    /// The line without its `+`, `-` or ` ` prefix (as is for the other kinds).
    public var text: String
    /// Line number in the old file (removed and context rows).
    public var old: Int?
    /// Line number in the new file (added and context rows).
    public var new: Int?

    public init(kind: Kind, text: String, old: Int? = nil, new: Int? = nil) {
        self.kind = kind
        self.text = text
        self.old = old
        self.new = new
    }
}

public enum DiffLines {
    /// The rows of [text]. Lenient: a line that fits no pattern is kept as `.other` or `.meta`, never dropped, so the
    /// screen shows everything the computer sent.
    public static func parse(_ text: String) -> [DiffRow] {
        var rows: [DiffRow] = []
        var lines = text.components(separatedBy: "\n")
        // A trailing newline leaves one empty last element: not a line of the diff.
        if lines.last == "" { lines.removeLast() }
        var inFile = false
        var inHunk = false
        var old = 0
        var new = 0
        for raw in lines {
            let line = raw.hasSuffix("\r") ? String(raw.dropLast()) : raw
            if line.hasPrefix("diff --git ") || line.hasPrefix("diff --cc ") {
                inFile = true
                inHunk = false
                rows.append(DiffRow(kind: .file, text: filePath(line)))
                continue
            }
            if !inFile {
                rows.append(DiffRow(kind: .other, text: line))
                continue
            }
            if line.hasPrefix("@@") {
                if let (o, n) = hunkStart(line) {
                    old = o
                    new = n
                    inHunk = true
                } else {
                    inHunk = false
                }
                rows.append(DiffRow(kind: .hunk, text: line))
                continue
            }
            if inHunk {
                if let first = line.first {
                    switch first {
                    case "+":
                        rows.append(DiffRow(kind: .added, text: String(line.dropFirst()), new: new))
                        new += 1
                        continue
                    case "-":
                        rows.append(DiffRow(kind: .removed, text: String(line.dropFirst()), old: old))
                        old += 1
                        continue
                    case " ":
                        rows.append(DiffRow(kind: .context, text: String(line.dropFirst()), old: old, new: new))
                        old += 1
                        new += 1
                        continue
                    case "\\":
                        rows.append(DiffRow(kind: .noNewline, text: line))
                        continue
                    default:
                        // Something else after a hunk (`git show` stat text): ends the hunk, not the file.
                        inHunk = false
                    }
                } else {
                    // An empty line inside a hunk is a context line whose single space was stripped.
                    rows.append(DiffRow(kind: .context, text: "", old: old, new: new))
                    old += 1
                    new += 1
                    continue
                }
            }
            rows.append(DiffRow(kind: .meta, text: line))
        }
        return rows
    }

    /// The old and new start lines of `@@ -a,b +c,d @@`.
    static func hunkStart(_ line: String) -> (Int, Int)? {
        let parts = line.split(separator: " ", omittingEmptySubsequences: true)
        guard parts.count >= 3, parts[0] == "@@" else { return nil }
        func start(_ part: Substring, _ sign: Character) -> Int? {
            guard part.first == sign else { return nil }
            return Int(part.dropFirst().split(separator: ",", maxSplits: 1, omittingEmptySubsequences: false)[0])
        }
        guard let o = start(parts[1], "-"), let n = start(parts[2], "+") else { return nil }
        return (o, n)
    }

    /// `diff --git a/x b/y` -> `y` (the line as is when it does not split that way).
    static func filePath(_ line: String) -> String {
        if let range = line.range(of: " b/", options: .backwards) { return String(line[range.upperBound...]) }
        return line
    }

    /// Added and removed lines of `rows`.
    public static func counts(_ rows: [DiffRow]) -> RemoteLineCount {
        var added = 0
        var removed = 0
        for r in rows {
            if r.kind == .added { added += 1 } else if r.kind == .removed { removed += 1 }
        }
        return RemoteLineCount(added: added, removed: removed)
    }
}
