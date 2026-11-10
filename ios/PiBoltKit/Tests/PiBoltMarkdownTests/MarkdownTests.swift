@testable import PiBoltMarkdown
import Testing

struct MarkdownTests {
    @Test func paragraphsHeadingsAndRules() {
        let blocks = Markdown.parse("# Title #\n\nFirst line\nsecond **line**.\n\n---\n## Next")
        #expect(blocks == [
            .heading(level: 1, text: "Title"),
            .paragraph("First line\nsecond **line**."),
            .rule,
            .heading(level: 2, text: "Next"),
        ])
    }

    @Test func fencedCodeKeepsItsTextAndLanguage() {
        let blocks = Markdown.parse("Run:\n```swift\nlet x = 1\n\n  print(x)\n```\nDone.")
        #expect(blocks == [
            .paragraph("Run:"),
            .code(language: "swift", text: "let x = 1\n\n  print(x)", closed: true),
            .paragraph("Done."),
        ])
    }

    @Test func aStreamingFenceIsAnOpenCodeBlock() {
        #expect(Markdown.parse("```ts\nconst a") == [.code(language: "ts", text: "const a", closed: false)])
        #expect(Markdown.parse("~~~\nplain\n~~~") == [.code(language: nil, text: "plain", closed: true)])
    }

    @Test func listsNestAndKeepTasks() {
        let blocks = Markdown.parse("- one\n- [x] done\n  - nested\n- [ ] todo\n\n1. first\n2. second\ncontinued")
        #expect(blocks == [
            .list(ordered: false, start: 1, items: [
                ListItem(depth: 0, text: "one"),
                ListItem(depth: 0, text: "done", checked: true),
                ListItem(depth: 1, text: "nested"),
                ListItem(depth: 0, text: "todo", checked: false),
            ]),
            .list(ordered: true, start: 1, items: [
                ListItem(depth: 0, text: "first", number: 1),
                ListItem(depth: 0, text: "second\ncontinued", number: 2),
            ]),
        ])
    }

    @Test func tablesSplitCellsButNotCodeSpans() {
        let blocks = Markdown.parse("| Name | Value |\n|:---|---:|\n| `a|b` | 1 |\n| c |\n\nAfter")
        #expect(blocks == [
            .table(header: ["Name", "Value"], alignments: [.leading, .trailing], rows: [["`a|b`", "1"], ["c", ""]]),
            .paragraph("After"),
        ])
    }

    @Test func quotesHoldBlocks() {
        #expect(Markdown.parse("> Note\n> - a\n\nx") == [.quote([.paragraph("Note"), .list(ordered: false, start: 1, items: [ListItem(depth: 0, text: "a")])]), .paragraph("x")])
    }

    @Test func aDashRuleIsNotAList() {
        #expect(Markdown.parse("- - -") == [.rule])
        #expect(Markdown.parse("-not a list") == [.paragraph("-not a list")])
    }

    @Test func deepQuotesStopNestingAtTheCap() {
        let blocks = Markdown.parse(String(repeating: ">", count: 50_000) + " x")
        var depth = 0
        var block = blocks.first
        while case let .quote(inner)? = block {
            depth += 1
            block = inner.first
        }
        #expect(depth == Markdown.maxQuoteDepth)
        guard case let .paragraph(text)? = block else {
            Issue.record("expected the rest as text")
            return
        }
        #expect(text.hasSuffix("> x"))
    }
}
