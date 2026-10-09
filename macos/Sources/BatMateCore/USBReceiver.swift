import Foundation

// 通过 USB 双向同步电量，不接管手环连接。
public final class USBReceiver {
    private let adb: URL

    public init() throws {
        let home = FileManager.default.homeDirectoryForCurrentUser.path
        let candidates = [ProcessInfo.processInfo.environment["BATMATE_ADB"],
                          "\(home)/Library/Android/sdk/platform-tools/adb", "/opt/homebrew/bin/adb", "/usr/local/bin/adb"].compactMap { $0 }
        guard let path = candidates.first(where: { FileManager.default.isExecutableFile(atPath: $0) }) else {
            throw ReceiverError.adbUnavailable
        }
        adb = URL(fileURLWithPath: path)
    }

    public func read() throws -> BatterySnapshot {
        let output = try run(["-d", "shell", "dumpsys", "activity", "service",
                              "com.folio.batterysync.probe/com.folio.batterysync.BatteryMonitorService", "--device-names"])
        return try BatterySnapshot.decodeServiceOutput(output)
    }

    public func sendComputerBatteries(_ devices: [ComputerBattery]) throws {
        let output = try run(["-d", "shell", "content", "call", "--uri",
                              "content://com.folio.batterysync.probe.computer-batteries", "--method", "sync",
                              "--arg", try ComputerBattery.payload(devices)])
        guard output.contains("accepted=\(devices.count)") else { throw ReceiverError.invalidResponse }
    }

    private func run(_ arguments: [String]) throws -> String {
        let process = Process()
        let output = Pipe()
        process.executableURL = adb
        process.arguments = arguments
        process.standardOutput = output
        process.standardError = FileHandle.nullDevice
        try process.run()
        let deadline = Date().addingTimeInterval(10)
        while process.isRunning && Date() < deadline { Thread.sleep(forTimeInterval: 0.05) }
        if process.isRunning { process.terminate(); process.waitUntilExit(); throw ReceiverError.queryTimedOut }
        guard process.terminationStatus == 0 else { throw ReceiverError.deviceUnavailable }
        let data = output.fileHandleForReading.readDataToEndOfFile()
        guard data.count <= 32_768, let text = String(data: data, encoding: .utf8) else {
            throw ReceiverError.invalidResponse
        }
        return text
    }
}
