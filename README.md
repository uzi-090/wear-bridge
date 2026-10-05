# Bridge: bring your Wear OS watch closer to your iPhone

A local-only bridge between an iPhone and a Wear OS watch (primary target: Galaxy Watch4 LTE),
built only on public APIs and documented Bluetooth protocols. The watch reads iPhone notifications
**directly from iOS over ANCS**. No cloud, account or analytics.

**Status:** notifications, calls (Answer/Decline/End), media controls and the iPhone's battery are
verified on an iPhone 16 Pro + Galaxy Watch4 Classic LTE. The watch inbox, Find iPhone/watch and the
iPhone companion app are implemented, with hardware tests listed in `docs/TEST_PLAN.md` §M2.
Start with `docs/STATUS.md`.

| Path | What |
|---|---|
| `docs/` | Capability matrix, architecture, risk register, ANCS notes, protocol, security, call-audio research, test plan |
| `wearos/ancs` | Pure Kotlin ANCS protocol + session (unit-tested) |
| `wearos/ams` | Pure Kotlin Apple Media Service protocol + Now Playing model (unit-tested) |
| `wearos/protocol` | Bridge GATT framing and messages, shared with iOS (unit-tested) |
| `wearos/app` | Wear OS app: BLE peripheral + GATT client, state machine, notifications, calls, media, Find iPhone/watch |
| `ios/` | SwiftUI companion (target `PairingPOC`, shown as "Bridge"): onboarding, settings, Find, diagnostics |

## Build

```bash
# Watch (needs JDK 17; the Android SDK path goes in wearos/local.properties)
cd wearos && ./gradlew :ancs:test :ams:test :protocol:test :app:testDebugUnitTest :app:assembleDebug

# iPhone (XcodeGen generates the project)
cd ios && xcodegen && open Bridge.xcodeproj
```

Read `docs/CAPABILITY_MATRIX.md` before promising any feature. A lot of what Android phones give a
watch is **not possible with current public iOS APIs**.
