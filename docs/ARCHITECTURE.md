# Architecture

> Status: **Phase 1 (ANCS proof of concept)**. The sections marked *(planned)* are designs, not code.

## 1. Guiding decisions

1. **The watch consumes ANCS directly.** The iOS companion app never reads other apps' notifications (it
   can't, and it shouldn't). Notification mirroring keeps working when the companion app is suspended,
   killed by the system, or not installed at all.
2. **One Bluetooth LE link, two GATT directions.**
3. **Everything stays local.** There's no backend, account, analytics or cloud.
4. **Honest capabilities.** Anything iOS doesn't expose is shown as unavailable, never faked
   (see `CAPABILITY_MATRIX.md`).

## 2. Link topology

```
           iPhone (LE CENTRAL)                                  Galaxy Watch (LE PERIPHERAL)
 ┌───────────────────────────────────┐                 ┌─────────────────────────────────────────┐
 │ iOS bluetoothd                    │                 │ BridgeService (foreground, connectedDevice)
 │  GATT SERVER:                     │                 │                                         │
 │   • ANCS  7905F431-…              │◀── GATT client ─┤  AncsClient  ─┐                          │
 │   • AMS   89D3502B-… (planned)    │◀── GATT client ─┤  AmsClient    ├─ GattOpQueue ─ BluetoothGatt
 │   • Battery 0x180F / CTS 0x1805   │◀── GATT client ─┤  StdServices ─┘   (connectGatt on the   │
 │                                   │                 │                     central that connected)
 │ Companion app (CoreBluetooth      │                 │  GATT SERVER: Bridge service            │
 │  central)  ───────── GATT client ─┼────────────────▶│   9C664C93-19F2-435D-895A-A40751731AE1  │
 │                                   │                 │   (encrypted characteristics)           │
 └───────────────────────────────────┘                 └─────────────────────────────────────────┘
                       ▲             one LE link, LE Secure Connections bond             │
                       └───────────── advertising (only while disconnected) ─────────────┘
```

- **The watch advertises** the bridge service UUID and, on API 31+, an **ANCS service solicitation**. The
  advertising uses `ADVERTISE_MODE_LOW_POWER` and runs **only while no iPhone is connected**. The watch
  never scans.
- **The iPhone connects** (as iOS system or as the companion app). The first access to an encrypted
  characteristic, on either side, triggers **LE Secure Connections pairing** with numeric comparison.
- Once the watch's `BluetoothGattServerCallback` reports the connection, the watch calls
  `device.connectGatt(..., autoConnect=false, TRANSPORT_LE)` **on the same device**. Android reuses the
  existing ACL link, which gives us a GATT *client* towards the iPhone. We discover ANCS through it.
- After bonding, iOS reconnects to the advertising watch at system level, with no companion app required
  (*verify on hardware* — Risk R-03).

### Why the watch is the peripheral
- ANCS is designed for accessories that are peripherals and solicit the service.
- An iOS app in the background **can't** advertise in a way Android can see (UUIDs move to the
  Apple-only overflow area). A watch-as-central design would therefore depend on the iOS app being in the
  foreground. That's unacceptable.
- No scanning on the watch saves battery.

## 3. Wear OS module layout

```
wearos/
  ancs/            Pure Kotlin/JVM. No Android dependency. Unit-tested.
    AncsConstants, NotificationSourceEvent, AncsCommands (request encoders),
    DataSourceReassembler (fragmented responses), AncsSession (request queue, priorities,
    timeouts, actions), AncsNotification / AncsDate
  app/
    ble/           ConnectionStateMachine (pure, unit-tested)
                   ConnectionManager   – orchestrator: runs state-machine effects, single inbox
                   BridgePeripheral    – advertising + bridge GATT server
                   GattSession         – GATT client to iOS, GattOpQueue, callbacks
                   PairingManager      – LE bonding + timeout
                   ReconnectionManager – the single pending retry timer
    ancs/          AncsClient – drives AncsSession over a BluetoothGatt
    notifications/ NotificationEngine (policy: reconnect grace, coalescing, quota, action gating),
                   NotificationRepository (StateFlows), MirrorNotifier (native Wear OS notifications)
    settings/      BridgeSettings (subtle pop-ups, experimental End Call)
    service/       BridgeService (foreground, connectedDevice), BootReceiver
    diagnostics/   DiagLog (redacted ring buffer), Redact
    ui/            Compose for Wear OS: Home, Notifications, Call surface, Settings, Diagnostics
```

Mapping to the spec's suggested names: the watch is the LE *peripheral*, so there's no DeviceScanner.
BluetoothManager is the system receivers in ConnectionManager. BlePacketCodec / TransportProtocol
arrive with the custom protocol (Phase 6/10). NotificationRenderer = MirrorNotifier.
NotificationFilter / Policy / IconResolver / History are Phase 4.

