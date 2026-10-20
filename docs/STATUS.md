# Status & handoff

_Last updated: 2026-10-08._

## Latest QA and fixes

13 October pairing review (code and unit tests; not yet re-verified on hardware): fixed the watch rejecting a re-bonded iPhone forever (address pinned during setup), OS bond loss wiping the app pairing, the iPhone stuck on Connecting… when a new watch dropped before bonding, Pair Watch Again waiting only on a stale identifier, and the missing iPhone Cancel Pairing. Also: watch approval timeout, single trusted key, previous-relationship data cleared on a new approval, Pair tap during service shutdown, and cancel/forget wording. See [Pairing lifecycle](PAIRING_LIFECYCLE.md). 115 watch app tests (198 Android), 37 iOS simulator tests, watch debug/release/lint pass.

Watch connection UI follow-up: saved/fresh pairing instructions, cancel setup, distinct Unpair/Forget dialogs, accurate reconnect states, direct Bluetooth recovery, persistent Device navigation and protected Back-stack cleanup are implemented. Seven new regressions pass (112 app tests; 195 Android total). The round-dial screenshot and final physical acceptance results are tracked in the [8 October report](PAIRING_SCENARIO_QA_2026-10-08.md).

Fresh 8 October pairing QA: [scenario report](PAIRING_SCENARIO_QA_2026-10-08.md). Physical online/offline Unpair and Forget from both devices, retained/fresh app re-pairing, denial/retry and Bluetooth disconnect gates pass. Fixed lost offline-removal guidance, interrupted cleanup/late-approval state, empty-service recovery, permission-loss cleanup, and the watch pairing-code layout. 188 Android unit tests and 33 physical-iPhone Swift tests pass; watch builds/lint pass. The actual watch code screen passes at normal and 30% larger text sizes. The corrected release is installed; both devices finish Paired / Connected with secure ANCS READY. True second-hardware replacement and the broader resilience matrix remain open.

Pairing lifecycle: [implementation](PAIRING_LIFECYCLE.md) and [7 October hardware QA](PAIRING_SCENARIO_QA_2026-10-07.md). Connected and offline Unpair/Forget from both devices, retained-device re-pair, fresh same-device pairing after OS Forget, denial/retry, cancellation, process restarts and one unpaired watch reboot passed. Fixed onboarding reset, approval/status wording, fresh discovery and a delayed system-connection discovery race; the exact race passed on the final installed build. Both devices were left Paired / Connected with authenticated ANCS READY at 18:14. 186 Android tests, 30 Swift tests on the actual iPhone, watch debug/release builds/lint and signed iPhone build pass. **Full acceptance remains partial:** no second hardware for replacement; repeated reboot/range, exact cleanup process-death and exhaustive feature/tile disconnect checks remain open. Filtered-only discovery can require Show Nearby; the encryption retry branch was not directly exercised. Automated replacement tests do not establish real replacement success.

See [the full QA report](QA_REPORT_2026-10-06.md) for baseline evidence, current fixes and open acceptance tests. 172 standard Android tests plus 6 previously validated channel harness tests pass; 21 Swift tests pass on simulator and physical iPhone. Both watch lint variants and debug/release builds pass. Contact transactions, durable removal acknowledgement, atomic encrypted generation storage, serialized transport, media capability handling, notification reconciliation/expiry and phone-handled call presentation have been updated. A reboot startup restriction is handled with a recovery notification; one fresh reboot recovered automatically without opening Bridge; repeated-reboot/firmware coverage remains open. The user confirmed the closed-player media retest. Fresh cellular/WhatsApp/FaceTime tests were deferred; broader media/player coverage remains open. Charging-aware battery alerts on both devices are implemented; see [behavior and verification](LOW_BATTERY_ALERTS.md). Public release is not ready. The phase table below records earlier hardware milestones; it does not establish complete coverage of the current code.

