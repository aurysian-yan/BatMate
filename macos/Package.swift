// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "BatMate",
    platforms: [.macOS(.v13)],
    products: [.executable(name: "BatMate", targets: ["BatMate"])],
    targets: [
        .target(name: "SystemBatteryBridge", linkerSettings: [.linkedFramework("IOKit"), .linkedFramework("Foundation")]),
        .target(name: "BatMateCore", dependencies: ["SystemBatteryBridge"]),
        .executableTarget(name: "BatMate", dependencies: ["BatMateCore"], resources: [.copy("Resources")]),
        .testTarget(name: "BatMateCoreTests", dependencies: ["BatMateCore"]),
    ],
    swiftLanguageModes: [.v5]
)
