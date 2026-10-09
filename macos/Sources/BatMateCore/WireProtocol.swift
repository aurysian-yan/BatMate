import Foundation
import Security

// 双向消息统一分帧；认证前不接收电量。
public enum WireProtocol {
    public static let maximumSize = 32_768

    public static func frame(_ message: [String: Any]) throws -> Data {
        let data = try JSONSerialization.data(withJSONObject: message.merging(["v": 1]) { _, new in new })
        guard !data.isEmpty, data.count <= maximumSize else { throw WireError.invalidMessage }
        let size = UInt32(data.count)
        return Data([UInt8(size >> 24), UInt8((size >> 16) & 255), UInt8((size >> 8) & 255), UInt8(size & 255)]) + data
    }

    public static func size(_ header: Data) throws -> Int {
        guard header.count == 4 else { throw WireError.invalidMessage }
        let size = header.reduce(0) { ($0 << 8) | Int($1) }
        guard size > 0, size <= maximumSize else { throw WireError.invalidMessage }
        return size
    }

    public static func decode(_ body: Data) throws -> [String: Any] {
        guard body.count <= maximumSize,
              let value = try JSONSerialization.jsonObject(with: body) as? [String: Any],
              let version = value["v"] as? NSNumber, version.doubleValue == 1, CFGetTypeID(version) != CFBooleanGetTypeID(),
              value["type"] is String else { throw WireError.invalidMessage }
        return value
    }

    public static func snapshot(_ value: Any?, now: Date = Date()) throws -> BatterySnapshot {
        guard var object = value as? [String: Any] else { throw WireError.invalidMessage }
        // 使用接收时间与读数年龄，避免两端时钟偏差。
        if let age = object.removeValue(forKey: "wearableAgeMs") as? NSNumber, CFGetTypeID(age) != CFBooleanGetTypeID(), age.doubleValue.isFinite, age.doubleValue >= 0 {
            object["updatedAt"] = now.timeIntervalSince1970 * 1000 - age.doubleValue
        } else { object.removeValue(forKey: "updatedAt") }
        let data = try JSONSerialization.data(withJSONObject: object)
        let result = try JSONDecoder().decode(BatterySnapshot.self, from: data)
        guard [result.phoneLevel, result.wearableLevel].allSatisfy({ $0 == nil || (0...100).contains($0!) }),
              [result.phoneName, result.wearableName].allSatisfy({ $0 == nil || $0!.count <= 120 }),
              result.status.count <= 64 else { throw WireError.invalidMessage }
        return result
    }

    static func secret() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw WireError.identity }
        return Data(bytes).base64EncodedString()
    }

    static func equal(_ lhs: String, _ rhs: String) -> Bool {
        let a = Array(lhs.utf8), b = Array(rhs.utf8)
        guard a.count == b.count else { return false }
        return zip(a, b).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }
}

public enum WireError: Error { case invalidMessage, identity, notListening, unauthorized }

struct PairedPhone: Codable { let id: String; let token: String }

// 一次性密钥仅保留在内存，成功后立即失效。
final class PairingAuthority {
    private var pending: (token: String, expires: Date)?
    var peer: PairedPhone?
    init(peer: PairedPhone? = nil) { self.peer = peer }

    func begin(now: Date = Date()) throws -> (String, Date) {
        let token = try WireProtocol.secret(), expiry = now.addingTimeInterval(300)
        pending = (token, expiry)
        return (token, expiry)
    }
    func cancel() { pending = nil }
    func claim(token: String, id: String, now: Date = Date()) throws -> PairedPhone {
        guard peer == nil, !id.isEmpty, id.count <= 64, let pending, now < pending.expires,
              WireProtocol.equal(token, pending.token) else { throw WireError.unauthorized }
        let phone = PairedPhone(id: id, token: try WireProtocol.secret())
        return phone
    }
    func commit(_ phone: PairedPhone) { peer = phone; pending = nil }
    func authenticate(token: String, id: String) -> Bool {
        guard let peer else { return false }
        return id == peer.id && WireProtocol.equal(token, peer.token)
    }
    func forget() { peer = nil; pending = nil }
}
