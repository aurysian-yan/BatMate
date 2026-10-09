import XCTest
@testable import BatMateCore

final class DiscoveryTests: XCTestCase {
    func testDiscoveryRejectsOtherAppsPeersVersionsAndOversizedPackets() throws {
        let query: [String: Any] = ["app": "batmate", "v": 1, "type": "discover", "id": "test-peer"]
        func accepts(_ value: [String: Any]) throws -> Bool {
            DiscoveryMessage.isQuery(try JSONSerialization.data(withJSONObject: value), id: "test-peer")
        }
        XCTAssertTrue(try accepts(query))
        for (key, value) in [("app", "localsend" as Any), ("v", 2), ("v", true), ("v", 1.5), ("id", "other"), ("type", "announce")] {
            var invalid = query; invalid[key] = value
            XCTAssertFalse(try accepts(invalid))
        }
        XCTAssertFalse(DiscoveryMessage.isQuery(Data(repeating: 32, count: 1025), id: "test-peer"))
        XCTAssertFalse(DiscoveryMessage.isQuery(Data("[]".utf8), id: "test-peer"))
        let reply = try DiscoveryMessage.announcement(id: "test-peer", fingerprint: "public-fingerprint", port: 53318)
        let value = try XCTUnwrap(JSONSerialization.jsonObject(with: reply) as? [String: Any])
        XCTAssertEqual(Set(value.keys), Set(["app", "v", "type", "id", "fingerprint", "port"]))
        XCTAssertEqual(value["port"] as? Int, 53318)
        XCTAssertFalse(DiscoveryMessage.isQuery(reply, id: "test-peer"))
    }
}
