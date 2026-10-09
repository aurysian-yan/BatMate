import XCTest
@testable import BatMateCore

final class ComputerBatteryTests: XCTestCase {
    func testUSBPayloadPreservesUnicodeNamesZeroAndUnknownCharging() throws {
        let input = """
        [{"name":"蓝牙触控板","category":"trackpad","level":0,"charging":null},
         {"name":"MacBook Pro","category":"computer","level":80,"charging":true}]
        """
        let devices = try JSONDecoder().decode([ComputerBattery].self, from: Data(input.utf8))
        let payload = try ComputerBattery.payload(devices)
        let data = try XCTUnwrap(Data(base64Encoded: payload))
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let records = try XCTUnwrap(object["devices"] as? [[String: Any]])
        XCTAssertEqual(records.first?["name"] as? String, "蓝牙触控板")
        XCTAssertEqual(records.first?["level"] as? Int, 0)
        XCTAssertNil(records.first?["charging"])
        XCTAssertEqual(records.count, 2)
        XCTAssertEqual(records[1]["category"] as? String, "computer")
        XCTAssertEqual(records[1]["level"] as? Int, 80)
        XCTAssertEqual(records[1]["charging"] as? Bool, true)
        XCTAssertFalse(payload.contains("\n"))
    }
}
