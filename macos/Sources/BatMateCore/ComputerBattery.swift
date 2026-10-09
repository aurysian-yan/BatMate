import Foundation
import SystemBatteryBridge

// 不读取蓝牙地址，仅传输列表展示所需的名称、电量和设备类别。
public struct ComputerBattery: Codable, Equatable {
    public let name: String
    public let category: String
    public let level: Int
    public let charging: Bool?

    public static func readConnected() throws -> [ComputerBattery] {
        guard let sources = BMCopyComputerSources() else { throw ReceiverError.invalidResponse }
        let data = try JSONSerialization.data(withJSONObject: sources)
        return try JSONDecoder().decode([Self].self, from: data)
    }

    public static func payload(_ devices: [ComputerBattery]) throws -> String {
        struct Payload: Encodable { let devices: [ComputerBattery] }
        return try JSONEncoder().encode(Payload(devices: devices)).base64EncodedString()
    }
}