## Where things stand
| Phase | State |
|---|---|
| 0 Feasibility docs | ✅ Capability matrix, architecture, risk register, ANCS, protocol, security, call audio, test plan |
| 1 ANCS PoC | ✅ **Verified on hardware**: pairing, notifications (WhatsApp 64–194 ms, iMessage 113 ms), app name, timestamp |
| 2 Actions / calls | ✅ Answer (167 ms) / Decline (115 ms) from the watch. **End Call** via undocumented ANCS category 12 (experimental setting, off by default). Call audio stays on the iPhone (expected). **Session 3:** redesigned call screen, watch-only Silence ✔, shake to decline ✔ (real call), bezel to answer (built), "Answering on iPhone…" hand-over, in-call timer, end summary. Silencing the iPhone ringer: NOT POSSIBLE with public APIs |
| 3 Reconnection | 🟡 App restart ✅, BT toggle ✅. **Not run:** watch reboot, iPhone reboot, out-of-range walk, force-quit iOS app, locked iPhone (TEST_PLAN.md M1) |
| 4 Watch notification UI | ✅ Logos/icons, filtering, masking, pop-up styles, **conversation grouping, watch-only delete / clear all, opt-in 24 h history, RTL-aware text** (checked on hardware with fake data) |
| Media (AMS) | ✅ **Verified** with YouTube Music and Apple Music: app icon + brand palette, round layout, play/pause/skip, bezel volume (one click = one iOS step), progress ring |
| iPhone battery / model | ✅ **Verified**: Battery Service + Device Information read straight from iOS |
| 5 iOS companion | ✅ Rebuilt: onboarding, home (watch battery, notifications, last sync), Find Watch, Calls, Music & Media, Settings (pop-ups, history, End Call), Diagnostics, guide, About. Installed on the iPhone; the on-screen walk-through is pending (M2.5, M2.12) |
| 9 Find phone / watch | ✅ **Verified**: Find iPhone (foreground, locked, force-quit = honest "can't reach") and Find watch |
| 10 Security (app layer) | ✅ **Verified**: app-level pairing approved on the watch, every bridge message sealed (AES-GCM, replay-safe). Cross-platform test vectors from CryptoKit |
| 6 Contacts | ✅ **Verified**: 957 contacts synced, encrypted on the watch; search, favorites; "Call from iPhone" (M2.15); iPhone app manages exactly who is on the watch (add/remove, incremental syncs verified) |
| 7 HFP research, 8 health, 11 store prep | ⏸ Not started |

## Next steps
1. M2.10–M2.12 (history, delete survives replay, fresh onboarding).
2. Run the open M1 reliability tests (reboots, out of range, locked iPhone) and M2.10–M2.12.
3. iPhone diagnostics log: `xcrun devicectl device copy from --device <UDID> --domain-type appDataContainer --domain-identifier io.wearbridge.pairingpoc --source Library/Caches/diagnostics.log --destination <file>`.

## Key hardware findings (details in CAPABILITY_MATRIX / ANCS / TEST_PLAN)
- iOS Settings doesn't list the watch, so pairing goes through the iPhone app. A stale half-bond needs *Forget This Device*.
- ANCS replays every existing notification on reconnect (~0.1–0.6 s each). Fresh events jump the backlog, and replays don't post or buzz.
- Android's 50-notifications-per-app quota is real. Posted mirrors are capped at 24.
- One UI Watch picks the pop-up style: **full screen over the watch face, a small banner only inside an app**. Off-body → no pop-up or vibration. Pop-up duration is 7 s (it woke the screen) or the screen timeout. No app API changes any of this.
- `USE_FULL_SCREEN_INTENT` is denied by default on Wear OS 6, so calls use a heads-up notification, and Answer opens the call screen via an activity PendingIntent.

- **Session 2:** iOS exposes `DeviceInfo, Battery, CurrentTime, ANCS, AMS` (+ Continuity, Nearby and two
  unidentified Apple services) to the bonded watch. AMS answers commands even with no player active.
- Personal (free) Apple teams **can't sign** the Time Sensitive notifications entitlement.

## User decisions (session 1)
- India region, so no AccessoryNotifications. Text reply = NOT POSSIBLE.
- End Call: keep, as an experimental setting, **off by default**.
- Proceeded to Phase 4 with the Phase 3 reliability tests not run (recorded as open risk).
- Wants **notification/app selection in the iPhone app, not on the watch** (done).
- Wants real app logos. Approved Simple Icons download (bundled), and **always-on App Store icon lookup** (bundle IDs → Apple only).
- Wanted a small top banner. Investigated and NOT POSSIBLE over the watch face (Samsung rule). Banner style removed.

## Dev environment
See `README.md` for build commands.
- Android SDK: `/opt/homebrew/share/android-commandlinetools` (adb in `platform-tools/`). Find the watch with `adb mdns services`.
  Wireless ADB drops when the watch is off the charger.
- Xcode: use `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` (xcode-select still points at CLT). Set your own team in `ios/project.yml` (`DEVELOPMENT_TEAM`).
  The project is generated: `cd ios && xcodegen`.
- Debug-only helpers (watch): `am broadcast -a io.wearbridge.watch.debug.SAMPLE -p io.wearbridge.watch [extras]`.
  Extras: `--el uid N --es title/text/app/name … --el ago MIN --ez quiet true --ez cleared true` (fake notifications),
  `--ez demo true` (**demo mode**: clears the inbox and ignores real notifications until `am force-stop`, so
  screenshots only show fake content), `--ez fake_media true` (fake Now playing, demo mode only),
  `--es media VOLUME_UP` (real AMS command), `--ez find_phone true|false`, `--ez find_watch true|false`.
  The remote shell splits words, so quote values (see `printf %q`).
  `am start -n io.wearbridge.watch/.ui.MainActivity --es route <route>` opens a screen
  (`notifications`, `thread/<uid>`, `notification/<uid>`, `media`, `find`, `settings`, `diagnostics`).
