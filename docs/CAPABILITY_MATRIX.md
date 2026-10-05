# Capability Matrix

> **Status of this document:** started as the Phase 0 research assessment and is updated with hardware
> results. A row's `HW` column changes from `—` to `✔` (or `✘`) only after it has been demonstrated on an
> iPhone 16 Pro + Galaxy Watch4 Classic LTE (One UI 8 Watch / Wear OS 6). "Implemented" without ✔ means
> built and unit-tested, with the hardware test still pending (TEST_PLAN.md §M2). Rows marked *verify*
> are plausible from the specs, but the exact behaviour is undocumented.
>
> Target user region: **India (non-EU)**. Apple's EU-only *AccessoryNotifications* framework is therefore
> listed as unavailable everywhere it would otherwise help.

## Legend

**Status**

| Code | Meaning |
|---|---|
| ✅ FULL | FULLY SUPPORTED by public APIs/protocols |
| 🟡 LIMITED | SUPPORTED WITH LIMITATIONS (works, with documented gaps) |
| 🟠 BEST EFFORT | Works only when the OS cooperates (app alive, link up, iOS policy) and can't be guaranteed |
| 🧪 EXPERIMENTAL | Unknown until tested on hardware; may turn out to be impossible |
| ⛔ NOT POSSIBLE | **NOT POSSIBLE WITH CURRENT PUBLIC API**, with the closest legitimate alternative noted |

**Paths** (source → destination)

| Code | Path | Needs iOS app running? |
|---|---|---|
| **A** | iOS system → **ANCS** (GATT, bonded LE) → Watch | **No.** iOS's own Bluetooth daemon serves it |
| **M** | iOS system → **AMS** (Apple Media Service, GATT) → Watch | No |
| **S** | iOS system → standard GATT services (Battery 0x180F, Current Time 0x1805, Device Info 0x180A) → Watch | No (*verify which ones iOS exposes*) |
| **B** | iOS companion app ⇄ **Bridge GATT service** (hosted on the watch) ⇄ Watch app | **Yes**, the app has to be running or relaunchable via CoreBluetooth background modes |
| **W** | Watch-local Android / Wear OS APIs | n/a |
| **H** | Bluetooth Classic **HFP** (system-managed) | n/a, system-level only |

**Columns:** Batt = battery impact (L/M/H). Rel = expected reliability (H/M/L). AS / GP = App Store /
Google Play review risk (L/M/H).

## Global preconditions for path A/M/S
1. The watch and iPhone are **bonded** (LE Secure Connections). ANCS characteristics require an encrypted, authenticated link.
2. iOS **Settings → Bluetooth → (watch) → "Share System Notifications"** is enabled (iOS 13+ shows this toggle for ANCS consumers).
3. The watch is advertising while disconnected (ANCS is solicited in the advertisement on API 31+), so iOS can reconnect at system level.
4. Before the iPhone's **first unlock after reboot**, ANCS content isn't available (*verify*).

---

## 1. Notifications

