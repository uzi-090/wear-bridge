# Bridge: bring your Wear OS watch closer to your iPhone

A local-only bridge between an iPhone and a Wear OS watch (primary target: Galaxy Watch4 LTE),
built only on public APIs and documented Bluetooth protocols. The watch reads iPhone notifications
**directly from iOS over ANCS**. No cloud, account or analytics.

**Status: Phase 1, ANCS proof of concept. Not yet verified on hardware.** See `docs/TEST_PLAN.md` §M1.

| Path | What |
|---|---|
| `docs/` | Capability matrix, architecture, risk register, ANCS notes, protocol, security, call-audio research, test plan |
| `wearos/ancs` | Pure Kotlin ANCS protocol + session (unit-tested) |
| `wearos/app` | Wear OS app: BLE peripheral + GATT client, state machine, notifications, call surface |
| `ios/` | `PairingPOC`: SwiftUI companion that pairs and keeps the link |

## Build

```bash
# Watch (needs JDK 17; the Android SDK path goes in wearos/local.properties)
cd wearos && ./gradlew :ancs:test :app:testDebugUnitTest :app:assembleDebug

# iPhone (XcodeGen generates the project)
cd ios && xcodegen && open Bridge.xcodeproj
```

Read `docs/CAPABILITY_MATRIX.md` before promising any feature. A lot of what Android phones give a
watch is **not possible with current public iOS APIs**.
