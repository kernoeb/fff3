// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "Fff3",
    platforms: [.macOS(.v14)],
    targets: [
        // Protocol and music analysis, with no app code, so the bench runs exactly what the app runs.
        .target(name: "Fff3Core", path: "Sources/Fff3Core"),
        .executableTarget(name: "Fff3", dependencies: ["Fff3Core"], path: "Sources/Fff3"),
        .executableTarget(name: "Fff3Bench", dependencies: ["Fff3Core"], path: "Sources/Fff3Bench"),
    ]
)
