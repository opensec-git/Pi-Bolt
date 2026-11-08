import CoreGraphics
import Foundation
import ImageIO
@testable import PiBoltProtocol
import Testing
import UniformTypeIdentifiers

/// The Android `CommandPaletteTest` (palette and fuzzy parts) on the iOS built-ins.
struct CommandPaletteTests {
    private let host = [
        SlashCommand(name: "fix-tests", description: "Fix failing tests", source: "prompt"),
        SlashCommand(name: "skill:pdf", description: "Read PDF files", source: "skill"),
        SlashCommand(name: "todo", description: "Show the todo list", source: "extension", argumentHint: "[filter]"),
        SlashCommand(name: "pibolt-app:ask", description: "internal", source: "extension"),
    ]

    @Test func hostCommandsKeepTheirSourceAndHint() {
        let entries = CommandPalette.entries(host)
        #expect(entries.first { $0.name == "fix-tests" }?.source == .prompt)
        #expect(entries.first { $0.name == "skill:pdf" }?.source == .skill)
        #expect(entries.first { $0.name == "todo" }?.hint == "[filter]")
        #expect(!entries.contains { $0.name.hasPrefix("pibolt-app") })
        #expect(Set(entries.compactMap(\.builtin)) == Set(Builtin.allCases))
    }

    @Test func aHostCommandShadowsABuiltinOfTheSameName() {
        let more = host + [SlashCommand(name: "compact", description: "Custom compaction", source: "extension")]
        let entries = CommandPalette.entries(more)
        #expect(entries.filter { $0.name == "compact" }.count == 1)
        #expect(entries.first { $0.name == "compact" }?.source == .extension)
        #expect(CommandPalette.parse("/compact now", more) == nil)
    }

    @Test func filterIsFuzzyAndClosesOnASpace() {
        let entries = CommandPalette.entries(host)
        #expect(CommandPalette.filter("/", entries).count == min(entries.count, CommandPalette.limit))
        #expect(CommandPalette.filter("/ft", entries).first?.name == "fix-tests")
        #expect(CommandPalette.filter("/comp", entries).first?.name == "compact")
        #expect(CommandPalette.filter("/compact ", entries).isEmpty)
        #expect(CommandPalette.filter("hello", entries).isEmpty)
        #expect(CommandPalette.filter("/zzzz", entries).isEmpty)
        #expect(CommandPalette.filter("/failing", entries).contains { $0.name == "fix-tests" })
    }

    @Test func hintShowsWhileArgumentsAreTyped() {
        let entries = CommandPalette.entries(host)
        #expect(CommandPalette.hintFor("/compact focus on", entries)?.hint == "[instructions]")
        #expect(CommandPalette.hintFor("/todo ", entries)?.hint == "[filter]")
        #expect(CommandPalette.hintFor("/compact", entries) == nil)
        #expect(CommandPalette.hintFor("/fix-tests now", entries) == nil)
    }

    @Test func parseFindsBuiltinsAndTheirArguments() {
        #expect(CommandPalette.parse("/compact keep the API notes", host) == BuiltinCall(builtin: .compact, args: "keep the API notes"))
        #expect(CommandPalette.parse("  /thinking  ", host) == BuiltinCall(builtin: .thinking, args: ""))
        #expect(CommandPalette.parse("/name Fix the parser", host)?.args == "Fix the parser")
        #expect(CommandPalette.parse("/fix-tests", host) == nil)
        #expect(CommandPalette.parse("/new", host) == nil)
        #expect(CommandPalette.parse("please /compact", host) == nil)
        #expect(CommandPalette.parse("/compacted", host) == nil)
    }

    @Test func fuzzyPrefersPrefixesBoundariesAndShortNames() throws {
        let a = try #require(Fuzzy.score("comp", "compact"))
        let b = try #require(Fuzzy.score("comp", "skill:compose"))
        #expect(a > b)
        // Scattered: a character at a word start counts more than one mid-word.
        #expect(try #require(Fuzzy.score("ft", "fix-tests")) > (try #require(Fuzzy.score("ft", "fixatests"))))
        #expect(Fuzzy.score("xyz", "compact") == nil)
        #expect(Fuzzy.rank("session", ["skill:session-notes", "session", "new"], limit: 5) { $0 } == ["session", "skill:session-notes"])
        #expect(Fuzzy.score("", "anything") == 0)
    }
}