**Threading:** every Bluetooth callback just posts an event into a single-threaded coroutine
dispatcher (`BridgeService.bleScope`, `limitedParallelism(1)`). That keeps state mutation serialized
with no locks, and callbacks never block. GATT operations go through `GattOpQueue`, because Android
allows only one outstanding operation per `BluetoothGatt`. Each op has a timeout. Composables only
observe `StateFlow`s and never touch BLE.

## 4. Connection state machine

```
           ┌──────────────┐ bt on / service start
           │ DISCONNECTED │──────────────────────────┐
           └──────▲───────┘                          ▼
     bt off       │                         ┌────────────────┐
  (any state) ────┘                         │  ADVERTISING   │◀──────────────┐
                                            └───────┬────────┘               │
                                  central connected │                        │
                                            ┌───────▼────────┐               │
                                            │   CONNECTED    │               │
                                            └───────┬────────┘               │
                                 connectGatt client │                        │
                                     ┌──────────────▼───────┐                │
                                     │ DISCOVERING_SERVICES │──no ANCS──▶ waits for bond,
                                     └──────────────┬───────┘   (PAIRING)    retries w/ backoff
                                                    │ ANCS found             │
                                         ┌──────────▼──────┐                 │
                                         │   SUBSCRIBING   │─ auth error ─▶ PAIRING
                                         └──────────┬──────┘                 │
                                                    │ CCCDs written          │
                                              ┌─────▼─────┐   link lost      │
                                              │   READY   │──────────────────┤
                                              └───────────┘                  │
                                   ERROR (bounded retries exhausted) ─ user "Reconnect" ┘
```

The spec states `SCANNING` and `DEVICE_FOUND` are reserved for a future central-mode transport. The
peripheral topology replaces them with `ADVERTISING`. Retries use exponential backoff
(1 s → 2 → 4 … capped at 60 s, max 8 attempts per link), then ERROR. A new link always resets the counter.
`RECONNECTING` = ADVERTISING after a link loss, so the UI can say "Reconnecting…".

## 5. ANCS data flow (Phase 1)

1. Subscribe **Data Source** CCCD, then **Notification Source** CCCD.
2. iOS replays all current notifications as `Added` + `PreExisting`. We record them **silently** (reconnect grace).
3. For each `Added`/`Modified`: queue *Get Notification Attributes* (AppId, Title 64, Subtitle 64,
   Message 512, MessageSize, Date, PositiveLabel, NegativeLabel), but only when the matching action flag
   is set for the label attributes.
4. `DataSourceReassembler` rebuilds the response across MTU-sized fragments and completes when every
   requested attribute has arrived.
5. If the app's display name isn't cached, send *Get App Attributes*.
6. Publish to `NotificationRepository`. Non-silent, non-pre-existing events trigger a haptic.
   `IncomingCall` opens the call surface.
7. User actions → *Perform Notification Action* with the action that has the **iOS-provided label**.
8. `Removed` → drop from the repository and close the call surface.

Requests are strictly one-at-a-time. ANCS responses carry no request ID beyond the UID, so pipelining
attribute requests makes reassembly ambiguous.

## 6. iOS companion (Phase 1: PairingPOC only)

- SwiftUI + CoreBluetooth central, `bluetooth-central` background mode, state restoration ID.
- Scans for the bridge service UUID → connects → reads the encrypted `PAIRING` characteristic → iOS
  pairing prompt → bonded.
- Plain-language status only. Diagnostics stay behind a disclosure.
- *(planned)* AccessorySetupKit picker (iOS 18+), contacts/health sync, settings, find-phone.

## 7. Custom bridge protocol *(planned, see `BLE_PROTOCOL.md`)*
Service `9C664C93-19F2-435D-895A-A40751731AE1`. Characteristics are derived by changing the 4th group
(`…-895A-…` → `…-0001-…` etc., see `BridgeUuids`). Binary framing: version, type, seq, length, payload,
AES-GCM tag with a session key from an ECDH (P-256) exchange authenticated by the LE bond.

## 8. Persistence
- Phase 1: in-memory only. Notifications are gone when the service dies. That's intentional, because it's minimal retention.
- *(planned)* Room for opt-in history, contacts and per-app policy, with SQLCipher-free encryption via a Keystore-wrapped key.

## 9. Observability
`DiagLog` keeps a 200-entry in-memory ring buffer of **redacted** events: state transitions, GATT
status codes, latencies, `app=<bundle-id>`, `uid=***`. Titles, bodies, names and numbers are never
passed to it. It shows up on the watch under Diagnostics and in `adb logcat -s Bridge`.

## 10. Dependencies

| Side | Dependency | Why |
|---|---|---|
| Wear | AGP 9.4, Kotlin 2.4, coroutines 1.11 | build / concurrency |
| Wear | Compose for Wear OS Material3 1.7, foundation, navigation | UI |
| Wear | AndroidX core, activity-compose, lifecycle | platform glue |
| Wear | JUnit 4 (tests only) | tests |
| iOS | SwiftUI, CoreBluetooth, Observation (system frameworks) | no third-party deps |
| Tooling | XcodeGen (generates the .xcodeproj from `project.yml`) | reproducible project file |

No Firebase, analytics SDKs, networking libraries or backend.