| Capability | Status | Path | API / protocol | Permission | Background req. | Batt | Rel | AS | GP | HW | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|
| All iPhone notifications | 🟡 LIMITED | A | ANCS Notification Source | iOS pairing + "Share System Notifications"; watch BT perms | Watch FGS `connectedDevice` | L | H | L | M | — | Only what iOS forwards over ANCS. Apps with notifications disabled are excluded. Live Activities are **not** notifications and aren't forwarded. |
| Title | ✅ FULL | A | ANCS attr `Title` (max length requested) | as above | as above | L | H | L | L | — | We request up to 64 bytes and truncate on a UTF-8 boundary. |
| Subtitle | ✅ FULL | A | ANCS attr `Subtitle` | | | L | H | L | L | — | Often empty. |
| Body / message | ✅ FULL | A | ANCS attr `Message` + `MessageSize` | | | L | H | L | L | — | We request 512 bytes; `MessageSize` tells us whether it was truncated. |
| Application name | ✅ FULL | A | ANCS *Get App Attributes* → `DisplayName` | | | L | H | L | L | — | Cached per bundle ID for the session. |
| Application identifier | ✅ FULL | A | ANCS attr `AppIdentifier` | | | L | H | L | L | — | iOS bundle ID, e.g. `net.whatsapp.WhatsApp`. |
| App icon | 🟡 LIMITED (**verified**) | iOS app → B → W | iOS can't read other apps' icons (**NOT POSSIBLE WITH CURRENT PUBLIC API**). **Implemented:** the iPhone app looks up each app that has notified the watch on **Apple's public App Store lookup** (`itunes.apple.com/lookup?bundleId=`, the user's region, then the US), downloads the official icon, resizes it to a 96 px JPEG (3–5 KB), caches it, and sends it over the SETTINGS channel (`SET_APP_ICON`). Watch priority: App Store icon → bundled Simple Icons logo → monogram. Apple's built-in apps aren't in the store, so they get generic glyphs | Network (iPhone) | iOS app running when a new app appears | L | H | **M** | **M** | ✔ (5 apps: Airtel, Google Chat, Flipkart, GeoGuessr, WhatsApp) | **Network use:** sends only bundle IDs, only to Apple (user chose "always on"). App icons are their owners' trademarks: review before store release (R-13). |
| Timestamp | 🟡 LIMITED | A | ANCS attr `Date` (`yyyyMMdd'T'HHmmSS`) | | | L | H | L | L | — | Local time with **no timezone**. We interpret it in the watch's zone, which is the same zone in practice. |
| Category | ✅ FULL | A | ANCS `CategoryID` (Other, IncomingCall, MissedCall, Voicemail, Social, Schedule, Email, News, Health, Business, Location, Entertainment) | | | L | H | L | L | — | Assigned by iOS and coarse. |
| Grouping | 🟡 LIMITED (implemented) | A + W | No thread ID in ANCS | — | — | L | M | L | L | ✔ (fake data) | Watch inbox groups by app + title (iOS puts the sender or chat name in the title). One card per conversation with "+N more", and a thread screen. Hidden-content apps group by app only. |
| Notification updates | ✅ FULL | A | EventID `Modified` | | | L | H | L | L | — | Re-fetch attributes, update in place, no repeat haptic. |
| Notification removal (iPhone → watch) | ✅ FULL | A | EventID `Removed` | | | L | H | L | L | — | When the user clears it on the iPhone, it disappears from the watch. |
| Dismissal (watch → iPhone) | 🟡 LIMITED | A | *Perform Notification Action* `Negative`, when the `NegativeAction` flag is set (label is typically "Clear") | | | L | H | L | L | — | Only when iOS advertises a negative action. |
| Delete from watch / Clear all | ✅ FULL (implemented) | W | Watch-local. Deleted UIDs are remembered for the process, so iOS's reconnect replay doesn't bring them back | — | — | L | H | L | L | ✔ (fake data) | The UI says "Stays on your iPhone". Calls in progress are never cleared. |
| Notification actions | 🟡 LIMITED | A | ANCS Positive/Negative actions + iOS-supplied labels | | | L | H | L | L | — | **At most two actions**, and the labels come from iOS. App-defined actions (Reply, Like, Mark as Read) are **not** exposed. |
| Pop-up presentation | 🟡 LIMITED (**verified**) | W | **One UI Watch's system UI picks the style by what's in front, and an app can't choose it.** Measured 2026-10-05: watch face in front → **full-screen pop-up**, even with the screen on. An app in front → **small top pop-up** (`w_noti_popup_small`, about 3 s). Screen off → full-screen (on wrist raise, or immediately with Samsung's "Turn on screen"). Off-body → none. Anything that vibrates gets a pop-up, whatever its importance. **NOT POSSIBLE WITH CURRENT PUBLIC API:** a small banner over the watch face. Waking the screen first was tried and still gave full-screen (it lands on the watch face), so it was removed along with its permissions. **Options:** *Standard* (Samsung default) or *Subtle* (posted silently, so no pop-up, and Bridge vibrates the watch itself) | — | — | L | H | L | L | ✔ | A custom banner would need a full-screen-intent activity: Play restricts FSI to calls/alarms (high Play risk), so not pursued. **Duration (measured):** Samsung decides this too. A pop-up that woke the screen uses a fixed `setCustomTimeout 7000` (7 s). With the screen already on, it lasts until the watch screen timeout (15 s on the test watch). Apps can't close another app's pop-up or turn the screen off. User options: shorter screen timeout, palm-to-cover, swipe, or Subtle. |
| History | ✅ FULL (implemented) | W | In memory only. **Off by default**: the inbox mirrors what's on the iPhone now. "Keep history" (set from the iPhone app or watch) keeps notifications for 24 h after iOS clears them | — | — | L | H | L | L | — | Never kept: hidden-content apps (banking/OTP) and calls in progress. Turning it off deletes the history at once. Nothing is written to disk. |
| Filtering | ✅ FULL (implemented) | W | `NotificationPolicy`: modes All / Important only / Messages & calls / Calls only, plus per-app on/off and vibrate (`PolicyStore`, local) | — | — | L | H | L | L | — | Filtered notifications stay in the inbox quietly. A disabled app is dropped entirely. |
| Sensitive redaction | ✅ FULL (implemented) | W | Per-app "Hide content". Finance apps (catalog kind or iOS Business & Finance category) are hidden by default. Calls are never masked | — | — | L | H | L | L | — | The posted notification shows only the app name. Content appears only in Bridge after "Show content". OTP apps aren't distinguishable by category: they need a per-app rule. |
| Previews / iOS "Show Previews" setting | 🧪 EXPERIMENTAL | A | iOS behaviour undocumented | | | — | — | — | — | — | *Verify* whether "Show Previews: When Unlocked/Never" blanks the ANCS `Message`. |
| Attachments / images | ⛔ NOT POSSIBLE | — | ANCS carries text only | — | — | — | — | — | — | — | **Alternative:** a "📷 Photo" style hint when the body indicates media. |
| Silent / important flags | ✅ FULL | A | EventFlags `Silent`, `Important` | | | L | H | L | L | — | `Silent` → no haptic. |
| Pre-existing on reconnect | ✅ FULL | A | EventFlag `PreExisting` | | | L | H | L | L | — | **Reconnect grace:** never buzz for pre-existing notifications. |
| WhatsApp | 🟡 LIMITED | A | as above | | | L | H | L | L | — | Text, groups, mentions, reactions and media *descriptions* appear in whatever form iOS shows them. Actions: usually only "Clear". |
| iMessage | 🟡 LIMITED | A | as above | | | L | H | L | L | — | No reply (see §3). |
| Telegram / Instagram / Gmail / Slack / Teams / Discord | 🟡 LIMITED | A | as above | | | L | H | L | L | — | Generic engine. There's no app-specific code. |
| Banking apps | 🟡 LIMITED | A + W | as above + masking | | | L | H | L | L | — | Masked by default (body hidden until tapped). |
| Authentication / OTP | 🟡 LIMITED | A + W | as above + masking | | | L | H | L | L | — | Masked by default. History is never kept for these. |
| Notification forwarding via Apple *AccessoryNotifications* (rich content, icons, replies) | ⛔ NOT POSSIBLE (this region) | — | iOS 26.5+, **EU only**, restricted entitlements, AccessorySetupKit, HPKE | — | — | — | — | H | — | — | An optional future path for EU users only. Never a dependency. |

