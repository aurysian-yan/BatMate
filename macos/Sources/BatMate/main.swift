import Foundation
import BatMateCore

// 验证模式只输出电量与系统接纳数量，不输出设备标识。
if CommandLine.arguments.contains("--verify") {
    do {
        let snapshot = try USBReceiver().read()
        let computerDevices = try ComputerBattery.readConnected()
        try USBReceiver().sendComputerBatteries(computerDevices)
        let records = snapshot.records(phoneName: Catalog.text("batterySync.phone"), wearableName: Catalog.text("batterySync.wearable"))
        let publisher = SystemBatteryPublisher()
        try publisher.update(records)
        let result: [String: Any] = ["phoneLevel": snapshot.phoneLevel.map { $0 as Any } ?? NSNull(),
            "wearableLevel": snapshot.wearableLevel.map { $0 as Any } ?? NSNull(), "published": publisher.publishedCount,
            "eligible": publisher.eligibleCount, "glyphs": publisher.glyphCount, "computerDevices": computerDevices.count,
            "macLevel": computerDevices.first(where: { $0.category == "computer" }).map { $0.level as Any } ?? NSNull()]
        print(String(data: try JSONSerialization.data(withJSONObject: result), encoding: .utf8)!)
        publisher.withdrawAll()
    } catch { fputs("Mac 接入验证失败：\(error)\n", stderr); exit(1) }
} else {
    BatMateApp.main()
}
