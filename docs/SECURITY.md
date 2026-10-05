# Security

> Status: **Phase 1**. Link-layer security only. App-layer encryption is designed in `BLE_PROTOCOL.md` (Phase 10).

## Principles
- Everything is local. No servers, no analytics, no telemetry.
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

## Planned (Phase 10)
App-layer ECDH session keys, AES-GCM frames, replay counters, Keychain / Android Keystore storage,
bonded-identity allow-list on the watch, and wiping on unpair.

## Threat model (summary)
Spoofed watch / phone, replayed or forged control packets, malicious nearby device, lost/stolen phone or
watch, notification / contact / health leakage, MITM at pairing, corrupted payloads, stale sessions.
See `RISK_REGISTER.md` R-14..R-16.
