import PiBoltMarkdown
import SwiftUI

/// A reply's markdown: blocks from `PiBoltMarkdown`, inline formatting through `AttributedString(markdown:)`.
struct MarkdownView: View {
    var text: String
    var color: Color = Pi.ink1

    var body: some View {
        let blocks = Markdown.parse(text)
        VStack(alignment: .leading, spacing: 12) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                BlockView(block: block, color: color)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Inline markdown of reply text: `Markdown.inline` parses it and keeps only web and mail links live (`SafeLinks`);
/// code spans are styled here.
func inline(_ text: String) -> AttributedString {
    var parsed = Markdown.inline(text)
    for run in parsed.runs where run.inlinePresentationIntent?.contains(.code) == true {
        parsed[run.range].font = .system(size: 15, design: .monospaced)
        parsed[run.range].backgroundColor = Pi.surface2
    }
    return parsed
}

private struct BlockView: View {
    var block: MarkdownBlock
    var color: Color

    var body: some View {
        switch block {
        case let .heading(level, text):
            Text(inline(text))
                .font(.system(size: level == 1 ? 22 : level == 2 ? 19 : 17, weight: .semibold))
                .foregroundStyle(Pi.ink1)
                .padding(.top, 4)
        case let .paragraph(text):
            Text(inline(text)).font(.system(size: 16)).foregroundStyle(color).lineSpacing(3).textSelection(.enabled)
        case let .code(language, text, _):
            CodeBlock(language: language, text: text)
        case let .list(ordered, _, items):
            VStack(alignment: .leading, spacing: 6) {
                ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Group {
                            if let checked = item.checked {
                                Image(systemName: checked ? "checkmark.square.fill" : "square").foregroundStyle(checked ? Pi.success : Pi.ink3)
                            } else if ordered {
                                Text("\(item.number ?? index + 1).").foregroundStyle(Pi.ink3).monospacedDigit()
                            } else {
                                Text(item.depth == 0 ? "•" : "◦").foregroundStyle(Pi.ink3)
                            }
                        }
                        .font(.system(size: 16))
                        Text(inline(item.text)).font(.system(size: 16)).foregroundStyle(color).lineSpacing(3)
                    }
                    .padding(.leading, CGFloat(item.depth) * 18)
                }
            }
        case let .quote(inner):
            HStack(spacing: 12) {
                Rectangle().fill(Pi.ink4).frame(width: 3)
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(inner.enumerated()), id: \.offset) { _, b in BlockView(block: b, color: Pi.ink2) }
                }
            }
            .fixedSize(horizontal: false, vertical: true)
        case let .table(header, alignments, rows):
            TableBlock(header: header, alignments: alignments, rows: rows)
        case .rule:
            Rectangle().fill(Pi.lineStrong).frame(height: 1).padding(.vertical, 4)
        }
    }
}

private struct CodeBlock: View {
    var language: String?
    var text: String
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(language ?? "code").font(.system(size: 12, weight: .medium)).foregroundStyle(Pi.ink3)
                Spacer()
                Button {
                    UIPasteboard.general.string = text
                    copied = true
                    Task {
                        try? await Task.sleep(nanoseconds: 3_000_000_000)
                        copied = false
                    }
                } label: {
                    Image(systemName: copied ? "checkmark" : "doc.on.doc").font(.system(size: 13)).foregroundStyle(Pi.ink3)
                        .frame(width: 32, height: 28)
                }
                .accessibilityLabel(copied ? "Copied" : "Copy code")
            }
            .padding(.leading, 12)
            .padding(.trailing, 4)
            .background(Pi.surface3.opacity(0.6))
            ScrollView(.horizontal, showsIndicators: false) {
                Text(text).font(Pi.mono).foregroundStyle(Pi.ink1).textSelection(.enabled).padding(12)
            }
        }
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Pi.surface2))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Pi.line, lineWidth: 1))
    }
}

private struct TableBlock: View {
    var header: [String]
    var alignments: [TableAlignment]
    var rows: [[String]]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 8) {
                GridRow {
                    ForEach(Array(header.enumerated()), id: \.offset) { i, cell in
                        Text(inline(cell)).font(.system(size: 15, weight: .semibold)).foregroundStyle(Pi.ink1)
                            .gridColumnAlignment(alignment(i))
                    }
                }
                Divider().overlay(Pi.lineStrong)
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    GridRow {
                        ForEach(Array(row.enumerated()), id: \.offset) { _, cell in
                            Text(inline(cell)).font(.system(size: 15)).foregroundStyle(Pi.ink2)
                        }
                    }
                }
            }
            .padding(12)
        }
        .background(RoundedRectangle(cornerRadius: 12).fill(Pi.surface1))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Pi.line, lineWidth: 1))
    }

    private func alignment(_ column: Int) -> HorizontalAlignment {
        switch column < alignments.count ? alignments[column] : .leading {
        case .leading: .leading
        case .center: .center
        case .trailing: .trailing
        }
    }
}
