import XCTest
@testable import BatMateCore

final class BatterySnapshotTests: XCTestCase {
    func snapshot(status: String = "ready", level: String = "68", time: Double = 1000) throws -> BatterySnapshot {
        try BatterySnapshot.decodeServiceOutput("SERVICE\n{\"status\":\"\(status)\",\"phoneLevel\":100,\"phoneCharging\":true,\"wearableLevel\":\(level),\"wearableCharging\":false,\"updatedAt\":\(time),\"backgroundRunning\":true}")
    }
    func testKeepsRealZeroAndCharging() throws {
        let records = try snapshot(level: "0").records(phoneName: "Phone", wearableName: "Band", now: Date(timeIntervalSince1970: 1))
        XCTAssertEqual(records.count, 2)
        XCTAssertEqual(records.last?.percent, 0)
        XCTAssertEqual(records.first?.charging, true)
    }
    func testWithdrawsDisconnectedInvalidAndStaleWearable() throws {
        for record in [try snapshot(status: "deviceDisconnected"), try snapshot(level: "101"), try snapshot(level: "null"), try snapshot(time: 0)] {
            XCTAssertEqual(record.records(phoneName: "Phone", wearableName: "Band", now: Date(timeIntervalSince1970: 181)).count, 1)
        }
    }
    func testRejectsMissingService() {
        XCTAssertThrowsError(try BatterySnapshot.decodeServiceOutput("No services match"))
    }
    func testUsesDeviceNamesAndFallsBackForBlankNames() throws {
        let output = """
        {"status":"ready","phoneName":" Redmi K60 ","wearableName":"小米手环 10 Pro 陶瓷版","phoneLevel":100,"wearableLevel":68,"updatedAt":1000,"backgroundRunning":true}
        """
        let named = try BatterySnapshot.decodeServiceOutput(output).records(phoneName: "Phone", wearableName: "Band", now: Date(timeIntervalSince1970: 1))
        XCTAssertEqual(named.map(\.name), ["Redmi K60", "小米手环 10 Pro 陶瓷版"])
        XCTAssertEqual(named.first?.category, "Battery Case")
        let blank = try BatterySnapshot.decodeServiceOutput(output.replacingOccurrences(of: " Redmi K60 ", with: "   "))
        XCTAssertEqual(blank.records(phoneName: "Phone", wearableName: "Band").first?.name, "Phone")
    }
}
