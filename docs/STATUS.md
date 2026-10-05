# Status & handoff

_Last updated: 2026-10-05, session 2._

## Where things stand
| Phase | State |
|---|---|
| 0 Feasibility docs | ✅ Capability matrix, architecture, risk register, ANCS, protocol, security, call audio, test plan |
| 1 ANCS PoC | ✅ **Verified on hardware**: pairing, notifications (WhatsApp 64–194 ms, iMessage 113 ms), app name, timestamp |
| 2 Actions / calls | ✅ Answer (167 ms) / Decline (115 ms) from the watch. **End Call** via undocumented ANCS category 12 (experimental setting, off by default). Call audio stays on the iPhone (expected) |
| 3 Reconnection | 🟡 App restart ✅, BT toggle ✅. **Not run:** watch reboot, iPhone reboot, out-of-range walk, force-quit iOS app, locked iPhone (TEST_PLAN.md M1) |
| 4 Watch notification UI | ✅ Logos/icons, filtering, masking, pop-up styles, **conversation grouping, watch-only delete / clear all, opt-in 24 h history, RTL-aware text** (checked on hardware with fake data) |
| Media (AMS) | ✅ **Verified**: play/pause, next/previous, volume; Wear OS-style volume screen (stepper + edge level + bezel) |
| iPhone battery / model | ✅ **Verified**: Battery Service + Device Information read straight from iOS |
| 5 iOS companion | ✅ Rebuilt: onboarding, home (watch battery, notifications, last sync), Find Watch, Calls, Music & Media, Settings (pop-ups, history, End Call), Diagnostics, guide, About. Installed on the iPhone; the on-screen walk-through is pending (M2.5, M2.12) |
| 9 Find phone / watch | ✅ **Verified**: Find iPhone (foreground, locked, force-quit = honest "can't reach") and Find watch |
| 10 Security (app layer) | ✅ **Verified**: app-level pairing approved on the watch, every bridge message sealed (AES-GCM, replay-safe). Cross-platform test vectors from CryptoKit |
| 6 Contacts | 🟡 In progress (protocol codec written) |
| 7 HFP research, 8 health, 11 store prep | ⏸ Not started |

## Next steps
1. Finish Phase 6 contacts (encrypted, incremental).
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
- Xcode: use `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` (xcode-select still points at CLT). Team `""`.
  The project is generated: `cd ios && xcodegen`.
- Debug-only helpers (watch): `am broadcast -a io.wearbridge.watch.debug.SAMPLE -p io.wearbridge.watch [extras]`.
  Extras: `--el uid N --es title/text/app/name … --el ago MIN --ez quiet true --ez cleared true` (fake notifications),
  `--ez demo true` (**demo mode**: clears the inbox and ignores real notifications until `am force-stop`, so
  screenshots only show fake content), `--ez fake_media true` (fake Now playing, demo mode only),
  `--es media VOLUME_UP` (real AMS command), `--ez find_phone true|false`, `--ez find_watch true|false`.
  The remote shell splits words, so quote values (see `printf %q`).
  `am start -n io.wearbridge.watch/.ui.MainActivity --es route <route>` opens a screen
  (`notifications`, `thread/<uid>`, `notification/<uid>`, `media`, `find`, `settings`, `diagnostics`).
