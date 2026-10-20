# Fresh pairing QA — 8 October 2026

Physical devices: iPhone 16 Pro and Galaxy Watch4 Classic LTE (SM-R895F). Native XCTest controlled the iPhone; ADB controlled the watch and fetched occasional screenshots. No screen mirror was opened. This is fresh verification, separate from the dated 7 October results.

## Fixes

- A dedicated watch approval screen now takes priority over navigation. Six digits stay together, including leading zeroes, with digit-by-digit accessibility semantics. The notification groups the same code. Shorter instructions and explicit button text sizing address the clipped Allow label and larger-text overflow found on the actual watch.
- iPhone removal guidance persists across relaunch. Before the fix, an offline removal warning disappeared after restart; the final offline hardware tests pass.
- Pending iPhone cleanup/reset journals force pairing closed. Late approval cannot restore a removed device, and new pairing waits for cleanup.
- Watch Bluetooth permission loss closes both transports and clears the session. A bonded connection returning zero services rebuilds its GATT client with bounded retries. Both branches have regression coverage; the zero-service recovery branch was not deliberately induced after the fix on hardware.

## Fresh physical results

| Case | Result / evidence |
|---|---|
| iPhone online Unpair, saved-entry re-pair | PASS; `ios-final-online-code.log` |
| iPhone online Forget, fresh app entry selection and approval | PASS; `ios-final-online-code.log` |
| Watch online Unpair and Forget, counterpart acknowledgement | PASS; `ios-watch-lifecycle.log`, `watch-watch-cycles.log` |
| iPhone offline Unpair and Forget, local state and warning survive restart | PASS; `ios-offline-final.log`, `watch-offline-final.log` |
| Watch offline Unpair and Forget; phone retains its own pairing until local cleanup | PASS; `ios-offline-final.log`, `watch-offline-final.log` |
| Removal cancellation | PASS; phone cancellation and watch cancellation checks in the online/offline drivers |
| Deny, Ask Again, matching-code approval | PASS; `ios-final-online-code.log` |
| Bluetooth loss and restoration; phone media/contact access and watch media route gating | PASS; `ios-final-online-code.log`, `watch-final-online-code.log` |
| Pairing state after iPhone process relaunch | PASS; tested after each removal in native UI tests |
| Watch removal interrupted by process stop | Recovery observed; `watch-recovery.log`. Exact interruption timing was not controlled. |
| Corrected approval screen, normal and 1.3 font scale | PASS; `watch-code-final.png` and `watch-code-final-large-font.png`. Six digits and full Allow/Deny labels fit inside the round display. |

Every approval in the host driver compares the actual watch code with the iPhone code before tapping Allow. A real leading-zero code (037053) also passed. Unpair retains the app device entry; Forget removes it. Offline peer notification is honestly reported as unconfirmed, and the other device must be cleared before fresh pairing.

## Automated verification

- 188 fresh Android unit tests (35 ANCS, 13 AMS, 35 protocol, 105 app): `android-fresh-tests.log`.
- 33 Swift unit tests on the physical iPhone: `ios-final-unit-tests.log`.
- Five physical XCTest scenario methods passed across online, offline and watch batches. These cover multiple removal/re-pair cycles rather than five individual user actions.
- Watch debug/release APK builds and both lint variants pass: `watch-code-accessibility-build.log`.
- Final corrected code-screen denial/reconnect run passes: `ios-code-accessibility-final.log`, `watch-code-accessibility-final.log`.
- Real watch isolated SharedPreferences instrumentation passes: `watch-storage-instrumentation.log`. This verifies persisted interrupted-removal/cleanup retry and synthetic replacement identity handling; it does not establish second-device hardware acceptance.

The hardware scheme is opt-in (`PairingHardwareQA`); normal unit tests do not run destructive pairing QA. Watch automation is `ios/PairingPOCUITests/watch_driver.py`, using `ADB` and `ANDROID_SERIAL` environment variables. Run it alongside a selected native test log. These scenarios intentionally change app pairing state and Bluetooth state; they do not reset system Bluetooth bonds.

## Approval-screen evidence

