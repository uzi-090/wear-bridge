# Capability Matrix

> **Status of this document:** Phase 0, a research-based assessment. **Nothing here has been verified on
> hardware yet.** Every row has a `HW` column that changes from `—` to `✔` (or `✘`) only after it has
> been demonstrated on an iPhone 16 Pro + Galaxy Watch4 LTE (One UI 8 Watch / Wear OS 6). Rows marked
> *verify* are plausible from the specs and community experience, but the exact behaviour is undocumented.
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
| App icon | ⛔ NOT POSSIBLE | — | ANCS has no icon attribute; iOS gives no public API to read other apps' icons | — | — | — | — | — | — | — | **Alternative:** a bundled `bundle_id → glyph/colour` map shipped with the app, plus a clean monogram fallback. Brand logos need a trademark review before shipping (see the Risk Register). |
| Timestamp | 🟡 LIMITED | A | ANCS attr `Date` (`yyyyMMdd'T'HHmmSS`) | | | L | H | L | L | — | Local time with **no timezone**. We interpret it in the watch's zone, which is the same zone in practice. |
| Category | ✅ FULL | A | ANCS `CategoryID` (Other, IncomingCall, MissedCall, Voicemail, Social, Schedule, Email, News, Health, Business, Location, Entertainment) | | | L | H | L | L | — | Assigned by iOS and coarse. |
| Grouping | 🟡 LIMITED | A + W | No thread ID in ANCS | — | — | L | M | L | L | — | Watch-side heuristic: group by app, then by title (sender/chat). |
| Notification updates | ✅ FULL | A | EventID `Modified` | | | L | H | L | L | — | Re-fetch attributes, update in place, no repeat haptic. |
| Notification removal (iPhone → watch) | ✅ FULL | A | EventID `Removed` | | | L | H | L | L | — | When the user clears it on the iPhone, it disappears from the watch. |
| Dismissal (watch → iPhone) | 🟡 LIMITED | A | *Perform Notification Action* `Negative`, when the `NegativeAction` flag is set (label is typically "Clear") | | | L | H | L | L | — | Only when iOS advertises a negative action. Otherwise the dismissal is watch-local and we say so. |
| Notification actions | 🟡 LIMITED | A | ANCS Positive/Negative actions + iOS-supplied labels | | | L | H | L | L | — | **At most two actions**, and the labels come from iOS. App-defined actions (Reply, Like, Mark as Read) are **not** exposed. |
| History | ✅ FULL | W | Room (opt-in), in-memory by default | — | — | L | H | L | L | — | Minimal retention, can be turned off. |
| Filtering | ✅ FULL | W | Watch-side policy by bundle ID / category / flags | — | — | L | H | L | L | — | iOS also supports per-device "Share System Notifications" (all-or-nothing). |
| Sensitive redaction | ✅ FULL | W | Watch-side masking policy | — | — | L | H | L | L | — | Defaults to masking banking/OTP categories. |
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

| Capability | Status | Path | API | Notes |
|---|---|---|---|---|
| iPhone battery % | 🟡 LIMITED | S, fallback B | Battery Service 0x180F (*verify iOS exposes it*), else `UIDevice.batteryLevel` over the bridge | |
| iPhone charging state | 🟠 BEST EFFORT | B | `UIDevice.batteryState` | Needs the app alive. |
| Watch battery / charging | ✅ FULL | W → B | `BatteryManager` | Shown on iPhone. |
| Connection status | ✅ FULL | W, B | GATT callbacks / CoreBluetooth | |
| Signal strength (watch LTE) | ✅ FULL | W | `TelephonyManager.getSignalStrength()` | |
| Signal strength (iPhone cellular) | ⛔ NOT POSSIBLE | — | No public iOS API | Hidden from the UI. |
| Find phone | 🟠 BEST EFFORT | B | Watch → bridge write → iOS app woken (bluetooth-central) → local notification with sound (+ Time-Sensitive) | Fails if the app was **force-quit**. Ringer switch / Focus may silence it. Critical Alerts need an Apple entitlement (unlikely to be granted). |
| Watch → phone command channel | 🟡 LIMITED | B | Bridge GATT | Only while the iOS app is alive/relaunchable. |
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
| Play/pause, next/previous | ✅ FULL (*verify*) | M | AMS Remote Command | Works for any app using `MPNowPlayingInfoCenter`, including Apple Music and Spotify. The iOS app isn't needed. |
| Volume | ✅ FULL (*verify*) | M | AMS `VolumeUp/Down` (relative) + player volume attribute | |
| Now-playing metadata | ✅ FULL (*verify*) | M | AMS Entity Update: Player name, Track title/artist/album/duration, playback info | Artwork isn't available. |
| Apple Music / Spotify | ✅ FULL (*verify*) | M | as above | |

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