/// The Android `AttachmentsTest` sizing cases, and the encoder on real images.
struct ImageEncodingTests {
    @Test func imagesScaleToTheLongEdgeAndNeverUp() {
        #expect(ImageSizing.target(4000, 3000) == (2000, 1500))
        #expect(ImageSizing.target(3024, 5376) == (1125, 2000))
        #expect(ImageSizing.target(800, 600) == (800, 600))
        #expect(ImageSizing.target(10000, 2) == (2000, 1))
        #expect(ImageSizing.target(4000, 3000, maxEdge: ImageSizing.thumbEdge) == (192, 144))
    }

    @Test func sizeLimitsAndFormat() {
        #expect(ImageSizing.base64Size(1) == 4)
        #expect(ImageSizing.base64Size(3) == 4)
        #expect(ImageSizing.base64Size(4) == 8)
        #expect(ImageSizing.fits(3_538_944))
        #expect(!ImageSizing.fits(3_538_945))
        #expect(ImageSizing.keepPng(hasAlpha: true, pngBytes: 400_000))
        #expect(!ImageSizing.keepPng(hasAlpha: true, pngBytes: 2_000_000))
        #expect(!ImageSizing.keepPng(hasAlpha: false, pngBytes: 10000))
        #expect(ImageSizing.slotsLeft(0) == 4)
        #expect(ImageSizing.slotsLeft(5) == 0)
    }

    private func image(_ width: Int, _ height: Int, alpha: Bool) -> CGImage {
        let info = alpha ? CGImageAlphaInfo.premultipliedLast.rawValue : CGImageAlphaInfo.noneSkipLast.rawValue
        let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: info)!
        context.setFillColor(CGColor(red: 0.2, green: 0.5, blue: 0.8, alpha: alpha ? 0.5 : 1))
        context.fill(CGRect(x: 0, y: 0, width: width / 2, height: height))
        return context.makeImage()!
    }

    private func file(_ image: CGImage, _ type: UTType, properties: [CFString: Any] = [:]) -> Data {
        let out = NSMutableData()
        let destination = CGImageDestinationCreateWithData(out, type.identifier as CFString, 1, nil)!
        CGImageDestinationAddImage(destination, image, properties as CFDictionary)
        CGImageDestinationFinalize(destination)
        return out as Data
    }

    private func properties(_ base64: String) -> [CFString: Any] {
        let source = CGImageSourceCreateWithData(Data(base64Encoded: base64)! as CFData, nil)!
        return CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] ?? [:]
    }

    @Test func aLargePhotoIsScaledAndLosesItsLocation() throws {
        let gps: [CFString: Any] = [kCGImagePropertyGPSLatitude: 51.5, kCGImagePropertyGPSLongitude: 0.12, kCGImagePropertyGPSLatitudeRef: "N"]
        let photo = file(image(4000, 3000, alpha: false), .jpeg, properties: [kCGImagePropertyGPSDictionary: gps])
        #expect(CGImageSourceCopyPropertiesAtIndex(CGImageSourceCreateWithData(photo as CFData, nil)!, 0, nil).map { ($0 as NSDictionary)[kCGImagePropertyGPSDictionary] != nil } == true)
        let encoded = try ImageEncoder.encode(photo).get()
        #expect(encoded.mimeType == "image/jpeg")
        let props = properties(encoded.base64)
        #expect(props[kCGImagePropertyPixelWidth] as? Int == 2000)
        #expect(props[kCGImagePropertyPixelHeight] as? Int == 1500)
        #expect(props[kCGImagePropertyGPSDictionary] == nil)
    }

    @Test func aSmallTransparentImageStaysPng() throws {
        let encoded = try ImageEncoder.encode(file(image(300, 200, alpha: true), .png)).get()
        #expect(encoded.mimeType == "image/png")
        #expect(properties(encoded.base64)[kCGImagePropertyPixelWidth] as? Int == 300)
    }

    @Test func notAnImageIsRefused() {
        #expect(ImageEncoder.encode(Data("hello".utf8)) == .failure(.unreadable))
    }
}

/// Local notifications: which events raise one, and what a background refresh owes after a reconnect.
struct SessionAlertTests {
    private func run(_ state: SessionState, _ lines: String...) -> SessionState {
        lines.reduce(state) { SessionReducer.reduce($0, SessionReducer.parse($1)) }
    }

