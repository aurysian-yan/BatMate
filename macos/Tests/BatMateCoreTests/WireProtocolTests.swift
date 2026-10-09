import XCTest
@testable import BatMateCore

final class WireProtocolTests: XCTestCase {
    func testFramesUnicodeAndRejectsInvalidSizesAndVersions() throws {
        let frame = try WireProtocol.frame(["type": "snapshot", "name": "手机"])
        XCTAssertEqual(try WireProtocol.size(frame.prefix(4)), frame.count - 4)
        XCTAssertEqual(try WireProtocol.decode(frame.dropFirst(4))["name"] as? String, "手机")
        for header in [Data([0, 0, 0, 0]), Data([0, 0, 128, 1]), Data([255, 255, 255, 255])] {
            XCTAssertThrowsError(try WireProtocol.size(header))
        }
        for json in ["{\"v\":true,\"type\":\"auth\"}", "{\"v\":2,\"type\":\"auth\"}", "{}"] {
            XCTAssertThrowsError(try WireProtocol.decode(Data(json.utf8)))
        }
        XCTAssertThrowsError(try WireProtocol.frame(["type": "snapshot", "name": String(repeating: "x", count: 32_768)]))
    }
    func testPairingExpiresRejectsWrongSecretsAndCannotReplay() throws {
        let now = Date(timeIntervalSince1970: 1_000)
        let authority = PairingAuthority()
        let (token, _) = try authority.begin(now: now)
        XCTAssertThrowsError(try authority.claim(token: "wrong", id: "phone", now: now))
        XCTAssertThrowsError(try authority.claim(token: token, id: "phone", now: now.addingTimeInterval(300)))
        let phone = try authority.claim(token: token, id: "phone", now: now.addingTimeInterval(299))
        authority.commit(phone)
        XCTAssertThrowsError(try authority.claim(token: token, id: "phone", now: now))
        XCTAssertTrue(authority.authenticate(token: phone.token, id: phone.id))
        XCTAssertFalse(authority.authenticate(token: token, id: phone.id))
        authority.forget()
        XCTAssertFalse(authority.authenticate(token: phone.token, id: phone.id))
    }
    func testSnapshotUsesReadAgeAndRejectsInvalidBatteryWithoutChangingLastValue() throws {
        let object: [String: Any] = ["status": "ready", "phoneLevel": 100, "phoneCharging": true,
            "wearableLevel": 0, "wearableCharging": false, "backgroundRunning": true, "wearableAgeMs": 1_000]
        let now = Date(timeIntervalSince1970: 5_000)
        let snapshot = try WireProtocol.snapshot(object, now: now)
        XCTAssertEqual(snapshot.updatedAt, 4_999_000)
        XCTAssertEqual(snapshot.wearableLevel, 0)
        for level in [-1, 101, 1.5] {
            var invalid = object; invalid["wearableLevel"] = level
            XCTAssertThrowsError(try WireProtocol.snapshot(invalid, now: now))
        }
        XCTAssertEqual(snapshot.wearableLevel, 0)
        XCTAssertEqual(try WireProtocol.snapshot(object, now: now).records(phoneName: "phone", wearableName: "watch", now: now).count, 2)
    }
}
