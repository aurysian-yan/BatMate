import Foundation
import SystemBatteryBridge

// 仅登记配件电源，不创建 UPS 或修改 Mac 自身电源状态。
public final class SystemBatteryPublisher {
    private var sources: [String: UnsafeMutableRawPointer] = [:]
    public init() {}

    public func update(_ records: [BatteryRecord]) throws {
        let present = Set(records.map(\.id))
        for id in Array(sources.keys) where !present.contains(id) {
            BMReleaseSource(sources.removeValue(forKey: id))
        }
        for record in records {
            var result: UInt32 = 0
            let source: UnsafeMutableRawPointer
            if let existing = sources[record.id] { source = existing }
            else {
                guard let created = BMCreateSource(&result), result == 0 else { throw ReceiverError.publishFailed(result) }
                source = created
                sources[record.id] = created
            }
            let details: [String: Any] = [
                "Type": "Accessory Source", "Name": record.name,
                "Accessory Identifier": "app.batmate.\(record.id)", "Group Identifier": "app.batmate.\(record.id)",
                "Part Identifier": "Single", "Is Present": true, "Is Charging": record.charging,
                "Current Capacity": record.percent, "Max Capacity": 100,
                "Power Source State": record.charging ? "AC Power" : "Battery Power",
                "Transport Type": record.id == "phone" ? "USB" : "Ethernet", "Accessory Category": record.category,
                // 手机借用系统 iPhone 电池壳图标，不改变设备名称或电量来源。
                "Vendor ID": record.id == "phone" ? 1452 : 65535,
                "Product ID": record.id == "phone" ? 5016 : 65535,
            ]
            result = BMUpdateSource(source, details as CFDictionary)
            guard result == 0 else { throw ReceiverError.publishFailed(result) }
        }
    }

    public func withdrawAll() {
        sources.values.forEach(BMReleaseSource)
        sources.removeAll()
    }
    public var publishedCount: Int { Int(BMCountPublishedSources()) }
    public var eligibleCount: Int { Int(BMCountEligibleSources()) }
    public var glyphCount: Int { Int(BMCountSourcesWithGlyph()) }
    deinit { withdrawAll() }
}
