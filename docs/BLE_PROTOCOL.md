# Bridge BLE protocol

> Status: `PAIRING`, `CONTROL`, `EVENTS`, `DEVICE_STATUS` and `SETTINGS` are **implemented** (v1.1).
> `SETTINGS` is verified on hardware. The others are built and unit-tested, and their hardware tests
> are in TEST_PLAN.md §M2. `CONTACTS`, `FILE_TRANSFER`, `HEALTH` and `ACK` are designs.
> App-layer encryption is designed below (Phase 10). Today every characteristic relies on the bonded,
> encrypted LE link.

The watch also consumes Apple's own services directly (no iPhone app needed): **ANCS** (notifications,
see ANCS.md), **AMS** (media), **Battery Service 0x180F** and **Device Information 0x180A**. Those are
Apple/Bluetooth SIG protocols, described at the end.

## Service
`BRIDGE_SERVICE` = `9C664C93-19F2-435D-895A-A40751731AE1`, hosted by the **watch** (GATT server).
Characteristic UUIDs replace the 4th group with a 4-hex-digit index: `9C664C93-19F2-435D-XXXX-A40751731AE1`.

| Index | Name | Props | Perms | Status |
|---|---|---|---|---|
| 0001 | PAIRING | read | encrypted | implemented: protocol version + capability bits |
| 0002 | CONTROL | write | encrypted | implemented: iPhone → watch commands |
| 0003 | EVENTS | notify | encrypted CCCD | implemented: watch → iPhone events |
| 0004 | CONTACTS | write | encrypted | planned |
| 0005 | DEVICE_STATUS | notify | encrypted CCCD | implemented: watch status pushes |
| 0006 | SETTINGS | write, notify | encrypted | implemented: notification rules and watch preferences |
| 0007 | FILE_TRANSFER | write-without-response, notify | encrypted | planned |
| 0008 | HEALTH | notify | encrypted | planned |
| 0009 | ACK | notify | encrypted | planned |

## PAIRING read value
`[protocolVersion u8 = 1][capabilities u32 LE]`.

| Bit | Capability |
|---|---|
| 0 | ANCS client (notifications) |
| 1 | AMS client (media) |
| 2 | SETTINGS channel |
| 3 | DEVICE_STATUS |
| 4 | Find iPhone / Find watch |
| 5 | Watch preferences (SET_PREFS, snapshot trailer) |

The iPhone app shows these under Settings › Advanced › Diagnostics.

