import XCTest
@testable import PairingPOC
final class LowBatteryPolicyTests: XCTestCase {
    func testTenAndEveryLowerReadingAlert() {
        for p in 0...10 { XCTAssertEqual(LowBatteryPolicy.decide(percent: p, alerted: false), .alert) }
    }
    func testRecoveryRearms() {
        for p in 11...100 { XCTAssertEqual(LowBatteryPolicy.decide(percent: p, alerted: true), .rearm) }
    }
    func testRepeatedLowReadingsDoNotAlert() {
        for p in 0...10 { XCTAssertEqual(LowBatteryPolicy.decide(percent: p, alerted: true), .ignore) }
    }
    func testUnknownDisconnectAndInvalidReadingsDoNotRearm() {
        for p: Int? in [nil, -1, 101, 255] { XCTAssertEqual(LowBatteryPolicy.decide(percent: p, alerted: true), .ignore) }
    }
    func testNextLowEpisodeAlertsAfterRecovery() {
        XCTAssertEqual(LowBatteryPolicy.decide(percent: 10, alerted: false), .alert)
        XCTAssertEqual(LowBatteryPolicy.decide(percent: 9, alerted: true), .ignore)
        XCTAssertEqual(LowBatteryPolicy.decide(percent: 11, alerted: true), .rearm)
        XCTAssertEqual(LowBatteryPolicy.decide(percent: 10, alerted: false), .alert)
    }
    func testChargingClearsWarningsEvenWhenLevelUnknown() {
        for p: Int? in [nil, 0, 9, 10, 100] { XCTAssertEqual(LowBatteryPolicy.decide(percent: p, alerted: true, charging: true), .rearm) }
    }
    func testUnknownChargingCannotTriggerLowWarning() {
        XCTAssertEqual(LowBatteryPolicy.decide(percent: 10, alerted: false, charging: nil), .ignore)
        XCTAssertEqual(LowBatteryPolicy.decide(percent: 11, alerted: true, charging: nil), .rearm)
    }
    func testUnpluggingLowStartsNewEpisodeWithoutRepeating() {
        XCTAssertEqual(LowBatteryPolicy.decide(percent: 8, alerted: true, charging: true), .rearm)
        XCTAssertEqual(LowBatteryPolicy.decide(percent: 8, alerted: false, charging: false), .alert)
        XCTAssertEqual(LowBatteryPolicy.decide(percent: 7, alerted: true, charging: false), .ignore)
    }
}
