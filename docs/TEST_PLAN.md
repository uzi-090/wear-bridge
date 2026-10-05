# Test Plan

## Automated (run on every change)

| Suite | Command | Covers | Status |
|---|---|---|---|
| ANCS protocol | `cd wearos && ./gradlew :ancs:test` | Notification Source parsing, command encoding, fragment reassembly (every split offset, 1-byte and MTU chunks), app attributes, dates, session queueing/priorities/timeouts/stale results, actions, reset | ✅ 35 passing |
| AMS protocol | `./gradlew :ams:test` | Entity updates (Unicode, truncation flag), registration and command encoding, supported-command parsing, playback info, position extrapolation and clamping, truncated-value refetch, log redaction | ✅ 8 passing |
| Bridge protocol | `./gradlew :protocol:test` | Framing/reassembly, SETTINGS snapshot (Unicode, tri-state, v1.0/v1.1 prefs trailer), rules, icons, prefs, watch STATUS, switches, truncation | ✅ 14 passing |
| Watch app logic | `./gradlew :app:testDebugUnitTest` | Link state machine (happy path, pairing, auth-retry, backoff and cap, reconnect, BT off/on, duplicate/stale events, client rebuild, stop); notification policy; repository (history on/off/expiry, never for hidden apps or calls, stale→history, watch-only delete survives replay, clear-all keeps calls, conversation threads) | ✅ 32 passing |
| Watch build | `cd wearos && ./gradlew :app:assembleDebug :app:lintDebug` | Compiles, lint clean of errors | ✅ |
| iOS build | `cd ios && xcodegen && xcodebuild -scheme PairingPOC -destination 'generic/platform=iOS Simulator' build` | Compiles under Swift 6 strict concurrency | ✅ |

*Planned:* app-layer auth / replay tests (Phase 10), contact sync diff tests (Phase 6).

