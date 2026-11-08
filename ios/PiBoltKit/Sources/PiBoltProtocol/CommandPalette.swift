import Foundation

/// Fuzzy matching for the `/` command list, as the Android app's `Fuzzy` (ui/session/features).
public enum Fuzzy {
    /// How well `query` matches `candidate` (higher is better), or nil when it does not match. Case-insensitive.
    public static func score(_ query: String, _ candidate: String) -> Int? {
        if query.isEmpty { return 0 }
        let q = Array(query.lowercased())
        let original = Array(candidate)
        let c = Array(candidate.lowercased())
        // Lowercasing can change the length (rare letters); fall back to the original for boundaries then.
        let boundarySource = original.count == c.count ? original : c
        if let at = indexOf(q, in: c) {
            var s = 1000 - at * 2 - (c.count - q.count)
            if at == 0 { s += 500 } else if isBoundary(boundarySource, at) { s += 250 }
            return s
        }
        var from = 0
        var previous = -2
        var s = 0
        for ch in q {
            guard let i = c[from...].firstIndex(of: ch) else { return nil }
            if i == previous + 1 { s += 15 } else if isBoundary(boundarySource, i) { s += 10 } else { s += 1 }
            s -= min(i - from, 10)
            previous = i
            from = i + 1
            if from > c.count { return nil }
        }
        return s - c.count / 8
    }

    private static func indexOf(_ needle: [Character], in haystack: [Character]) -> Int? {
        guard needle.count <= haystack.count else { return nil }
        for i in 0 ... (haystack.count - needle.count) where Array(haystack[i ..< i + needle.count]) == needle {
            return i
        }
        return nil
    }

    private static let boundaries: Set<Character> = ["/", "\\", "-", "_", ".", " ", ":"]

    static func isBoundary(_ s: [Character], _ i: Int) -> Bool {
        if i <= 0 { return true }
        let before = s[i - 1]
        return boundaries.contains(before) || (s[i].isUppercase && before.isLowercase)
    }

    /// `items` that match `query`, best first (stable for equal scores), at most `limit`.
    public static func rank<T>(_ query: String, _ items: [T], limit: Int, key: (T) -> String) -> [T] {
        if query.isEmpty { return Array(items.prefix(limit)) }
        let scored = items.enumerated().compactMap { i, item in score(query, key(item)).map { (item, $0, i) } }
        return scored.sorted { $0.1 != $1.1 ? $0.1 > $1.1 : $0.2 < $1.2 }.prefix(limit).map(\.0)
    }
}

/// The app's own slash commands: the terminal's built-ins that make sense from a phone. Remote control does not run
/// the terminal's built-ins from a phone, so the app runs these itself (a sheet, an RPC command); the host never sees
/// them.
public enum Builtin: String, CaseIterable, Sendable {
    case compact, name, model, thinking, copy

    public var description: String {
        switch self {
        case .compact: "Compact the context"
        case .name: "Rename this session"
        case .model: "Choose a model"
        case .thinking: "Set the thinking level"
        case .copy: "Copy the last reply"
        }
    }

    public var hint: String? {
        switch self {
        case .compact: "[instructions]"
        case .name: "<name>"
        case .model: "[search]"
        case .thinking: "[level]"
        case .copy: nil
        }
    }
}

public enum CommandSource: String, Sendable {
    case app, `extension`, prompt, skill
}

/// One row of the `/` list. `builtin` is set for the app's own commands.
public struct PaletteEntry: Equatable, Sendable, Identifiable {
    public var name: String
    public var description: String
    public var source: CommandSource
    public var hint: String?
    public var builtin: Builtin?

    public var id: String { "\(source.rawValue):\(name)" }
    /// What the composer gets when the row is picked.
    public var insert: String { "/\(name) " }
    public var label: String { "/\(name)" }
}

/// A built-in typed in full and sent, with the text after its name.
public struct BuiltinCall: Equatable, Sendable {
    public var builtin: Builtin
    public var args: String
}

public enum CommandPalette {
    /// At most this many rows; the list scrolls.
    public static let limit = 40

    /// Built-ins, then the host's commands (extensions, prompt templates, skills). A host command with a built-in's
    /// name wins: it is what the terminal would run.
    public static func entries(_ host: [SlashCommand]) -> [PaletteEntry] {
        let hostNames = Set(host.map(\.name))
        let builtins = Builtin.allCases.filter { !hostNames.contains($0.rawValue) }
            .map { PaletteEntry(name: $0.rawValue, description: $0.description, source: .app, hint: $0.hint, builtin: $0) }
        // The Android app's helper extension is not for people.
        let fromHost = host.filter { $0.name != "pibolt-app" && !$0.name.hasPrefix("pibolt-app:") }.map { c in
            PaletteEntry(name: c.name, description: c.description.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 } ?? "", source: sourceOf(c.source), hint: c.argumentHint)
        }
        return builtins + fromHost
    }

    public static func sourceOf(_ source: String) -> CommandSource {
        switch source {
        case "prompt": .prompt
        case "skill": .skill
        default: .extension
        }
    }

    /// The rows for `text`: shown while the composer holds only `/` and a name being typed (no space, one line). An
    /// empty name lists everything in `entries` order; otherwise fuzzy by name, then by description.
    public static func filter(_ text: String, _ entries: [PaletteEntry]) -> [PaletteEntry] {
        guard text.hasPrefix("/"), !text.contains(where: { $0 == " " || $0 == "\n" }) else { return [] }
        let query = String(text.dropFirst())
        if query.isEmpty { return Array(entries.prefix(limit)) }
        let byName = Fuzzy.rank(query, entries, limit: limit) { $0.name }
        if byName.count >= limit || query.count < 3 { return byName }
        let names = Set(byName.map(\.id))
        let lowered = query.lowercased()
        let rest = entries.filter { !names.contains($0.id) && $0.description.lowercased().contains(lowered) }
        return byName + Fuzzy.rank(query, rest, limit: limit - byName.count) { $0.description }
    }

    /// The usage line while the arguments of a known command are typed (`/compact focus on…` shows
    /// `/compact [instructions]`), or nil.
    public static func hintFor(_ text: String, _ entries: [PaletteEntry]) -> PaletteEntry? {
        guard text.hasPrefix("/"), !text.contains("\n"), let space = text.firstIndex(of: " ") else { return nil }
        let name = String(text[text.index(after: text.startIndex) ..< space])
        return entries.first { $0.name == name && $0.hint != nil }
    }

    /// The built-in `text` calls, or nil when it is a prompt for the host. A host command of the same name shadows a
    /// built-in.
    public static func parse(_ text: String, _ host: [SlashCommand]) -> BuiltinCall? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard t.hasPrefix("/") else { return nil }
        let end = t.firstIndex(where: { $0 == " " || $0 == "\n" }) ?? t.endIndex
        let name = String(t[t.index(after: t.startIndex) ..< end])
        if host.contains(where: { $0.name == name }) { return nil }
        guard let builtin = Builtin(rawValue: name) else { return nil }
        return BuiltinCall(builtin: builtin, args: String(t[end...]).trimmingCharacters(in: .whitespacesAndNewlines))
    }
}
