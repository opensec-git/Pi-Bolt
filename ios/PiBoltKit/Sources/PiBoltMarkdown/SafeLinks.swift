import Foundation

/// Reply text is not trusted: a model can be steered into writing any markdown by a file, a page or a tool's output it
/// read. Only web and mail links stay live, as in the Android app; any other scheme (`tel:`, `sms:`, `shortcuts:`,
/// another app's deep link, this app's own `pibolt://` pairing link) or a link without a scheme is plain text.
public enum SafeLinks {
    public static let allowedSchemes: Set<String> = ["http", "https", "mailto"]

    public static func isAllowed(_ url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased(), allowedSchemes.contains(scheme) else { return false }
        // A web link needs a host: `https:shortcuts://…` or `http:/x` is no web page.
        if scheme != "mailto" { return url.host?.isEmpty == false }
        return true
    }

    /// Removes every link attribute whose URL is not allowed, whatever construct produced it (an inline link, an
    /// autolink, a reference link, a code span or emphasis inside a link), by looking at the link runs alone.
    public static func sanitize(_ text: inout AttributedString) {
        let refused = text.runs[\.link].compactMap { url, range -> Range<AttributedString.Index>? in
            guard let url, !isAllowed(url) else { return nil }
            return range
        }
        for range in refused { text[range].link = nil }
    }
}

extension Markdown {
    /// Inline markdown (emphasis, code spans, links) of untrusted text, links filtered by `SafeLinks`; the text as is
    /// when it does not parse. The app styles the result (code spans) and renders it.
    public static func inline(_ text: String) -> AttributedString {
        var options = AttributedString.MarkdownParsingOptions()
        options.interpretedSyntax = .inlineOnlyPreservingWhitespace
        options.failurePolicy = .returnPartiallyParsedIfPossible
        guard var parsed = try? AttributedString(markdown: text, options: options) else { return AttributedString(text) }
        SafeLinks.sanitize(&parsed)
        return parsed
    }
}
