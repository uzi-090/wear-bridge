# Test Plan

## Automated (run on every change)

| Suite | Command | Covers | Status |
|---|---|---|---|
| ANCS protocol | `cd wearos && ./gradlew :ancs:test` | Notification Source parsing, command encoding, fragment reassembly (every split offset, 1-byte and MTU chunks), app attributes, dates, session queueing/priorities/timeouts/stale results, actions, reset | ✅ 33 passing |
| Link state machine | `cd wearos && ./gradlew :app:testDebugUnitTest` | Happy path, pairing, auth-retry, backoff and cap, reconnect, BT off/on, duplicate/stale events, client rebuild, stop | ✅ 12 passing |
| Watch build | `cd wearos && ./gradlew :app:assembleDebug :app:lintDebug` | Compiles, lint clean of errors | ✅ |
| iOS build | `cd ios && xcodegen && xcodebuild -scheme PairingPOC -destination 'generic/platform=iOS Simulator' build` | Compiles under Swift 6 strict concurrency | ✅ |

*Planned:* BLE frame codec / auth / replay tests (Phase 10), persistence and migration tests (once Room is added), contact sync diff tests (Phase 6).

## Milestone 1: hardware (iPhone 16 Pro + Galaxy Watch4 LTE)

> Do not move past the PoC until every M1 row has an observed result.

### Setup
1. **Watch:** Settings → About watch → Software → tap *Software version* 5× → Developer options →
   enable **ADB debugging** and **Wireless debugging**. Make sure the watch and Mac are on the same Wi-Fi.
2. **Mac:** `adb pair <ip>:<pair-port>` (code shown on the watch), then `adb connect <ip>:<port>`.
3. `cd wearos && ./gradlew :app:installDebug`, then open **Bridge** on the watch → allow Bluetooth and notifications.
4. In a terminal, keep `adb logcat -s Bridge` running. Every line is redacted by design.
5. **iPhone (optional, fallback for R-02):** open `ios/Bridge.xcodeproj`, set your team, and run **PairingPOC** on the iPhone.

### Procedure and results

| # | Step | Expected | Observed | Pass |
|---|---|---|---|---|
| M1.1 | Watch: Bridge open. iPhone: Settings → Bluetooth. Does the watch appear under *Other Devices*? | Appears (ANCS solicitation) | **2026-10-05: No.** The watch was advertising (connectable, legacy, 1 s interval, ANCS solicitation in the ADV packet, bridge UUID in the scan response) but iOS Settings didn't list it. → Pair via the companion app (M1.2) | ✘ |
| M1.2 | If not: PairingPOC → *Find Watch* (then *Show nearby devices* if needed) | Watch found, iOS pairing prompt | **2026-10-05: Yes.** Found by the filtered scan (the bridge UUID in the scan response is matched by CoreBluetooth). Pairing prompt on both devices | ✔ |
| M1.3 | Confirm the pairing codes on both devices | Watch: `bond state 11 → 12`, `link … → DISCOVERING_SERVICES` | Attempts 1–2 **failed**: LESC numeric comparison and encryption succeeded, then the iPhone sent SMP *Pairing Failed* about 90 ms after the watch's identity keys, and the watch timed out after 30 s. Attempt 3, after **Forget This Device** on the iPhone and toggling the watch's Bluetooth, **succeeded** with `sec_level 0x2` (authenticated). The root cause isn't proven (no snoop log for the failures). Likely a stale half-bond | ✔ (with workaround) |
| M1.4 | Watch log shows `ancs=true` and `ancs subscribed`, and the watch shows **Connected** | READY | ✔ READY 8 s after first contact. ANCS is visible in discovery **even before bonding** (9 services) | ✔ |
| M1.5 | If `ancs=false`: iPhone Settings → Bluetooth → ⓘ → **Share System Notifications** on | ANCS appears, then READY | | |
| M1.6 | Send yourself an iMessage | Watch notification with **app, title, body, time**. Log `latency=…ms` | (pending iMessage) | |
| M1.7 | Send a WhatsApp message (1:1 and group) | As above. Record which action labels iOS exposes | 1:1 ✔: 3 messages at **194 / 73 / 98 ms**. App name `WhatsApp` came via Get App Attributes. Timestamp from iOS. **Only action: negative "Clear"** (no reply / mark-read). Group: pending | ✔ (1:1) |
| M1.8 | Clear the notification on the iPhone | Disappears from the watch | | |
| M1.9 | Tap the iOS-labelled negative action (e.g. "Clear") on the watch | Removed on the iPhone | | |
| M1.10 | Get a **real cellular call** | Full-screen call surface with iOS's labels (record them verbatim) | Call reached the watch in **88–104 ms**. 1st attempt: the surface **never appeared** (Android's 50-per-app quota was full from the replay, fix e14aa6d). `USE_FULL_SCREEN_INTENT` is **denied by default** on Wear OS 6, so the call falls back to a heads-up notification with iOS's actions. Labels: pending (now logged) | partial |
| M1.11 | Tap the label iOS gives for declining | The call is declined on the iPhone | | |
| M1.12 | Call again, tap the label iOS gives for answering | The call is answered on the iPhone. **Record where the audio goes** | **✔ 2026-10-05:** tapped Answer on the watch, the ANCS positive action succeeded in **167 ms**, and the call connected. **Audio stayed on the iPhone** (as predicted: control ≠ audio). Answering on the iPhone removed the watch's call notification at the same moment | ✔ |
| M1.13 | Missed call | MissedCall notification. Record its labels | | |
| M1.14 | iPhone locked vs unlocked, for M1.6 | Record any difference in content (the *Show Previews* setting) | | |
| M1.15 | Force-quit PairingPOC, then repeat M1.6 | Still works (ANCS doesn't need the app) | | |
| M1.16 | Walk out of range for 1 minute and back | `RECONNECTING` → READY, **no buzz** for old notifications | App-restart variant ✔: the iPhone reconnected 0.23 s after advertising began, READY in 3.2–4.7 s. 16 replayed notifications were stored silently. **Found:** the replay costs about 0.6 s per notification (serial fetch), so new messages now jump the backlog (fix 2bcc283). Out-of-range walk: pending | partial |
| M1.17 | Toggle watch Bluetooth off/on | "Bluetooth is off" → reconnects | | |
| M1.18 | Reboot the watch | The service restarts from boot and reconnects | | |
| M1.19 | Reboot the iPhone (unlock once) | Reconnects. Record the time to READY | | |

Record firmware: One UI Watch version, Wear OS version, watch security patch, iOS version.

**Test rig (2026-10-05):** Galaxy Watch4 Classic LTE (SM-R895F), Android 16 / API 36 (Wear OS 6, One UI 8 Watch), security patch 2026-08-05. iPhone 16 Pro (iOS version: TBD).

**Watch-side verified:** app installs, `connectedDevice` FGS runs, bridge GATT service is registered (`status=0`), LE advertising starts with solicitation (`dumpsys bluetooth_manager`: connectable legacy set, interval 1600 × 0.625 ms).

## Later phases (integration matrix)
iPhone locked/unlocked × iOS app foreground/background/force-quit × watch screen on/off/charging ×
distance 1/5/10 m × Wi-Fi off/cellular only × a 50-notifications/minute flood (dropped, duplicates,
ordering, battery, UI). Apps: WhatsApp, Messages, Phone, Gmail, Slack, Instagram, Telegram.
