import PiBoltProtocol
import SwiftUI

/// A unified diff drawn row by row: file and hunk headers, then lines with old and new numbers, a `+` / `−` sign (so
/// the change does not rely on colour alone) and a soft green or red fill. Scrolls both ways; long diffs stop at
/// `maxRows` with a note.
struct DiffBody: View {
    var text: String
    static let maxRows = 4000

    var body: some View {
        let all = DiffLines.parse(text)
        let rows = Array(all.prefix(Self.maxRows))
        let width = gutterWidth(rows)
        GeometryReader { proxy in
            ScrollView([.vertical, .horizontal]) {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                        DiffRowView(row: row, gutter: width, minWidth: proxy.size.width)
                    }
                    if all.count > rows.count {
                        ReviewNote(systemName: "scissors", text: "\(all.count - rows.count) more lines are not shown here.")
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

    /// Digits of the largest old and new line numbers.
    private func gutterWidth(_ rows: [DiffRow]) -> (Int, Int) {
        var old = 0
        var new = 0
        for r in rows {
            if let o = r.old { old = max(old, String(o).count) }
            if let n = r.new { new = max(new, String(n).count) }
        }
        return (old, new)
    }
}

private struct DiffRowView: View {
    var row: DiffRow
    var gutter: (Int, Int)
    /// The screen's width: a short row's fill still reaches across it.
    var minWidth: CGFloat

    var body: some View {
        content
            .frame(minWidth: minWidth, alignment: .leading)
            .background(fill)
    }

    @ViewBuilder private var content: some View {
        switch row.kind {
        case .file:
            HStack(spacing: 8) {
                Image(systemName: "doc.text").font(.system(size: 12, weight: .semibold)).foregroundStyle(Pi.ink2)
                Text(row.text).font(.system(size: 13, weight: .semibold, design: .monospaced)).foregroundStyle(Pi.ink1)
            }
            .fixedSize()
            .padding(.horizontal, 14)
            .padding(.top, 14)
            .padding(.bottom, 6)
        case .meta, .other:
            line(row.text, color: Pi.ink4)
        case .hunk:
            Text(row.text)
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(Pi.ink3)
                .fixedSize()
                .padding(.horizontal, 14)
                .padding(.vertical, 4)
        case .noNewline:
            line(row.text, color: Pi.ink4)
        case .added, .removed, .context:
            HStack(spacing: 0) {
                Text(numbers)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(Pi.ink4)
                    .padding(.leading, 11)
                    .padding(.trailing, 4)
                Text(sign)
                    .font(.system(size: 12, weight: .semibold, design: .monospaced))
                    .foregroundStyle(row.kind == .added ? Pi.success : Pi.danger)
                    .frame(width: 14)
                Text(row.text.isEmpty ? " " : row.text)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(row.kind == .context ? Pi.ink2 : Pi.ink1)
                    .padding(.leading, 4)
                    .padding(.trailing, 14)
            }
            .fixedSize()
            .overlay(alignment: .leading) { Rectangle().fill(bar).frame(width: 3) }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(accessibility)
        }
    }

    private func line(_ text: String, color: Color) -> some View {
        Text(text.isEmpty ? " " : text)
            .font(.system(size: 12, design: .monospaced))
            .foregroundStyle(color)
            .fixedSize()
            .padding(.horizontal, 14)
            .padding(.vertical, 1)
    }

    private var sign: String {
        switch row.kind {
        case .added: "+"
        case .removed: "−"
        default: ""
        }
    }

    private var numbers: String {
        func pad(_ n: Int?, _ width: Int) -> String {
            let s = n.map(String.init) ?? ""
            return String(repeating: " ", count: max(0, width - s.count)) + s
        }
        var parts: [String] = []
        if gutter.0 > 0 { parts.append(pad(row.old, gutter.0)) }
        if gutter.1 > 0 { parts.append(pad(row.new, gutter.1)) }
        return parts.joined(separator: " ")
    }

    private var fill: Color {
        switch row.kind {
        case .added: Pi.success.opacity(0.10)
        case .removed: Pi.danger.opacity(0.10)
        case .hunk: Pi.surface2
        default: Color.clear
        }
    }

    private var bar: Color {
        switch row.kind {
        case .added: Pi.success.opacity(0.6)
        case .removed: Pi.danger.opacity(0.7)
        default: Color.clear
        }
    }

    private var accessibility: String {
        switch row.kind {
        case .added: "Added line \(row.new ?? 0): \(row.text)"
        case .removed: "Removed line \(row.old ?? 0): \(row.text)"
        default: "Line \(row.new ?? 0): \(row.text)"
        }
    }
}
