import Foundation

/// One block of a reply, as the transcript lays it out. Inline formatting (bold, italics, code spans, links) stays in
/// the text: the app renders it with `AttributedString(markdown:)`, which handles inline syntax but drops block
/// structure, hence this parser.
public enum MarkdownBlock: Equatable, Sendable {
    case heading(level: Int, text: String)
    case paragraph(String)
    /// A fenced or indented code block. `closed` is false while a streaming reply has not written the closing fence.
    case code(language: String?, text: String, closed: Bool)
    case list(ordered: Bool, start: Int, items: [ListItem])
    case quote([MarkdownBlock])
    case table(header: [String], alignments: [TableAlignment], rows: [[String]])
    case rule
}

public struct ListItem: Equatable, Sendable {
    /// Nesting depth, 0 for a top-level item.
    public var depth: Int
    public var text: String
    /// A task list item (`- [ ]` / `- [x]`).
    public var checked: Bool?
    /// The item's own number in an ordered list.
    public var number: Int?

    public init(depth: Int, text: String, checked: Bool? = nil, number: Int? = nil) {
        self.depth = depth
        self.text = text
        self.checked = checked
        self.number = number
    }
}

public enum TableAlignment: Equatable, Sendable {
    case leading, center, trailing
}

public enum Markdown {
    public static func parse(_ text: String) -> [MarkdownBlock] {
        let lines = text.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n")
        var parser = Parser(lines: lines, depth: 0)
        return parser.blocks()
    }

    /// Quotes nest at most this deep; deeper `>` markers stay in the text. A line of thousands of `>` from a reply
    /// would otherwise recurse once per marker.
    static let maxQuoteDepth = 4

    private struct Parser {
        let lines: [String]
        let depth: Int
        var i = 0

        init(lines: [String], depth: Int) {
            self.lines = lines
            self.depth = depth
        }

        mutating func blocks() -> [MarkdownBlock] {
            var out: [MarkdownBlock] = []
            var paragraph: [String] = []
            func flush() {
                if !paragraph.isEmpty {
                    out.append(.paragraph(paragraph.joined(separator: "\n")))
                    paragraph = []
                }
            }
            while i < lines.count {
                let line = lines[i]
                let trimmed = line.trimmingCharacters(in: .whitespaces)
                if trimmed.isEmpty {
                    flush()
                    i += 1
                    continue
                }
                if let fence = Self.fence(trimmed) {
                    flush()
                    out.append(code(fence: fence, indent: Self.indent(line)))
                    continue
                }
                if let heading = Self.heading(trimmed) {
                    flush()
                    out.append(heading)
                    i += 1
                    continue
                }
                if Self.isRule(trimmed) {
                    flush()
                    out.append(.rule)
                    i += 1
                    continue
                }
                if trimmed.hasPrefix(">"), depth < Markdown.maxQuoteDepth {
                    flush()
                    out.append(quote())
                    continue
                }
                if Self.listMarker(line) != nil {
                    flush()
                    out.append(list())
                    continue
                }
                if trimmed.hasPrefix("|") || (trimmed.contains("|") && i + 1 < lines.count && Self.isTableDivider(lines[i + 1])),
                   i + 1 < lines.count, Self.isTableDivider(lines[i + 1])
                {
                    flush()
                    out.append(table())
                    continue
                }
                paragraph.append(trimmed)
                i += 1
            }
            flush()
            return out
        }

        // MARK: Code

        static func fence(_ trimmed: String) -> (marker: String, language: String?)? {
            for marker in ["```", "~~~"] where trimmed.hasPrefix(marker) {
                let run = trimmed.prefix { $0 == marker.first }
                let info = trimmed.dropFirst(run.count).trimmingCharacters(in: .whitespaces)
                if marker == "```", info.contains("`") { return nil }
                let language = info.split(separator: " ").first.map(String.init)
                return (String(run), language?.isEmpty == false ? language : nil)
            }
            return nil
        }

        mutating func code(fence: (marker: String, language: String?), indent: Int) -> MarkdownBlock {
            i += 1
            var body: [String] = []
            while i < lines.count {
                let line = lines[i]
                let t = line.trimmingCharacters(in: .whitespaces)
                if t.hasPrefix(fence.marker), t.allSatisfy({ $0 == fence.marker.first }) {
                    i += 1
                    return .code(language: fence.language, text: body.joined(separator: "\n"), closed: true)
                }
                body.append(Self.dropIndent(line, upTo: indent))
                i += 1
            }
            return .code(language: fence.language, text: body.joined(separator: "\n"), closed: false)
        }

        // MARK: Headings and rules

        static func heading(_ trimmed: String) -> MarkdownBlock? {
            let hashes = trimmed.prefix { $0 == "#" }.count
            guard hashes >= 1, hashes <= 6 else { return nil }
            let rest = trimmed.dropFirst(hashes)
            guard rest.isEmpty || rest.first == " " else { return nil }
            var text = rest.trimmingCharacters(in: .whitespaces)
            // A closing run of #s is decoration.
            while text.hasSuffix("#") { text.removeLast() }
            return .heading(level: hashes, text: text.trimmingCharacters(in: .whitespaces))
        }

        static func isRule(_ trimmed: String) -> Bool {
            let compact = trimmed.replacingOccurrences(of: " ", with: "")
            guard compact.count >= 3, let first = compact.first, "-*_".contains(first) else { return false }
            return compact.allSatisfy { $0 == first }
        }

        // MARK: Quotes

        mutating func quote() -> MarkdownBlock {
            var inner: [String] = []
            while i < lines.count {
                let t = lines[i].trimmingCharacters(in: .whitespaces)
                guard t.hasPrefix(">") else { break }
                var rest = t.dropFirst()
                if rest.first == " " { rest = rest.dropFirst() }
                inner.append(String(rest))
                i += 1
            }
            var nested = Parser(lines: inner, depth: depth + 1)
            return .quote(nested.blocks())
        }

