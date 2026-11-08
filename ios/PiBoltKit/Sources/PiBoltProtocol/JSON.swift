import Foundation

/// A JSON value as it crosses the wire. Lenient readers mirror the Android protocol's `Json.kt`: a field of the wrong type
/// reads as absent, never as an error.
public enum JSON: Equatable, Sendable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSON])
    case object([String: JSON])

    public struct ParseError: Error, CustomStringConvertible {
        public let description: String
    }

    // MARK: Parsing

    public static func parse(_ data: Data) throws -> JSON {
        let any: Any
        do {
            any = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        } catch {
            throw ParseError(description: "Malformed JSON: \(error.localizedDescription)")
        }
        return from(any)
    }

    public static func parse(_ text: String) throws -> JSON {
        try parse(Data(text.utf8))
    }

    /// An object from [text], or nil when it is not valid JSON or not an object.
    public static func object(_ text: String) -> JSON? {
        guard let value = try? parse(text), case .object = value else { return nil }
        return value
    }

    private static func from(_ any: Any) -> JSON {
        switch any {
        case let s as String:
            return .string(s)
        case let n as NSNumber:
            // NSNumber hides booleans: the CF type tells them apart from 0 and 1.
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return .bool(n.boolValue) }
            return .number(n.doubleValue)
        case let a as [Any]:
            return .array(a.map(from))
        case let d as [String: Any]:
            var out = [String: JSON](minimumCapacity: d.count)
            for (k, v) in d { out[k] = from(v) }
            return .object(out)
        default:
            return .null
        }
    }

    // MARK: Writing

    /// Compact JSON text. Object keys are written in sorted order, so equal values always serialize the same.
    public var text: String {
        var out = ""
        write(into: &out)
        return out
    }

    public var data: Data { Data(text.utf8) }

    private func write(into out: inout String) {
        switch self {
        case .null: out += "null"
        case let .bool(b): out += b ? "true" : "false"
        case let .number(n):
            if n.isFinite, n == n.rounded(), abs(n) < 9.007_199_254_740_992e15 {
                out += String(Int64(n))
            } else if n.isFinite {
                out += "\(n)"
            } else {
                out += "null"
            }
        case let .string(s): JSON.writeString(s, into: &out)
        case let .array(a):
            out += "["
            for (i, v) in a.enumerated() {
                if i > 0 { out += "," }
                v.write(into: &out)
            }
            out += "]"
        case let .object(o):
            out += "{"
            for (i, k) in o.keys.sorted().enumerated() {
                if i > 0 { out += "," }
                JSON.writeString(k, into: &out)
                out += ":"
                o[k]!.write(into: &out)
            }
            out += "}"
        }
    }

    private static func writeString(_ s: String, into out: inout String) {
        out += "\""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            default:
                if scalar.value < 0x20 {
                    out += String(format: "\\u%04x", scalar.value)
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        out += "\""
    }

    // MARK: Lenient access

    public subscript(key: String) -> JSON? {
        if case let .object(o) = self { return o[key] }
        return nil
    }

    public var objectValue: [String: JSON]? {
        if case let .object(o) = self { return o }
        return nil
    }

    public var arrayValue: [JSON]? {
        if case let .array(a) = self { return a }
        return nil
    }

    public var stringValue: String? {
        if case let .string(s) = self { return s }
        return nil
    }

    public var isObject: Bool { objectValue != nil }

    public func obj(_ key: String) -> JSON? {
        guard let v = self[key], case .object = v else { return nil }
        return v
    }

    public func arr(_ key: String) -> [JSON]? { self[key]?.arrayValue }

    public func str(_ key: String) -> String? { self[key]?.stringValue }

    public func double(_ key: String) -> Double? {
        if case let .number(n)? = self[key] { return n }
        return nil
    }

    public func long(_ key: String) -> Int64? {
        guard let n = double(key), n.isFinite, abs(n) < 9.2e18 else { return nil }
        return Int64(n)
    }

    public func int(_ key: String) -> Int? {
        guard let n = long(key), n >= Int64(Int32.min), n <= Int64(Int32.max) else { return nil }
        return Int(n)
    }

    public func bool(_ key: String) -> Bool? {
        if case let .bool(b)? = self[key] { return b }
        return nil
    }

    public var type: String? { str("type") }

    /// The strings of array [key]; other elements are skipped.
    public func strings(_ key: String) -> [String] {
        arr(key)?.compactMap(\.stringValue) ?? []
    }

    /// Text of a primitive, as Kotlin's `JsonPrimitive.content`: a string as is, a number or boolean as written.
    public var primitiveText: String? {
        switch self {
        case let .string(s): return s
        case let .bool(b): return b ? "true" : "false"
        case .number: return text
        default: return nil
        }
    }

    /// A copy of this object with [key] set (or removed when [value] is nil).
    public func with(_ key: String, _ value: JSON?) -> JSON {
        var o = objectValue ?? [:]
        o[key] = value
        return .object(o)
    }

    /// Pretty text for people (tool arguments): a string as is, anything else as indented JSON.
    public var displayText: String {
        if case let .string(s) = self { return s }
        guard let data = try? JSONSerialization.data(withJSONObject: foundationValue, options: [.prettyPrinted, .sortedKeys, .fragmentsAllowed, .withoutEscapingSlashes]),
              let s = String(data: data, encoding: .utf8)
        else { return text }
        return s
    }

    private var foundationValue: Any {
        switch self {
        case .null: return NSNull()
        case let .bool(b): return b
        case let .number(n): return n == n.rounded() && abs(n) < 9e15 ? Int64(n) as Any : n
        case let .string(s): return s
        case let .array(a): return a.map(\.foundationValue)
        case let .object(o): return o.mapValues(\.foundationValue)
        }
    }
}

extension JSON: ExpressibleByStringLiteral, ExpressibleByBooleanLiteral, ExpressibleByIntegerLiteral, ExpressibleByFloatLiteral,
    ExpressibleByArrayLiteral, ExpressibleByDictionaryLiteral, ExpressibleByNilLiteral
{
    public init(stringLiteral value: String) { self = .string(value) }
    public init(booleanLiteral value: Bool) { self = .bool(value) }
    public init(integerLiteral value: Int) { self = .number(Double(value)) }
    public init(floatLiteral value: Double) { self = .number(value) }
    public init(arrayLiteral elements: JSON...) { self = .array(elements) }
    public init(dictionaryLiteral elements: (String, JSON)...) {
        var o = [String: JSON]()
        for (k, v) in elements { o[k] = v }
        self = .object(o)
    }

    public init(nilLiteral: ()) { self = .null }
}
