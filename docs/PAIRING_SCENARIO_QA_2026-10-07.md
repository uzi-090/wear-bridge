# Pairing scenario QA — 7 October 2026

**Result: tested app removal and same-device re-pair cycles pass on both devices after fixes. Full acceptance remains partial: replacement with a second physical device and remaining resilience checks are unverified.**

Hardware: iPhone 16 Pro and Galaxy Watch4 Classic LTE (SM-R895F). The user authorized removal/re-pair cycles and confirmed no second phone or watch is available. Physical screens were operated and inspected through iPhone Mirroring and scrcpy using native computer control. Times are device-local IST. Source iPhone contacts and notifications were retained.

**Final state, 18:14:** both apps display Paired / Connected. Fresh matching Bridge approval established a secure session at 18:13:52; ANCS reached `SUBSCRIBING → READY` and notification replay resumed. Contacts remain Off until explicitly selected again. Bluetooth is back on.

## Physical scenario matrix

| Scenario | Observed result | Acceptance |
| --- | --- | --- |
| Connected Unpair initiated on iPhone | 12:43: authenticated peer confirmation; both Unpaired, entries retained, approval disabled and peer features hidden | Passed |
| Relaunch both apps after Unpair | 12:44: both stayed Unpaired; watch service stopped, no silent re-pair | Passed for process restart |
| Pair retained old device after iPhone Unpair | 12:45–12:47: fresh matching app code; Deny then Ask Again/Allow; secure session restored; contacts Off and old catalog cleared | Passed; initial attempt needed Try Again while watch server was not ready |
| Deny, retry and Allow | Final visual retest 13:31–13:32: Approval declined / Ask Again on iPhone, retry guidance on watch; features hidden until fresh approval; then secure ANCS READY | Passed |
| Cancel watch removal | 12:48: Back and dismiss cancelled Unpair; 17:41: dismiss cancelled Forget; approved connection retained | Passed |
| Connected Unpair initiated on watch | 12:49: sealed CONTROL acknowledgement; phone confirmed=true; both Unpaired with retained entries | Passed |
| Pair retained old device after watch Unpair | 12:50–12:51: different matching app code and fresh approval; secure session restored | Passed |
| Reconnect after app update | 12:51: authenticated session restored; removed catalog remained empty before new replay | Passed |
| Connected Forget initiated on iPhone | 12:54: peer acknowledgement queued, cleanupFailed=false, watch radio stopped; phone confirmed=true and identifier absent | Passed |
| Forget preserves completed onboarding | Fixed legacy migration and root gate; full tour completed 13:32–13:33; subsequent connected and offline Forget cycles kept completed onboarding | Passed after fix |
| Cancel fresh discovery | 13:00: Back cancelled onboarding search; search could start again without granting approval | Passed |
| Fresh discovery and OS bond after Forget on same hardware | Initial filtered/fallback attempts failed. After fixes, Show Nearby found the watch 13:30; matching OS numeric comparison confirmed on both displays; bond complete and 10 iPhone services including ANCS found | Passed with Show Nearby fallback; filtered-only discovery is not guaranteed |
| iPhone OS Bluetooth Forget | 13:05: watch disappeared from My Devices. At 13:16 Android automatically removed its stale iPhone bond after authentication failure | Observed; manual watch-side OS Unpair was not tested |
| Unfinished pairing, watch process restart | 13:06: restored Unpaired and required explicit Pair again | Passed |
| Unfinished pairing, physical watch reboot | 13:07 reboot; user restored wireless debugging; 13:14 watch observed Unpaired with no automatic Bridge service activation | Passed for this reboot |
| Approved watch process restart | 13:34: force-stop/relaunch preserved approval and returned to READY without new approval | Passed |
| Watch Forget during interrupted peer session | 17:43: our overlapping iPhone test runner interrupted the session; watch reported confirmed=false and completed local cleanup with peer-removal guidance | Local interrupted-removal result; not a clean acknowledgement pass |
| Connected Forget initiated on watch, clean repeat | 17:49: peer confirmed=true in about 260 ms; cleanupFailed=false; both Unpaired, onboarding intact | Passed |
| Offline iPhone Unpair | 17:44: confirmed=false; local cleanup, retained entry / Pair Watch Again, honest counterpart-removal guidance | Passed |
| Offline iPhone Forget | 17:45 and 18:08: local entry removed, no Forget row, Pair Watch available, completed onboarding retained | Passed |
| Offline watch Unpair | Bluetooth Off verified 18:05; 18:07 removal queued=false / confirmed=false / cleanupFailed=false; Unpaired, retained Forget entry and counterpart-removal guidance visible | Passed |
| Offline watch Forget with service stopped | 18:10: local fallback cleanup; Forget row disappeared, Unpaired and local-removal guidance remained. This path has no service removal log; evidence is inspected UI plus separate persistence coverage | Passed |
| Radio restored after removal | 18:11: Bluetooth On; watch remained Unpaired, iPhone stayed searching; no app approval or peer features restored automatically | Passed |
| Phone search starts before watch Pair | 18:10:41: initial connected-candidate count=0. Explicit watch Pair at 18:12:55; same search reported candidates=1 without restarting or Show Nearby | Passed after search-refresh fix; exact race exercised |
| Explicit re-pair after both sides forgot | 18:13: candidate explicitly selected, matching new app code inspected on both Device screens, watch Allow; secure session and ANCS READY; both Home screens Connected | Passed |
| Notification readiness after fresh pairing | 13:32, 17:47, 17:53 and 18:13: secure sessions reached ANCS READY; real notification replay resumed | Passed; initial zero-service baseline resolved by fresh OS pairing |
| Different physical watch or phone | No second hardware available | Unverified; isolated replacement tests only |
| Repeated paired reboots, iPhone reboot, range loss, exact process death during cleanup | Not deliberately exercised end to end | Unverified |
| Disconnect/removal while each feature screen, call screen and tile is open | Root gates implemented; exhaustive physical navigation/tile matrix not exercised | Unverified |