**UI checks on hardware (fake data via the debug demo mode, 2026-10-05):** conversation cards, thread
screen, delete (returns to the thread), Clear all confirmation, Arabic text aligned right, emoji and CJK
wrapping, Now playing layout (edge progress ring, controls), home "iPhone 16 Pro · 81%".

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
| M1.6 | Send yourself an iMessage | Watch notification with **app, title, body, time**. Log `latency=…ms` | **✔ 2026-10-05 16:47:03:** iMessage (`com.apple.MobileSMS`, Social) arrived, alerting, in **113 ms** (found in the capture after the earlier "not run" note, which was wrong) | ✔ |
| M1.7 | Send a WhatsApp message (1:1 and group) | As above. Record which action labels iOS exposes | 1:1 ✔: 3 messages at **194 / 73 / 98 ms**. App name `WhatsApp` came via Get App Attributes. Timestamp from iOS. **Only action: negative "Clear"** (no reply / mark-read). Group: pending | ✔ (1:1) |
| M1.8 | Clear the notification on the iPhone | Disappears from the watch | | |
| M1.9 | Tap the iOS-labelled negative action (e.g. "Clear") on the watch | Removed on the iPhone | | |
| M1.10 | Get a **real cellular call** | Full-screen call surface with iOS's labels (record them verbatim) | Call reached the watch in **88–104 ms**. 1st attempt: the surface **never appeared** (Android's 50-per-app quota was full from the replay, fix e14aa6d). `USE_FULL_SCREEN_INTENT` is **denied by default** on Wear OS 6, so the call falls back to a heads-up notification with iOS's actions. Labels: pending (now logged) | partial |
| M1.11 | Tap the label iOS gives for declining | The call is declined on the iPhone | **✔ 2026-10-05:** labels were exactly **"Answer" / "Decline"**. Decline from the watch succeeded in **115 ms** and the iPhone stopped ringing | ✔ |
| M1.12 | Call again, tap the label iOS gives for answering | The call is answered on the iPhone. **Record where the audio goes** | **✔ 2026-10-05:** tapped Answer on the watch, the ANCS positive action succeeded in **167 ms**, and the call connected. **Audio stayed on the iPhone** (as predicted: control ≠ audio). Answering on the iPhone removed the watch's call notification at the same moment | ✔ |
| M1.12b | Answer on the watch, then tap the in-call button | iOS's active-call action ends the call | **✔ 2026-10-05:** Answer opened the in-call screen (Category 12, label **"End Call"**). Tapping it: the action succeeded and iOS removed the active call 76 ms later, so the call ended | ✔ |
| M1.13 | Missed call | MissedCall notification. Record its labels | ✔ After the decline, iOS posted MissedCall (Category 2) with labels **"Dial" / "Clear"** | ✔ |
| M1.14 | iPhone locked vs unlocked, for M1.6 | Record any difference in content (the *Show Previews* setting) | **Not run** (2026-10-05: the user chose to proceed to Phase 4; logs showed no evidence of this test) | ⏸ |
| M1.15 | Force-quit PairingPOC, then repeat M1.6 | Still works (ANCS doesn't need the app) | **Not run** (2026-10-05: the user chose to proceed to Phase 4; logs showed no evidence of this test) | ⏸ |
| M1.16 | Walk out of range for 1 minute and back | `RECONNECTING` → READY, **no buzz** for old notifications | App-restart variant ✔: the iPhone reconnected 0.23 s after advertising began, READY in 3.2–4.7 s. 16 replayed notifications were stored silently. **Found:** the replay costs about 0.6 s per notification (serial fetch), so new messages now jump the backlog (fix 2bcc283). Out-of-range walk: **not run** | partial |
| M1.17 | Toggle watch Bluetooth off/on | "Bluetooth is off" → reconnects | **✔ 2026-10-05 (after fix 9e3abae):** BT on → advertising in 0.2 s → iPhone reconnected after 7.2 s → **READY 10.8 s** after BT on. 5 WhatsApp messages afterwards in 85–193 ms. (The first attempt got stuck "Reconnecting": a stale device reference, now fixed) | ✔ |
| M1.18 | Reboot the watch | The service restarts from boot and reconnects | **Not run** (2026-10-05: the user chose to proceed to Phase 4; logs showed no evidence of this test) | ⏸ |
| M1.19 | Reboot the iPhone (unlock once) | Reconnects. Record the time to READY | **Not run** (2026-10-05: the user chose to proceed to Phase 4; logs showed no evidence of this test) | ⏸ |

Record firmware: One UI Watch version, Wear OS version, watch security patch, iOS version.

**Test rig (2026-10-05):** Galaxy Watch4 Classic LTE (SM-R895F), Android 16 / API 36 (Wear OS 6, One UI 8 Watch), security patch 2026-08-05. iPhone 16 Pro (iOS version: TBD).

**Watch-side verified:** app installs, `connectedDevice` FGS runs, bridge GATT service is registered (`status=0`), LE advertising starts with solicitation (`dumpsys bluetooth_manager`: connectable legacy set, interval 1600 × 0.625 ms).

**Open risk carried into Phase 4:** watch reboot, iPhone reboot, out-of-range, force-quit iOS app,
locked iPhone and iMessage are unverified. Run them before any release build.

## Milestone 2: features added in session 2 (hardware)

| # | Step | Expected | Observed | Pass |
|---|---|---|---|---|
| M2.1 | Connect the watch | Log `iPhone services: …`, `ams ready`, `phone services battery=true deviceInfo=true` | **✔ 2026-10-05:** all present. AMS ready 1.5–3.7 s after READY (it waits behind the ANCS replay) | ✔ |
| M2.2 | Watch home | "iPhone 16 Pro · NN%" | **✔** 81 % matched the iPhone | ✔ |
| M2.3 | AMS volume up/down via debug hook | Command accepted | **✔** `media VOLUME_UP SENT` / `VOLUME_DOWN SENT` in ~20 ms, with no player active | ✔ |
| M2.4 | Play music on the iPhone, open Now playing, use play/pause, next, previous, bezel | iPhone reacts, title/artist/progress correct | **✔ 2026-10-05:** user confirmed track info; log shows TOGGLE_PLAY_PAUSE (iOS state flips ~0.5 s later), NEXT/PREVIOUS_TRACK and VOLUME_UP/DOWN all `SENT`; iOS listed 9 supported commands. Bezel: one click = one ±136 px event = one step | ✔ |
| M2.5 | iPhone app open → status | Watch battery, "Notifications On", "Last sync Just now" | Sealed STATUS and SETTINGS delivered after the secure session (watch log); iPhone shows settings (13 apps in its log) | ✔ |
| M2.6 | Watch → Find iPhone with the app in the foreground | Looping sound + "Found It" cover; Found It stops the watch's "Ringing" | **✔** iPhone rang; Found It reached the watch encrypted 14 s later (`control: found on iPhone`) | ✔ |
| M2.7 | Same with the iPhone locked | Alert with the 29.5 s sound | **✔ after a fix:** first attempts showed nothing — notification permission had never been requested for users who paired before onboarding (`auth=notDetermined`). After Allow Alerts: event received 1.3 s after sending, alert + chime on the Lock Screen | ✔ |
| M2.8 | Force-quit the iPhone app, then Find iPhone | Watch says "Can't reach the iPhone app" | **✔** iOS unsubscribed SETTINGS, EVENTS and STATUS at once; watch state `NO_APP` | ✔ |
| M2.9 | iPhone app → Find Watch | Alarm + vibration + Found it | **✔** `control: find watch on=true`, rang, stopped from the watch after 30 s | ✔ |
| M2.10 | iPhone app → Notifications → Keep history on, clear a message on the iPhone | It stays on the watch, dimmed, "Cleared on iPhone", for 24 h | Not run | ⏸ |
| M2.11 | Delete a notification on the watch, toggle watch Bluetooth | It doesn't come back after the replay | Not run | ⏸ |
| M2.13 | App-level pairing: install the security build on both | Watch asks "Allow iPhone app?" with the code the iPhone shows; after Allow, sealed traffic flows; a reinstall of the iPhone app reconnects without asking again | **✔ 2026-10-05:** approval → session in 11 s; reinstall → `session established` with no prompt. Stale frames from an old session were rejected by the iPhone, as designed | ✔ |
| M2.14 | iPhone → Contacts → Allow → Sync on | Contacts appear on the watch, sealed; one full sync, then incremental | **✔ 2026-10-05:** 957 contacts committed. Found + fixed: two status pushes started repeated full syncs | ✔ |
| M2.15 | Watch → Contacts → a number → Call from iPhone | iPhone offers the call | **✔ 2026-10-05:** iPhone log `event: call request` (21:21:12, sealed event decrypted); user confirmed iOS showed its call prompt | ✔ |
| M2.16 | Now playing with YouTube Music and Apple Music | Right app icon/name/colours, title, artist, progress, time | **✔** after fixes: explicit AMS reads (iOS skipped current values), duration re-read per track, ring no longer frozen; YouTube Music's App Store icon fetched by the iPhone (4.7 KB) | ✔ |
| M2.12 | Onboarding on a fresh install | Bluetooth prompt only after step 2, live ✓ on step 4 | Not run | ⏸ |

## Later phases (integration matrix)
iPhone locked/unlocked × iOS app foreground/background/force-quit × watch screen on/off/charging ×
distance 1/5/10 m × Wi-Fi off/cellular only × a 50-notifications/minute flood (dropped, duplicates,
ordering, battery, UI). Apps: WhatsApp, Messages, Phone, Gmail, Slack, Instagram, Telegram.