## 2. Calls

> **CALL CONTROL and CALL AUDIO are separate problems.** ANCS can only *control* the ringing call. Audio needs
> HFP, which a third-party Wear OS app **cannot** drive (see `CALL_AUDIO.md`).

| Capability | Status | Path | API / protocol | Permission | Background | Batt | Rel | AS | GP | HW | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Incoming cellular call detection | ✅ FULL (**verified**, 66–115 ms) | A | ANCS Category `IncomingCall` | as §1 | Watch FGS | L | H | L | M | — | |
| Caller name | 🟡 LIMITED | A | ANCS `Title` (iOS resolves the contact) | | | L | H | L | L | — | Shows the iOS contact name, or the number/"Unknown". |
| Caller number | 🟡 LIMITED | A | ANCS `Title` / `Subtitle` | | | L | M | L | L | — | Only appears when iOS shows it (unknown callers). The number isn't exposed separately for known contacts. |
| Incoming call UI on watch | ✅ FULL | W | Full-screen activity / `CallStyle`-like surface | `USE_FULL_SCREEN_INTENT` / POST_NOTIFICATIONS | | L | H | L | M | — | |
| Answer | 🟡 LIMITED (**verified**) | A | Perform Notification Action, using whichever action iOS labels "Answer" (**not** a hard-coded ID) | | | L | H | L | L | ✔ | Expected to answer on the iPhone, with **audio staying on the iPhone's current route**. Milestone-1 test. |
| Decline | ✅ FULL (**verified**) | A | Perform action, using the action iOS labels "Decline" | | | L | H | L | L | ✔ | 115 ms. iOS then posts a MissedCall (Dial / Clear). |
| Silence ringer only ("dismiss") | ⛔ NOT POSSIBLE | — | ANCS has no silence action | — | — | — | — | — | — | — | **Alternative:** dismiss the watch surface only. The UI says "Still ringing on iPhone". |
| End active call | 🟡 LIMITED (**verified, undocumented**) | A | iOS posts an **undocumented Category 12** "active call" notification while a call is connected. Its only action is negative, labelled **"End Call"**, and performing it ends the cellular call (76 ms) | as §1 | Watch FGS | L | M | M | L | ✔ | Not in Apple's ANCS spec: an iOS update could change or remove it. The UI uses iOS's label and hides the button if the category disappears. |
| Mute / hold | ⛔ NOT POSSIBLE | — | No public API on either side | — | — | — | — | — | — | — | |
| Switch audio route | ⛔ NOT POSSIBLE | — | iOS route is user-controlled (Control Center / call UI) | — | — | — | — | — | — | — | |
| Outgoing calls | ⛔ NOT POSSIBLE (direct) / 🟠 BEST EFFORT (handoff) | B | iOS can't place a call from the background. **Alternative:** the watch sends "call X" → the iOS app posts a local notification "Call Rahul?" → the user taps on the iPhone → `tel:` | Contacts, Notifications | iOS app relaunchable | L | M | M | L | — | Must be honest in the UI: "Continue on iPhone". |
| Contacts-based calling | 🟠 BEST EFFORT | B | as above | | | | | | | — | |
| Missed call notifications | ✅ FULL (**verified**) | A | Category `MissedCall` | | | L | H | L | L | ✔ | iOS labels are **"Dial" / "Clear"**. Dial would ring out **on the iPhone** (not yet tested). |
| Voicemail | ✅ FULL | A | Category `Voicemail` | | | L | H | L | L | — | |
| FaceTime | 🧪 EXPERIMENTAL | A | *Verify* whether it arrives as `IncomingCall` | | | | | | | — | |
| WhatsApp / Telegram / VoIP calls (CallKit) | 🧪 EXPERIMENTAL | A | *Verify* the category and actions iOS exposes | | | | | | | — | If it shows up as `IncomingCall` with Answer/Decline, the generic engine handles it. |
| Call audio on watch | 🧪 EXPERIMENTAL, likely ⛔ | H | Needs the watch in the HFP **Hands-Free** role. Android `BluetoothHeadsetClient` is **hidden/system API** | — | — | H | L | — | — | — | Experiment only. See `CALL_AUDIO.md`. |