Same-device fresh pairing is distinct from real replacement. OS reconnect and notification-service discovery can happen before Bridge approval; connected features stay gated until approval and authenticated READY. Notification replay can populate a newly cleared catalog/history from notifications still present on the iPhone; this is new sync, not retained removed-peer data.

## Fixes made during testing

- Denied approval and changed watch identity have accurate iPhone status titles. The watch gives denial/retry guidance instead of misleading Connecting text.
- Both Device screens expose matching-code approval. Watch status distinguishes App connected from notification READY.
- Forget preserves completed onboarding for legacy approved users. Initial discovery no longer dismisses the remaining tour. Back cancels onboarding search.
- Discovery handles notification-service solicitations, unnamed matching advertisements and system-connected Bridge watches. Watch setup advertisements put the Bridge service in the primary payload. Higher-confidence candidates appear first; selection remains explicit.
- During search, the iPhone refreshes system-connected candidates every two seconds. Cancellation, selection and session invalidation stop the task. This fixes the physical race where iOS connected after the initial query and advertisements stopped.
- Pairing characteristic reads receiving ATT insufficient encryption wait with bounded, session-scoped retries rather than immediately cancelling the OS prompt. The installed build passed fresh numeric-comparison pairing; that successful attempt did not emit the retry diagnostic, so the retry branch remains unexercised on hardware.
- Compact discovery artwork and approval-specific footer text leave space for nearby candidates during onboarding.
- A disconnect reporting removed pairing information stops automatic reconnection and gives recovery guidance. Notification status says Waiting when notification connectivity is absent instead of incorrectly claiming the sharing switch is off.
- Redacted removal diagnostics record initiator, acknowledgement and cleanup result.

Discovery uses the public [Core Bluetooth solicitation scan option](https://developer.apple.com/documentation/corebluetooth/cbcentralmanagerscanoptionsolicitedserviceuuidskey). Bridge clears its own relationship. This implementation leaves OS bond removal to Settings; no private bond-removal API was used.

## Automated verification and installed builds

- **186 Android tests passed in a fresh forced run:** ANCS 35, AMS 13, protocol 35, app 103. Covers simulated replacement identities/addresses, wrong-peer rejection, old/replayed sessions, cleanup and persisted lifecycle transitions.
- **30 Swift tests passed on the physical iPhone with zero failures after the final search-refresh fix**, including nine DevicePairing tests and onboarding regressions. Different-UUID replacement tests use isolated preferences; they do not establish a physical BLE replacement exchange.
- Final watch debug/release builds and both lint variants passed; discovery advertisement changes were in the installed release. Existing warnings remain.
- Final normal signed iPhone build passed, installed at 17:52, and passed deliberately ordered discovery and full re-pair at 18:13.
- Isolated watch SharedPreferences instrumentation passed. New store instances simulate reload; this is separate from the actual 13:07–13:14 physical reboot.
- `git diff --check` passed after the final code changes.

## Evidence and remaining acceptance

See [timestamped observations](qa-pairing-2026-10-07/scenario-observations.md), [final watch log](qa-pairing-2026-10-07/scenario-watch-final.log), [final iPhone log](qa-pairing-2026-10-07/scenario-iphone-final-ready.log), [reverse-order discovery](qa-pairing-2026-10-07/scenario-iphone-reverse-discovery.log), [clean watch Forget](qa-pairing-2026-10-07/scenario-iphone-watch-forget.log), [offline phone removal](qa-pairing-2026-10-07/scenario-iphone-offline-repair.log), [fresh OS pairing and READY](qa-pairing-2026-10-07/scenario-iphone-ready.log), [fresh Android tests](qa-pairing-2026-10-07/scenario-android-tests-fresh.log), [latest iPhone tests](qa-pairing-2026-10-07/scenario-ios-refresh-tests.log), [watch discovery build/lint](qa-pairing-2026-10-07/scenario-watch-discovery-build.log), and [latest signed iPhone build](qa-pairing-2026-10-07/scenario-ios-refresh-build.log).

Remaining acceptance needs another physical phone/watch for replacement, repeated reboot/range and cleanup-interruption tests, and the feature/tile disconnect matrix. Filtered-only discovery and the encryption retry branch also need repeated targeted hardware checks. Do not turn this one-pair result into a claim that every scenario is completely seamless.