    private func hello(running: Bool, session: String = "s1", messages: String = "", dialogs: String = "") -> String {
        #"{"type":"remote_hello","host":{"name":"mac","version":"1.1.5","cwd":"/w"},"state":{"isStreaming":\#(running),"sessionId":"\#(session)"},"messages":[\#(messages)],"dialogs":[\#(dialogs)]}"#
    }

    private let reply = #"{"role":"assistant","content":[{"type":"text","text":"\n  All tests pass now.\nDetails follow."}],"stopReason":"stop"}"#
    private let dialog = #"{"type":"extension_ui_request","id":"d1","method":"confirm","title":"Run rm?","message":"rm -rf build"}"#

    @Test func aRunThatEndsRaisesFinishedWithTheFirstLineOfTheReply() {
        let before = run(SessionState(), hello(running: true), #"{"type":"message_end","message":\#(reply)}"#)
        let alerts = SessionAlerts.alerts(before: before, event: ["type": "agent_settled"], hostId: "h", hostName: "studio")
        #expect(alerts == [SessionAlert(kind: .finished, hostId: "h", title: "Pi finished on studio", body: "All tests pass now.", sessionId: "s1")])
        #expect(alerts.first?.id == "h.settled")
        // Not running: nothing ended.
        #expect(SessionAlerts.alerts(before: run(SessionState(), hello(running: false)), event: ["type": "agent_settled"], hostId: "h", hostName: "studio").isEmpty)
        let stopped = SessionAlerts.alerts(before: run(SessionState(), hello(running: true)), event: ["type": "agent_settled", "aborted": true], hostId: "h", hostName: "studio")
        #expect(stopped.first?.title == "Pi stopped on studio")
        #expect(stopped.first?.body == "Tap to see the result")
    }

    @Test func aDialogRaisesNeedsAnswer() {
        let alerts = SessionAlerts.alerts(before: run(SessionState(), hello(running: true)), event: SessionReducer.parse(dialog), hostId: "h", hostName: "studio")
        #expect(alerts.map(\.kind) == [.needsAnswer])
        #expect(alerts.first?.body == "Run rm?\nrm -rf build")
        #expect(alerts.first?.id == "h.answer")
        // A notify is no dialog.
        #expect(SessionAlerts.alerts(before: SessionState(), event: ["type": "extension_ui_request", "id": "n", "method": "notify", "message": "hi"], hostId: "h", hostName: "s").isEmpty)
    }

    @Test func aWatchSeesWhatChangedWhileTheAppWasAway() throws {
        let running = run(SessionState(), hello(running: true))
        let watch = try #require(AlertWatch.of(running, hostId: "h", hostName: "studio"))
        // The run ended meanwhile, and a dialog opened.
        let after = SessionReducer.parse(hello(running: false, messages: reply, dialogs: dialog))
        let alerts = SessionAlerts.missed(before: watch.before, hello: after, hostId: "h", hostName: "studio")
        #expect(alerts.map(\.kind) == [.finished, .needsAnswer])
        #expect(alerts.first?.body == "All tests pass now.")
        // Still waiting on that dialog: watched further, and the same dialog is not announced twice.
        let next = try #require(watch.after(after))
        #expect(next.dialogIds == ["d1"])
        #expect(SessionAlerts.missed(before: next.before, hello: after, hostId: "h", hostName: "studio").isEmpty)
        // Another session on the computer now: nothing to say about this one.
        #expect(SessionAlerts.missed(before: watch.before, hello: SessionReducer.parse(hello(running: false, session: "s2")), hostId: "h", hostName: "studio").isEmpty)
        #expect(watch.after(SessionReducer.parse(hello(running: false, session: "s2"))) == nil)
        // Idle with nothing open: nothing to watch.
        #expect(AlertWatch.of(run(SessionState(), hello(running: false)), hostId: "h", hostName: "studio") == nil)
    }

    /// A 1.1.6 computer's mobile UI kit (docs/mobile-ui.md): this app does not render views before 0.2.0, so views,
    /// view updates and sheets change nothing and raise no alert.
    @Test func mobileUIKitTrafficIsIgnored() {
        let viewHello = #"{"type":"remote_hello","host":{"name":"mac","version":"1.1.6","cwd":"/w"},"features":["subagent_stream","views"],"views":[{"viewKey":"deploy","slot":"panel","rev":1,"view":{"v":1,"title":"Deploy","body":[]}}],"state":{"isStreaming":true,"sessionId":"s1"},"messages":[],"dialogs":[{"type":"extension_ui_request","id":"u3","method":"showView","viewKey":"sheet:1","rev":1,"view":{"v":1,"title":"Settings","body":[]}}]}"#
        let base = run(SessionState(), hello(running: true))
        let s = run(base, viewHello)
        #expect(s.dialogs.isEmpty)
        #expect(s.host?.features.contains("views") == true)
        #expect(SessionReducer.missedWhileAway(base, SessionReducer.parse(viewHello)).isEmpty)
        let traffic = [
            #"{"type":"extension_ui_request","id":"u1","method":"setView","viewKey":"deploy","slot":"panel","rev":2,"view":{"v":1,"title":"Deploy","body":[]}}"#,
            #"{"type":"extension_ui_request","id":"u2","method":"updateView","viewKey":"deploy","rev":3,"ops":[{"op":"set","id":"bar","props":{"value":0.5}}]}"#,
            #"{"type":"extension_ui_request","id":"u4","method":"showView","viewKey":"sheet:2","rev":1,"view":{"v":1,"title":"More","body":[]}}"#,
            #"{"type":"extension_ui_dismissed","id":"u4"}"#,
        ]
        for line in traffic {
            let event = SessionReducer.parse(line)
            #expect(SessionReducer.reduce(s, event) == s, "\(line)")
            #expect(SessionAlerts.alerts(before: s, event: event, hostId: "h", hostName: "mac").isEmpty)
        }
    }

    @Test func aWatchSurvivesTheDisk() throws {
        let watch = AlertWatch(hostId: "h", hostName: "studio", sessionId: "s1", running: true, dialogIds: ["d1"], savedAt: Date(timeIntervalSince1970: 1_800_000_000))
        let data = try JSONEncoder().encode(watch)
        #expect(try JSONDecoder().decode(AlertWatch.self, from: data) == watch)
    }
}

struct RecentsTests {
    private func state(_ hello: String, _ lines: String...) -> SessionState {
        ([hello] + lines).reduce(SessionState()) { SessionReducer.reduce($0, SessionReducer.parse($1)) }
    }