## 3. Messaging

| Capability | Status | Path | Notes |
|---|---|---|---|
| Receive WhatsApp / iMessage | 🟡 LIMITED | A | As §1. |
| Grouping | 🟡 LIMITED | A + W | Heuristic. |
| Mark as read | ⛔ NOT POSSIBLE | — | ANCS doesn't expose app-defined actions. If iOS ever labels a positive action "Mark as Read", the generic engine shows it automatically. |
| Dismiss | 🟡 LIMITED | A | Negative action when present. |
| Predefined actions | 🟡 LIMITED | A | Only iOS's positive/negative pair. |
| Quick replies / canned responses | ⛔ NOT POSSIBLE | — | **NOT POSSIBLE WITH CURRENT PUBLIC API** (non-EU). iOS apps can't send iMessage/SMS/WhatsApp in the background. UI copy: *"Reply isn't available for this notification."* |
| Arbitrary text / voice / emoji reply | ⛔ NOT POSSIBLE | — | Same. The closest alternative is "Open on iPhone", which isn't automatable either. We show the honest message. EU users could get this via AccessoryNotifications in future. |

## 4. Contacts

| Capability | Status | Path | API | Permission | Background | Notes |
|---|---|---|---|---|---|---|
| Contact permission | ✅ FULL | iOS | `CNContactStore.requestAccess(for:)`; iOS 18+ may return `.limited` | `NSContactsUsageDescription` | — | Limited access is respected. |
| Import | ✅ FULL | B | `CNContactStore.enumerateContacts` | Contacts | iOS app foreground/BLE wake | Minimal fields only. |
| Incremental sync | 🟡 LIMITED | B | Per-contact digest diff stored on iOS (portable). `CNContactStoreDidChange` triggers it while the app is alive | | App must run | Change-history API to be evaluated later. |
| Search | ✅ FULL | W | Room FTS on the watch | — | — | |
| Caller identification | 🟡 LIMITED | A | iOS already resolves names in ANCS. The local DB adds a photo/initials | | | |
| Favorites | 🟡 LIMITED | B | No public "Phone favourites" API. **Alternative:** the user picks favourites in our app | | | |
| Offline contact cache | ✅ FULL | W | Room, encrypted at rest (Keystore-wrapped key) | | | |
| Photos | 🟡 LIMITED | B | `thumbnailImageData` | | | Sent once per hash change. Optional. |