## Chunk framing (all bridge characteristics)
`wearos/protocol/Framing.kt`, `ios/PairingPOC/BridgeProtocol.swift`:
`| version u8 = 1 | type u8 | messageId u8 | flags u8 (bit0 = more) | data… |`. Each chunk fits one
ATT packet (ATT_MTU − 3). There are no prepared/long writes. Chunks of one message are consecutive
(the watch's notification queue is FIFO across characteristics), and a new message abandons an
unfinished one. Limits: SETTINGS requests 4 KB (icons 32 KB), CONTROL 1 KB.

Encoding: `str` = u8 length + UTF-8 (≤ 255 bytes, cut on a character boundary). u16/u32 are big-endian.
Booleans are bit flags. Tri-state = 0 false, 1 true, 2 default.

## SETTINGS (iPhone app edits, watch stores and applies)
Notification rules live **on the watch**, because ANCS goes straight to it. The iPhone app is only the editor.

| Type | Direction | Payload |
|---|---|---|
| `0x01` GET_SETTINGS | iOS → watch | empty |
| `0x02` SET_APP_RULE | iOS → watch | str bundleId, u8 flags (bit0 enabled, bit1 vibrate), u8 hide (tri-state) |
| `0x03` SET_MODE | iOS → watch | u8 mode (0 all, 1 important, 2 messages & calls, 3 calls only) |
| `0x04` SET_APP_ICON | iOS → watch | str bundleId, image bytes (JPEG/PNG ≤ 32 KB, 16–256 px) |
| `0x05` SET_PREFS | iOS → watch | u8 popup style (0 standard, 1 subtle), u8 flags (bit0 keep history, bit1 experimental End Call) |
| `0x81` SETTINGS | watch → iOS | u8 mode, u16 count, then per app: str bundleId, str name, u8 flags (bit0 enabled, bit1 vibrate, bit2 hiddenByDefault, bit3 hasIcon), u8 hide, u32 ARGB colour, str logo slug. **Trailer (v1.1):** u8 popup style, u8 prefs flags |

Decoders read the trailer only if at least 2 bytes remain, so v1.0 and v1.1 interoperate.
The watch sends SETTINGS when the iPhone subscribes, on GET_SETTINGS, and (debounced 400 ms) after any
change, including a new app sending its first notification. Rules for apps the watch hasn't seen are ignored.

## CONTROL (iPhone → watch)
| Type | Payload | Effect |
|---|---|---|
| `0x10` FIND_WATCH | u8 1 start / 0 stop | Watch plays its alarm sound and vibrates, with a "Found it" action, for at most 60 s |
| `0x11` FIND_PHONE_STOPPED | empty | The user tapped Found It on the iPhone. The watch's Find iPhone screen shows "Found it" |
| `0x12` PING | empty | The watch answers with a STATUS push |

## EVENTS (watch → iPhone)
| Type | Payload | Effect |
|---|---|---|
| `0x90` FIND_PHONE | u8 1 start / 0 stop | iPhone app rings (foreground: looping sound; background: alert with a 29.5 s sound) |
| `0x91` FIND_WATCH_STOPPED | empty | The user tapped Found it on the watch |

Events reach the iPhone app only while it's connected and subscribed. iOS wakes a suspended app for
them (`bluetooth-central` background mode), but **not** an app the user force-quit. The watch knows
whether anyone is subscribed and tells the user "Can't reach the iPhone app" instead of failing silently.

## DEVICE_STATUS (watch → iPhone)
`0xA0` STATUS: u8 battery (0–100, 255 = unknown), u8 flags (bit0 charging, bit1 notifications ready =
ANCS subscribed, bit2 media ready = AMS ready), u16 inbox count, str model (`Build.MODEL`), str OS
version, str app version. Pushed on subscribe, on PING, and (debounced 500 ms) when any field changes.
There is no polling: battery changes come from the watch's sticky `ACTION_BATTERY_CHANGED` broadcast.

## Apple and SIG services the watch consumes (no iPhone app involved)

Discovered on the test iPhone (iOS, iPhone 16 Pro, 2026-10-05): `0x1800 0x1801 DeviceInfo Continuity
AppleNearby Battery CurrentTime ANCS AMS` + 2 unidentified Apple services.

### AMS (Apple Media Service)
Service `89D3502B-0F36-433A-8EF4-C502AD55F8DC`. Remote Command `9B3C81D8-…` (write a command ID,
notify = list of supported commands), Entity Update `2F7CABCE-…` (write `EntityID AttrID…` to register,
notify `EntityID AttrID Flags Value`), Entity Attribute `C6B2F38C-…` (write `EntityID AttrID`, then
read the full value of a truncated attribute). Implemented in `wearos/ams` (pure Kotlin, unit-tested)
and `AmsClient`. Registered: Player {Name, PlaybackInfo, Volume}, Track {Artist, Album, Title, Duration}.
Playback position is extrapolated from `elapsed + rate × Δt`, never polled.
**Verified:** the service and characteristics are present, subscription and registration succeed, and
VolumeUp/VolumeDown writes are accepted (~20 ms). Play/pause/skip with a live player: TEST_PLAN M2.4.

### Battery Service 0x180F / Device Information 0x180A
Battery Level 0x2A19 is read at connect and then notified by iOS on change. Model Number 0x2A24 gives
the model identifier (`iPhone17,1` = iPhone 16 Pro). **Verified on hardware.**

## App-layer security (designed, Phase 10)
**Why it matters:** the LE bond belongs to the iPhone, not to our app. Any iOS app can connect to the
bonded watch through CoreBluetooth and use the bridge characteristics over the already-encrypted link.
App-layer authentication closes that (Risk R-22).

```
| ver u8 | type u8 | flags u8 | seq u32 | len u16 | payload[len] | tag[16] |
```
- One-time app pairing: P-256 ECDH between the iPhone app (key in Keychain) and the watch (key wrapped
  by an Android Keystore AES key), confirmed by the user comparing a 6-digit code on both screens.
- Per connection: both sides send 16-byte nonces. Session keys = HKDF-SHA256(shared secret,
  nonceA ‖ nonceB, "bridge v2"), one AES-128-GCM key per direction.
- `seq`: per-direction monotonic counter. The receiver rejects `seq ≤ lastSeen` (replay). GCM nonce = direction ‖ seq.
- ACK: `type=ACK` carries the acknowledged seq. Retries at 500 ms, 1 s and 2 s, then fail.
- Version negotiation via PAIRING: the lowest common version wins.