    private func hello(name: String? = nil, session: String = "s1", file: String = "/home/u/.pi/sessions/a.jsonl", messages: String = "") -> String {
        let n = name.map { #","sessionName":"\#($0)""# } ?? ""
        return #"{"type":"remote_hello","host":{"name":"mac","version":"1","cwd":"/work/app","service":true},"state":{"sessionId":"\#(session)","sessionFile":"\#(file)"\#(n)},"messages":[\#(messages)]}"#
    }

    @Test func anEntryIsTitledByNameThenFirstPrompt() throws {
        let named = try #require(RecentSession.of(state(hello(name: "Fix parser")), hostId: "h", hostName: "mac"))
        #expect(named.title == "Fix parser")
        #expect(named.sessionFile == "/home/u/.pi/sessions/a.jsonl")
        #expect(named.service)
        #expect(named.folder == "app")
        let prompted = try #require(RecentSession.of(state(hello(messages: #"{"role":"user","content":"why does\nthe build fail"}"#)), hostId: "h", hostName: "mac"))
        #expect(prompted.title == "why does")
        #expect(RecentSession.of(state(hello()), hostId: "h", hostName: "mac")?.title == "New session")
        #expect(RecentSession.of(SessionState(), hostId: "h", hostName: "mac") == nil)
    }

    @Test func recordingMovesTheSessionFirstAndCaps() throws {
        var list: [RecentSession] = []
        for i in 0 ..< 25 {
            list = Recents.record(list, try #require(RecentSession.of(state(hello(session: "s\(i)")), hostId: "h", hostName: "mac")))
        }
        #expect(list.count == Recents.cap)
        #expect(list.first?.sessionId == "s24")
        list = Recents.record(list, try #require(RecentSession.of(state(hello(name: "again", session: "s10")), hostId: "h", hostName: "mac")))
        #expect(list.first?.sessionId == "s10")
        #expect(list.first?.title == "again")
        #expect(list.filter { $0.sessionId == "s10" }.count == 1)
        #expect(Recents.removing(list, hostId: "h").isEmpty)
    }
}
