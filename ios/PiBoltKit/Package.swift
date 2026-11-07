// swift-tools-version:5.10
// The app's platform-independent parts, testable with `swift test` on the command-line tools alone:
// PiBoltProtocol (Noise_IK, pairing, framing, the remote backend, RPC, the session reducer) and PiBoltMarkdown (the
// block parser the transcript renders). The app target (ios/project.yml) links both.
import PackageDescription

let package = Package(
    name: "PiBoltKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "PiBoltProtocol", targets: ["PiBoltProtocol"]),
        .library(name: "PiBoltMarkdown", targets: ["PiBoltMarkdown"]),
    ],
    targets: [
        .target(name: "PiBoltProtocol"),
        .target(name: "PiBoltMarkdown"),
        .testTarget(
            name: "PiBoltProtocolTests",
            dependencies: ["PiBoltProtocol"],
            resources: [.copy("Resources/noise-ik-vectors.json")]
        ),
        .testTarget(name: "PiBoltMarkdownTests", dependencies: ["PiBoltMarkdown"]),
    ]
)
