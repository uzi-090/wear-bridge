# Status & handoff

_Last updated: 2026-10-05, end of session 1._

## Where things stand
| Phase | State |
|---|---|
| 0 Feasibility docs | ✅ Capability matrix, architecture, risk register, ANCS, protocol, security, call audio, test plan |
| 1 ANCS PoC | ✅ **Verified on hardware**: pairing, notifications (WhatsApp 64–194 ms, iMessage 113 ms), app name, timestamp |
| 2 Actions / calls | ✅ Answer (167 ms) / Decline (115 ms) from the watch. **End Call** via undocumented ANCS category 12 (experimental setting, off by default). Call audio stays on the iPhone (expected) |
| 3 Reconnection | 🟡 App restart ✅, BT toggle ✅ (after fix 9e3abae). **Not run:** watch reboot, iPhone reboot, out-of-range walk, force-quit iOS app, locked iPhone (see TEST_PLAN.md) |
| 4 Watch notification UI | 🟡 In progress: logos/icons ✅, filtering ✅, masking ✅, pop-up styles (Standard/Subtle) ✅. **Next:** inbox delete/clear-all, history on/off, chat grouping, large-text/RTL check |
| 5 iOS companion | 🟡 PairingPOC + **Notifications settings screen** (per-app rules over the SETTINGS channel) + App Store icon fetching |
| 6+ Contacts, HFP research, health, find phone, hardening, store prep | ⏸ Not started |

## Key hardware findings (details in CAPABILITY_MATRIX / ANCS / TEST_PLAN)
- iOS Settings doesn't list the watch, so pairing goes through the iPhone app. A stale half-bond needs *Forget This Device*.
- ANCS replays every existing notification on reconnect (~0.1–0.6 s each). Fresh events jump the backlog, and replays don't post or buzz.
- Android's 50-notifications-per-app quota is real. Posted mirrors are capped at 24.
- One UI Watch picks the pop-up style: **full screen over the watch face, a small banner only inside an app**. Off-body → no pop-up or vibration. Pop-up duration is 7 s (it woke the screen) or the screen timeout. No app API changes any of this.
- `USE_FULL_SCREEN_INTENT` is denied by default on Wear OS 6, so calls use a heads-up notification, and Answer opens the call screen via an activity PendingIntent.

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
- Xcode: use `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` (xcode-select still points at CLT). Team `""`.
  The project is generated: `cd ios && xcodegen`.
- Debug-only helpers (watch): `am broadcast -a io.wearbridge.watch.debug.SAMPLE -p io.wearbridge.watch [--el uid N --es style FULL|SUBTLE]`
  posts a fake notification. `am start -n io.wearbridge.watch/.ui.MainActivity --es route <route>` opens a screen.