## 5. Device

> Verified 2026-10-05: iOS exposes **Battery Service 0x180F** and **Device Information 0x180A** to the
> bonded watch, so the iPhone's battery and model need no iPhone app.

| Capability | Status | Path | API | Notes |
|---|---|---|---|---|
| iPhone battery % | ✅ FULL (**verified**) | S | Battery Service 0x180F: read at connect, then notified by iOS on change | Watch home: "iPhone 16 Pro · 81%". |
| iPhone model | ✅ FULL (**verified**) | S | Device Information Model Number (`iPhone17,1`) mapped to a marketing name | |
| iPhone charging state | ⛔ NOT POSSIBLE (without the app) / 🟠 BEST EFFORT | B | Battery Service carries only the level. `UIDevice.batteryState` needs the iPhone app alive | Not implemented: level only. |
| Watch battery / charging | ✅ FULL (implemented) | W → B | Sticky `ACTION_BATTERY_CHANGED` → DEVICE_STATUS push (debounced, no polling) | iPhone home: battery, charging, notifications on/off, last sync. |
| Connection status | ✅ FULL | W, B | GATT callbacks / CoreBluetooth | |
| Signal strength (watch LTE) | ✅ FULL | W | `TelephonyManager.getSignalStrength()` | |
| Signal strength (iPhone cellular) | ⛔ NOT POSSIBLE | — | No public iOS API | Hidden from the UI. |
| Find phone | 🟠 BEST EFFORT (implemented) | B | Watch → EVENTS notify → iOS app woken (bluetooth-central). Foreground: looping in-app sound (plays even on silent) + full-screen "Found It". Background: local notification with a bundled 29.5 s sound | Fails if the app was **force-quit** (the watch then says "Can't reach the iPhone app"). In the background the ring switch and Focus apply. Time Sensitive needs a **paid** developer team (personal teams can't sign it, verified). Critical Alerts need an Apple entitlement (unlikely). |
| Find watch (iPhone → watch) | ✅ FULL while connected (implemented) | B | CONTROL write → watch alarm sound + vibration + "Found it", 60 s cap | Needs the iPhone app open and the watch connected. |
| Watch → phone command channel | 🟡 LIMITED (implemented) | B | EVENTS characteristic | Only while the iOS app is alive/relaunchable. |
| Automatic reconnection | 🟡 LIMITED | A, B | Watch advertises (low power) while disconnected. iOS reconnects bonded ANCS consumers. iOS app keeps a pending `connect()` | *Verify* iOS system-level reconnect timing. |
| Reconnect after watch reboot | 🟡 LIMITED | W | `BOOT_COMPLETED` → FGS `connectedDevice` (allowed from boot) | |
| Reconnect after iPhone reboot | 🟡 LIMITED | A | ANCS resumes after first unlock (*verify*). Bridge resumes when iOS relaunches the app or the user opens it | |
| Reconnect after BT toggle / airplane mode | ✅ FULL (*verify*) | W | `ACTION_STATE_CHANGED` receiver | |

## 6. Health

> We separate **real-time sensor data** (Health Services), **historical data recorded by our app**, and
> **Samsung Health's own database** (not accessible).

| Capability | Status | Path | API | Notes |
|---|---|---|---|---|
| Steps | 🟡 LIMITED | W → B → HealthKit | Health Services `PassiveMonitoringClient` (`STEPS_DAILY`) → `HKQuantityType(.stepCount)` | Only from when our app registers onward. HealthKit is writable only while the iPhone is unlocked. |
| Heart rate | 🟡 LIMITED | W → B → HK | `MeasureClient` / passive `HEART_RATE_BPM` | Batches, not streaming. |
| Workouts | 🟡 LIMITED | W → B → HK | `ExerciseClient` (workouts **our** app records) → `HKWorkoutBuilder` | Samsung Health workouts aren't accessible. |
| Calories / distance | 🟡 LIMITED | W → B → HK | Passive `CALORIES_DAILY`, `DISTANCE_DAILY` | |
| Sleep | ⛔ NOT POSSIBLE | — | Health Services has no sleep stages. Samsung Health sleep is in Samsung's DB | |
| SpO2 | ⛔ NOT POSSIBLE (public) | — | Not in Health Services. Samsung Health **Sensor SDK** needs partner approval | Revisit only if partnership is granted. |
| HealthKit write | ✅ FULL | iOS | HealthKit, minimum write types | `NSHealthUpdateUsageDescription` |
| Historical sync (pre-install) | ⛔ NOT POSSIBLE | — | No public access to Samsung Health history on the watch | |
| Real-time sync | 🟠 BEST EFFORT | B | Bridge while the app is alive | Batched to save battery. |
| Known risk | — | — | One UI 8 Watch on Watch4 had reported sensor regressions (later patched) | |

## 7. Media

| Capability | Status | Path | API | Notes |
|---|---|---|---|---|
| AMS availability | ✅ FULL (**verified**) | M | Service present, notifications and attribute registration succeed after ANCS is READY | Started after ANCS. Failure never affects notifications. |
| Play/pause, next/previous | ✅ FULL (implemented) | M | AMS Remote Command, gated by iOS's supported-command list | Works for any app using `MPNowPlayingInfoCenter`, including Apple Music and Spotify. The iOS app isn't needed. Live-player test: M2.4. |
| Volume | ✅ FULL (**verified** command path) | M | AMS `VolumeUp/Down` (relative), crown/bezel on the Now playing screen, plus the player volume attribute for the level indicator | Commands accepted in ~20 ms. |
| Now-playing metadata | ✅ FULL (implemented) | M | AMS Entity Update: Player name, Track title/artist/album/duration, playback info. Truncated values re-read via Entity Attribute | Artwork isn't available (**NOT POSSIBLE WITH CURRENT PUBLIC API**). Progress shown as an edge ring, extrapolated locally. |
| Apple Music / Spotify | ✅ FULL (implemented) | M | as above | |

## 8. Other

| Capability | Status | Path | Notes |
|---|---|---|---|
| Calendar alerts | ✅ FULL | A | `Schedule` category notifications. |
| Calendar agenda | 🟠 BEST EFFORT | B | EventKit read on iOS → bridge. Needs the app alive. |
| Reminders | 🟡 LIMITED | A / B | Alerts via ANCS. The list sync uses EventKit and is best effort. |
| Navigation | 🟠 BEST EFFORT | A | Only if the nav app posts regular notifications (Google Maps does; Apple Maps mostly uses Live Activities, which aren't forwarded). |
| Weather | ✅ FULL | W | Use the watch's own LTE/Wi-Fi (watch-local). No phone dependency. |
| iPhone alarms / timers (read/sync) | ⛔ NOT POSSIBLE | — | There's no public API to read iOS Clock alarms. **Alternative:** watch-local alarms (Samsung Clock) work standalone. iOS AlarmKit only manages *our* app's alarms. |
| iPhone Focus status | 🟡 LIMITED | B | `INFocusStatusCenter` gives an `isFocused` boolean only, with user authorization. |
| Change iPhone Focus/DND from watch | ⛔ NOT POSSIBLE | — | |
| Mirror Focus → watch DND | 🧪 EXPERIMENTAL | W | `NotificationManager.setInterruptionFilter` needs ACCESS_NOTIFICATION_POLICY. *Verify* the grant flow exists on Wear OS. |
| Battery optimisation exemption | 🟡 LIMITED | W | The FGS is enough. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is restricted by Play policy, so we avoid it. |
| Current time sync | ✅ FULL (*verify*) | S | CTS 0x1805. Mostly irrelevant, because the watch has network time. |
| Emergency information | ⛔ NOT POSSIBLE (iPhone Medical ID) | — | iOS Medical ID isn't accessible to apps. The watch's own Samsung SOS works over LTE, independent of this app. |

## 9. Hard platform facts this matrix rests on
- ANCS gives **two actions max**, with iOS-defined labels. There are no icons and no reply.
- iOS apps **cannot** read other apps' notifications. The architecture never tries to.
- CallKit **cannot** control cellular calls in Phone.app.
- Android's HFP client role is not available to third-party apps.
- Apple AccessoryNotifications is EU-only and entitlement-gated.
- Wear OS on the Watch4 still needs its first-time setup with an Android phone. After that it runs standalone (confirmed for this user's watch).