Normal text: [watch-code-final.png](qa-pairing-2026-10-08/watch-code-final.png). 30% larger text: [watch-code-final-large-font.png](qa-pairing-2026-10-08/watch-code-final-large-font.png). Both are actual 450×450 watch screenshots during code 508301. Font scale was restored afterwards. Earlier `watch-code-fixed*` and `watch-code-intermediate*` images document the layout defects and are not final screenshots.

## Final installed state

The corrected optimized release APK is installed (8 October, 01:41:34); package flags confirm it is not debuggable. `watch-final-release-install.log` and `watch-final-release-sha256.txt` record installation and artifact hash. Following the install and app relaunch, watch ANCS READY and a secure session are recorded at 01:41:56; the iPhone reports connected, protocol v1 and a secure session at 01:41:54. See `watch-final-release.log` and `iphone-final.log`. Both app records remain approved/enabled, cleanup journals are clear, contacts sync remains off, and watch font scale is restored to 1.0. iPhone persisted state is captured without peer identifiers in `iphone-final-state.json`.

## Limits

A second physical phone/watch is unavailable: true replacement-device acceptance remains open. Fresh app pairing after Forget used the same two devices; system Bluetooth Forget/re-pair was not repeated today. Repeated reboots, physical range loss, exhaustive feature/tile gating, and controlled cleanup process-death timing remain open. Permission-loss and late-approval fault cases have unit coverage, not fresh OS permission-revocation acceptance.

Initial failed logs are retained: `ios-offline-resilience.log` includes the pre-fix offline warning regression; initial phone/denial batches also exposed test-driver selection and offscreen-tap issues that were corrected before the final passing runs. None of those failed batches is counted as a pass.

Evidence directory: [qa-pairing-2026-10-08](qa-pairing-2026-10-08/).

## Watch connection UI/UX follow-up

The Galaxy Watch4 has a circular 450×450 display. Device and Home use Wear OS transforming lists and curved-edge padding; longer action labels wrap, and content scrolls. The code uses a separate priority screen with centered buttons. Raw screenshots were captured only at relevant UI states; no screen mirror was used.

Changes in this follow-up:

- Device/Diagnostics stay open when the connection changes. Phone-data routes are removed from the back stack on disconnect, preventing stale feature screens from returning through Back. Repeated Device intents no longer accumulate repeated Back steps.
- Fresh pairing uses Pair iPhone; retained-device setup uses Pair again and explains Pair Watch Again on the phone. Waiting setup offers Cancel pairing. Declined setup names Ask Again and the watch cancellation action.
- Device displays the actual Connecting/Reconnecting/Bluetooth-off/error state. A ready Bluetooth transport cannot claim completed app pairing before approval. Bluetooth-off and permission recovery have direct actions, including after local removal; system Bluetooth state is tracked independently of the app pairing phase.
- Unpair and Forget explain different outcomes. Their accessible confirmation labels name the action; Cancel dismisses it. Dialog copy is compact enough for the round display, and the dialog survives font-size configuration changes.
- Forget shows Forgetting rather than Unpairing. Removal clears old notices, then shows the result before the next pairing action. Offline completion asks for the matching removal action on the iPhone. Pairing remains unavailable until failed cleanup is resolved.
- Device scroll position resets for a new pairing phase so the new status/result appears first. Approval buttons are centered, and all connection action labels can wrap.

112 watch-app unit tests pass, including seven new state/navigation regressions; the other module totals from this fresh run remain 83 (195 Android unit tests overall). Debug/release builds and both lint variants pass. Physical scenario evidence is under `watch-ux/`; final verification follows below. Fault-injected storage tests do not establish real OS disk-failure UI acceptance.

The initial UI follow-up batch passed denial/reconnect and offline watch removal, but its cancellation re-pair and later approval checks failed: host taps after extended screenshot/configuration work did not trigger the expected watch action. The watch timeout was 15 seconds; waking immediately before tapping completed the pending matching-code approval. These failed batches are retained and are not counted as passing final acceptance. The driver now checks that Pair actually enters pairing, and the code screen requests a bounded two-minute screen hold without changing the user timeout. The final batch explicitly checks the watch remains awake after 18 seconds on the code screen.