        // MARK: Lists

        /// (indent, ordered, number, content) of a list item line.
        static func listMarker(_ line: String) -> (indent: Int, ordered: Bool, number: Int?, content: String)? {
            let indent = indent(line)
            let t = line.trimmingCharacters(in: .whitespaces)
            if let first = t.first, "-*+".contains(first), t.count >= 2, t[t.index(after: t.startIndex)] == " " {
                if isRule(t) { return nil }
                return (indent, false, nil, String(t.dropFirst(2)))
            }
            let digits = t.prefix { $0.isNumber }
            guard !digits.isEmpty, digits.count <= 9 else { return nil }
            let after = t.dropFirst(digits.count)
            guard let mark = after.first, mark == "." || mark == ")", after.dropFirst().first == " " else { return nil }
            return (indent, true, Int(digits), String(after.dropFirst(2)))
        }

        mutating func list() -> MarkdownBlock {
            guard let first = Self.listMarker(lines[i]) else { return .paragraph(lines[i]) }
            let base = first.indent
            var items: [ListItem] = []
            var indents: [Int] = [base]
            while i < lines.count {
                let line = lines[i]
                if line.trimmingCharacters(in: .whitespaces).isEmpty {
                    // A blank line ends the list unless an item or an indented continuation follows.
                    if i + 1 < lines.count, let next = Self.listMarker(lines[i + 1]), next.indent >= base, next.ordered == first.ordered || next.indent > base {
                        i += 1
                        continue
                    }
                    break
                }
                if let marker = Self.listMarker(line), marker.indent >= base {
                    if marker.indent == base, marker.ordered != first.ordered { break }
                    while let last = indents.last, marker.indent < last { indents.removeLast() }
                    if let last = indents.last, marker.indent > last + 1 { indents.append(marker.indent) }
                    var text = marker.content
                    var checked: Bool?
                    if text.hasPrefix("[ ] ") || text == "[ ]" {
                        checked = false
                        text = String(text.dropFirst(3)).trimmingCharacters(in: .whitespaces)
                    } else if text.lowercased().hasPrefix("[x] ") || text.lowercased() == "[x]" {
                        checked = true
                        text = String(text.dropFirst(3)).trimmingCharacters(in: .whitespaces)
                    }
                    items.append(ListItem(depth: max(0, indents.count - 1), text: text, checked: checked, number: marker.number))
                    i += 1
                    continue
                }
                // A lazy continuation line joins the previous item; a fence or heading ends the list.
                let t = line.trimmingCharacters(in: .whitespaces)
                if Self.fence(t) != nil || Self.heading(t) != nil || t.hasPrefix(">") || Self.indent(line) < base { break }
                if !items.isEmpty { items[items.count - 1].text += "\n" + t }
                i += 1
            }
            return .list(ordered: first.ordered, start: first.number ?? 1, items: items)
        }

        // MARK: Tables

        static func cells(_ line: String) -> [String] {
            var t = line.trimmingCharacters(in: .whitespaces)
            if t.hasPrefix("|") { t.removeFirst() }
            if t.hasSuffix("|"), !t.hasSuffix("\\|") { t.removeLast() }
            var cells: [String] = []
            var current = ""
            var escaped = false
            var inCode = false
            for ch in t {
                if escaped {
                    current.append(ch)
                    escaped = false
                } else if ch == "\\" {
                    escaped = true
                    current.append(ch)
                } else if ch == "`" {
                    inCode.toggle()
                    current.append(ch)
                } else if ch == "|", !inCode {
                    cells.append(current.trimmingCharacters(in: .whitespaces))
                    current = ""
                } else {
                    current.append(ch)
                }
            }
            cells.append(current.trimmingCharacters(in: .whitespaces))
            return cells.map { $0.replacingOccurrences(of: "\\|", with: "|") }
        }

        static func isTableDivider(_ line: String) -> Bool {
            let parts = cells(line)
            guard !parts.isEmpty, line.contains("-") else { return false }
            return parts.allSatisfy { p in
                let s = p.trimmingCharacters(in: .whitespaces)
                return !s.isEmpty && s.allSatisfy { "-:".contains($0) } && s.contains("-")
            }
        }

        mutating func table() -> MarkdownBlock {
            let header = Self.cells(lines[i])
            let alignments: [TableAlignment] = Self.cells(lines[i + 1]).map { d in
                let s = d.trimmingCharacters(in: .whitespaces)
                if s.hasPrefix(":"), s.hasSuffix(":") { return .center }
                if s.hasSuffix(":") { return .trailing }
                return .leading
            }
            i += 2
            var rows: [[String]] = []
            while i < lines.count {
                let t = lines[i].trimmingCharacters(in: .whitespaces)
                guard !t.isEmpty, t.contains("|") else { break }
                var row = Self.cells(t)
                if row.count < header.count { row += Array(repeating: "", count: header.count - row.count) }
                rows.append(Array(row.prefix(header.count)))
                i += 1
            }
            return .table(header: header, alignments: Array(alignments.prefix(header.count)), rows: rows)
        }

        // MARK: Helpers

        static func indent(_ line: String) -> Int {
            var n = 0
            for ch in line {
                if ch == " " { n += 1 } else if ch == "\t" { n += 4 } else { break }
            }
            return n
        }

        static func dropIndent(_ line: String, upTo count: Int) -> String {
            var dropped = 0
            var index = line.startIndex
            while index < line.endIndex, dropped < count, line[index] == " " {
                dropped += 1
                index = line.index(after: index)
            }
            return String(line[index...])
        }
    }
}
