import Foundation

// 解码手机服务快照，真实零电量有效，失效或过期的手环读数不发布。
public struct BatterySnapshot: Decodable {
    public let status: String
    public let phoneName: String?
    public let wearableName: String?
    public let phoneLevel: Int?
    public let phoneCharging: Bool?
    public let wearableLevel: Int?
    public let wearableCharging: Bool?
    public let updatedAt: Double?
    public let backgroundRunning: Bool

    public static func decodeServiceOutput(_ output: String) throws -> BatterySnapshot {
        guard let start = output.firstIndex(of: "{"), let end = output.lastIndex(of: "}") else {
            throw ReceiverError.serviceUnavailable
        }
        return try JSONDecoder().decode(Self.self, from: Data(output[start...end].utf8))
    }

    public func records(phoneName: String, wearableName: String, now: Date = Date()) -> [BatteryRecord] {
        let phoneName = displayName(self.phoneName, fallback: phoneName)
        let wearableName = displayName(self.wearableName, fallback: wearableName)
        var result: [BatteryRecord] = []
        if let level = phoneLevel, (0...100).contains(level) {
            // 系统没有手机配件分类，使用手机电池壳的原生轮廓。
            result.append(BatteryRecord(id: "phone", name: phoneName, percent: level, charging: phoneCharging ?? false, category: "Battery Case"))
        }
        if status == "ready", let level = wearableLevel, (0...100).contains(level),
           let updatedAt, now.timeIntervalSince1970 * 1000 - updatedAt <= 180_000,
           updatedAt <= now.timeIntervalSince1970 * 1000 + 30_000 {
            result.append(BatteryRecord(id: "wearable", name: wearableName, percent: level, charging: wearableCharging ?? false, category: "Watch"))
        }
        return result
    }

    private func displayName(_ name: String?, fallback: String) -> String {
        let value = name?.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.flatMap { $0.isEmpty ? nil : $0 } ?? fallback
    }
}

public struct BatteryRecord: Equatable {
    public let id: String
    public let name: String
    public let percent: Int
    public let charging: Bool
    public let category: String
}

public enum ReceiverError: Error {
    case adbUnavailable, deviceUnavailable, serviceUnavailable, queryTimedOut, invalidResponse, publishFailed(UInt32)
}
