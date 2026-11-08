import Foundation
@testable import PiBoltMarkdown
import Testing

/// Reply text is untrusted: whatever markdown construct makes a link, only http, https and mailto links survive
/// `Markdown.inline` (PR #274 review: a code span inside a link kept a `shortcuts:` link live).
struct SafeLinksTests {
    private func links(_ text: String) -> [URL] {
        Markdown.inline(text).runs[\.link].compactMap { url, _ in url }
    }

    @Test(arguments: [
        // A code span inside the link: the case the review found.
        "[`run`](shortcuts://run-shortcut?name=x&input=text&text=rm)",
        "[`call`](tel:5551234)",
        "[`pair`](pibolt://pair#eyJ2IjoxfQ)",
        // Emphasis, nested emphasis and mixed spans inside the link.
        "[**open**](sms:5551234)",
        "[***deep*** _and_ `code`](shortcuts://x)",
        "[a **b `c` d** e](javascript:alert(1))",
        // Autolinks.
        "<shortcuts://run-shortcut?name=x>",
        "<tel:5551234>",
        "<pibolt://pair#eyJ2IjoxfQ>",
        // Reference links, with the definition on the next line.
        "[go][r]\n\n[r]: shortcuts://run-shortcut",
        "[r]\n\n[r]: tel:5551234",
        // Case, whitespace and scheme tricks.
        "[x](TEL:5551234)",
        "[x](ShortCuts://x)",
        "[x](https:shortcuts://x)",
        "[x](http:/relative)",
        "[x](/relative/path)",
        "[x](file:///etc/passwd)",
        "[x](data:text/html,hi)",
        // An image is a link too.
        "![img](shortcuts://x)",
    ])
    func refusedLinksAreText(payload: String) {
        let found = links(payload)
        #expect(found.allSatisfy(SafeLinks.isAllowed), "\(found)")
        #expect(found.isEmpty, "\(found)")
    }

    @Test func webAndMailLinksStayLiveWhateverTheirContent() {
        #expect(links("[`docs`](https://example.com/a)") == [URL(string: "https://example.com/a")!])
        #expect(links("[**site**](HTTP://example.com)").count == 1)
        #expect(links("<https://example.com>") == [URL(string: "https://example.com")!])
        #expect(links("[mail](mailto:a@example.com)") == [URL(string: "mailto:a@example.com")!])
        // A safe link next to a refused one: only the safe one stays.
        let mixed = links("[ok](https://example.com) and [`no`](tel:1)")
        #expect(mixed == [URL(string: "https://example.com")!])
    }

    /// Pins the Foundation behaviour the filter is for: the parser does make these links. Whether the code span inside
    /// a link also keeps its code intent differs between OS releases; the filter looks at link runs only.
    @Test func theParserItselfKeepsTheseLinks() throws {
        var options = AttributedString.MarkdownParsingOptions()
        options.interpretedSyntax = .inlineOnlyPreservingWhitespace
        for payload in ["[`run`](shortcuts://x)", "<tel:5551234>", "[***a***](pibolt://pair#x)"] {
            let raw = try AttributedString(markdown: payload, options: options)
            #expect(raw.runs[\.link].contains { url, _ in url != nil }, "\(payload)")
        }
    }

    @Test func theTextOfARefusedLinkIsKept() {
        let text = Markdown.inline("see [`run`](shortcuts://x) now")
        #expect(String(text.characters) == "see run now")
    }

    @Test func isAllowedChecksSchemeAndHost() {
        #expect(SafeLinks.isAllowed(URL(string: "https://example.com")!))
        #expect(SafeLinks.isAllowed(URL(string: "mailto:a@b.c")!))
        #expect(!SafeLinks.isAllowed(URL(string: "https:shortcuts://x")!))
        #expect(!SafeLinks.isAllowed(URL(string: "pibolt://pair#x")!))
        #expect(!SafeLinks.isAllowed(URL(string: "relative/path")!))
    }
}
