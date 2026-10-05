# Bridge BLE protocol

> Status: **design + Phase 1 subset**. Only `PAIRING` (an encrypted read, used to force bonding) and
> `CONTROL` ping are implemented.

## Service
`BRIDGE_SERVICE` = `9C664C93-19F2-435D-895A-A40751731AE1`, hosted by the **watch** (GATT server).
Characteristic UUIDs replace the 4th group with a 4-hex-digit index: `9C664C93-19F2-435D-XXXX-A40751731AE1`.

| Index | Name | Props | Perms | Phase |
|---|---|---|---|---|
| 0001 | PAIRING | read | encrypted | 1 (returns protocol version + capability bits) |
| 0002 | CONTROL | write | encrypted | 1 (ping), later commands |
| 0003 | EVENTS | notify | encrypted | planned |
| 0004 | CONTACTS | write | encrypted | planned |
| 0005 | DEVICE_STATUS | read, notify | encrypted | planned |
| 0006 | SETTINGS | read, write | encrypted | planned |
| 0007 | FILE_TRANSFER | write-without-response, notify | encrypted | planned |
| 0008 | HEALTH | notify | encrypted | planned |
| 0009 | ACK | notify | encrypted | planned |

## PAIRING read value (Phase 1)
`[protocolVersion u8 = 1][capabilities u32 LE]`. The capabilities bits are bit0 ANCS client, bit1 AMS client (reserved).

## Frame format (planned, v1)
```
| ver u8 | type u8 | flags u8 | seq u32 | len u16 | payload[len] | tag[16] |
```
- `seq`: per-direction monotonic counter. The receiver rejects `seq <= lastSeen` (replay).
- Fragmentation: frames bigger than MTU−3 are split. `flags.bit0` = more fragments.
- `tag`: AES-128-GCM over header+payload with a session key from P-256 ECDH, done on first connect over the
  bonded link and confirmed by both apps (keys in Keychain / Android Keystore). The nonce = direction ‖ seq.
- ACK: `type=ACK` carries the acknowledged seq. Retries at 500 ms, 1 s and 2 s, then fail.
- Version negotiation: both sides read PAIRING. The lowest common version wins.
- Payload encoding: a compact TLV. No JSON on the wire.
