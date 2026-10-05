# Security

> Status: **Phase 1**. Link-layer security only. App-layer encryption is designed in `BLE_PROTOCOL.md` (Phase 10).

## Principles
- Everything is local. No servers, no analytics, no telemetry. **Sole network call:** the iPhone app asks
  Apple's App Store lookup (`itunes.apple.com/lookup`, HTTPS) for app icons by bundle ID. That reveals to Apple
  which apps notify the watch (no content, names or identifiers). Cached on the iPhone, so each app is looked up once.
- Icons received by the watch are size- and format-checked (≤ 32 KB, 16–256 px, decodable) and stored in
  app-private storage under a sanitised file name.
- Minimum permissions on both platforms.
- Notification text, contact names, phone numbers, health values and keys are **never logged**.
  All diagnostics go through `Redact` / `DiagLog`.

## Phase 1 controls
| Threat | Control |
|---|---|
| Eavesdropping on ANCS | ANCS requires an encrypted bonded link (LE Secure Connections, AES-CCM) |
| MITM during pairing | Numeric comparison: both devices have a display + yes/no, so LESC uses Numeric Comparison. The user confirms that the codes match |
| Unbonded central reading bridge data | Every bridge characteristic is `PERMISSION_*_ENCRYPTED` |
| Watch reading notifications from a non-bonded phone | ANCS discovery only proceeds once the link is bonded |
| Leakage via logs | Redaction chokepoint. `adb logcat` shows only bundle IDs, categories and latencies |
| Data at rest | Phase 1 keeps notifications in memory only |

## Session 2 additions
| Area | Control |
|---|---|
| History | Off by default and in memory only. Never kept for hidden-content (banking/OTP) apps or calls. Turning it off deletes it at once |
| Media | Track titles are personal data: `NowPlaying.toString()` is redacted and only state/counts are logged |
| Watch status | Model, OS version, app version, battery, counts. No identifiers or content |
| Find watch/iPhone | No content. The watch only rings when the bonded iPhone writes CONTROL |
| Debug hooks | Debug builds only, behind the `DUMP` permission (adb shell). Demo mode blocks real content so screenshots can't capture it |

## App-layer security (implemented, R-22)
The LE bond belongs to the iPhone, not to the Bridge app, so another iOS app could otherwise use the
bridge service. Now:

| Threat | Control |
|---|---|
| Another iPhone app pretends to be Bridge | It lacks the Bridge app's private key (Keychain, this device only), so its HELLO fails verification; a new key needs **Allow** on the watch with a 6-digit code shown on both screens |
| Fake watch | The iPhone pins the watch's key at setup; a different key shows "Your watch has a new identity" and needs the user's confirmation |
| Replayed / reordered / forged / moved messages | AES-128-GCM, per-direction keys, seq in the nonce, AAD = version ‖ type; receivers reject seq ≤ last |
| Session hijack / reset by a nearby app | HELLO carries an HMAC only the key holder can make, so nobody else can start or reset a session |
| Key theft at rest | Watch EC key wrapped by an Android Keystore AES-256-GCM key; no backup. iPhone key `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` |
| Unauthenticated traffic | The watch sends nothing and accepts nothing on the bridge before the session exists |

Verified on hardware (TEST_PLAN M2.13) and by cross-platform test vectors generated with CryptoKit.

## Earlier design notes
App-layer ECDH session keys, AES-GCM frames, replay counters, Keychain / Android Keystore storage,
bonded-identity allow-list on the watch, and wiping on unpair.

## Threat model (summary)
Spoofed watch / phone, replayed or forged control packets, malicious nearby device, lost/stolen phone or
watch, notification / contact / health leakage, MITM at pairing, corrupted payloads, stale sessions.
See `RISK_REGISTER.md` R-14..R-16.
