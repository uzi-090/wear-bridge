import XCTest

/// Runs against the real app and real watch; host automation handles watch controls.
@MainActor
final class PairingHardwareTests: XCTestCase {
    let app = XCUIApplication(bundleIdentifier: "io.wearbridge.pairingpoc")
    override func setUp() async throws { continueAfterFailure = false; app.launch() }
    private var connected: XCUIElement { app.staticTexts.matching(NSPredicate(format: "label == %@ OR label == %@", "Connected", "Connection, Connected")).firstMatch }
    private var unpaired: XCUIElement { app.staticTexts.matching(NSPredicate(format: "label == %@ OR label == %@", "Unpaired", "State, Unpaired")).firstMatch }
    private func back() { if app.navigationBars["Device"].exists { app.navigationBars.buttons.firstMatch.tap() } }
    private func device() {
        if app.navigationBars["Device"].exists { return }
        for _ in 0..<3 {
            let row = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Pairing & Devices")).firstMatch
            if !row.isHittable { app.swipeUp() }
            XCTAssertTrue(row.waitForExistence(timeout: 10), app.debugDescription)
            row.tap()
            if app.navigationBars["Device"].waitForExistence(timeout: 3) { return }
        }
        XCTFail(app.debugDescription)
    }
    private func pair(denyFirst: Bool = false) {
        device()
        let code = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Code ")).firstMatch
        if !code.exists {
            let again = app.buttons["Pair Watch Again"]
            let retained = again.exists
            let button = retained ? again : app.buttons["Pair Watch"]
            XCTAssertTrue(button.waitForExistence(timeout: 5), app.debugDescription)
            print("QA_WATCH: pair")
            button.tap()
            if !retained {
                let candidate = app.buttons.matching(identifier: "nearbyWatch").firstMatch
                if !candidate.waitForExistence(timeout: 45), app.buttons["Show all nearby devices"].exists {
                    app.buttons["Show all nearby devices"].tap()
                }
                XCTAssertTrue(candidate.waitForExistence(timeout: 45), app.debugDescription)
                candidate.tap()
            }
        }
        XCTAssertTrue(code.waitForExistence(timeout: 45), app.debugDescription)
        print("QA_CODE: \(code.label)")
        if denyFirst {
            print("QA_WATCH: deny")
            if !app.buttons["Ask Again"].exists { app.swipeUp() }
            XCTAssertTrue(app.buttons["Ask Again"].waitForExistence(timeout: 30), app.debugDescription)
            XCTAssertFalse(connected.exists)
            Thread.sleep(forTimeInterval: 10) // host reads the declined watch UI before retry
            app.buttons["Ask Again"].tap()
            XCTAssertTrue(code.waitForExistence(timeout: 30))
            print("QA_CODE: \(code.label)")
        }
        print("QA_WATCH: allow")
        XCTAssertTrue(connected.waitForExistence(timeout: 90), app.debugDescription)
        back()
        print("QA_PASS: repaired phone secure")
    }
    private func remove(_ forget: Bool) {
        device()
        let title = forget ? "Forget Device" : "Unpair Watch"
        app.buttons[title].tap()
        let dialog = app.sheets.firstMatch
        XCTAssertTrue(dialog.waitForExistence(timeout: 5))
        dialog.buttons[title].tap()
        XCTAssertTrue(unpaired.waitForExistence(timeout: 15), app.debugDescription)
        print("QA_CHECK_WATCH: \(forget ? "forgotten" : "unpaired")")
        app.terminate(); app.launch()
        device()
        XCTAssertTrue(unpaired.exists)
        XCTAssertEqual(app.buttons["Forget Device"].exists, !forget)
        back()
        print("QA_PASS: \(title) persisted")
    }
    func testPhoneLifecycle() {
        if !connected.waitForExistence(timeout: 8) { pair() }
        device()
        XCTAssertTrue(connected.waitForExistence(timeout: 30), app.debugDescription)
        app.buttons["Unpair Watch"].tap()
        XCTAssertTrue(app.sheets.firstMatch.waitForExistence(timeout: 5))
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.1)).tap()
        XCTAssertTrue(connected.exists)
        back()
        remove(false); pair(); remove(true); pair()
    }
    func testWatchLifecycle() {
        if !connected.waitForExistence(timeout: 8) { pair() }
        for forget in [false, true] {
            print("QA_WATCH: \(forget ? "forget" : "unpair")")
            XCTAssertTrue(unpaired.waitForExistence(timeout: 45), app.debugDescription)
            device()
            XCTAssertEqual(app.buttons["Forget Device"].exists, !forget)
            back()
            app.terminate(); app.launch()
            XCTAssertTrue(unpaired.waitForExistence(timeout: 10))
            pair()
        }
    }
    func testOfflinePhoneLifecycle() {
        if !connected.waitForExistence(timeout: 8) { pair() }
        for forget in [false, true] {
            print("QA_WATCH: radio_off")
            let disconnected = app.staticTexts["Reconnecting…"]
            _ = disconnected.waitForExistence(timeout: 15)
            remove(forget)
            XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "could not confirm")).firstMatch.exists)
            print("QA_WATCH: offline_cleanup_\(forget ? "forget" : "unpair")")
            pair()
        }
    }

    func testOfflineWatchLifecycle() {
        if !connected.waitForExistence(timeout: 8) { pair() }
        for forget in [false, true] {
            print("QA_WATCH: offline_watch_\(forget ? "forget" : "unpair")")
            let lost = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: connected)
            XCTAssertEqual(XCTWaiter.wait(for: [lost], timeout: 25), .completed)
            Thread.sleep(forTimeInterval: 20) // allow the external watch driver to finish local removal
            device()
            XCTAssertTrue(app.staticTexts["State, Paired"].exists)
            back()
            remove(forget) // offline counterpart cleanup; neither peer silently repairs approval
            print("QA_WATCH: radio_on")
            pair()
        }
    }

    func testDenialAndDisconnect() {
        XCTAssertTrue(connected.waitForExistence(timeout: 30))
        remove(false); pair(denyFirst: true)
        let media = app.buttons["Music & Media"]
        if !media.isHittable { app.swipeUp() }
        media.tap()
        XCTAssertTrue(app.navigationBars["Music & Media"].waitForExistence(timeout: 5))
        print("QA_WATCH: radio_off")
        let removed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.navigationBars["Music & Media"])
        XCTAssertEqual(XCTWaiter.wait(for: [removed], timeout: 25), .completed)
        XCTAssertFalse(app.buttons["Contacts"].exists)
        print("QA_WATCH: radio_on")
        XCTAssertTrue(connected.waitForExistence(timeout: 45), app.debugDescription)
        print("QA_PASS: denied approval retry and feature disconnect gates")
    }

    func testWatchCancelPairing() {
        if !connected.waitForExistence(timeout: 8) { pair() }
        remove(false)
        print("QA_WATCH: cancel_setup")
        Thread.sleep(forTimeInterval: 30) // host checks waiting screen and cancels watch setup
        XCTAssertTrue(unpaired.exists)
        pair()
        print("QA_PASS: cancelled watch setup can pair again")
    }

}
