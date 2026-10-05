# Bridge BLE protocol

> Status: `PAIRING`, `CONTROL` (ping) and **`SETTINGS` (v1, implemented + verified on hardware 2026-10-05)**.
> Other characteristics are designs.

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
| 0006 | SETTINGS | write, notify | encrypted | **implemented**: notification settings (see below) |
| 0007 | FILE_TRANSFER | write-without-response, notify | encrypted | planned |
| 0008 | HEALTH | notify | encrypted | planned |
| 0009 | ACK | notify | encrypted | planned |

## PAIRING read value (Phase 1)
`[protocolVersion u8 = 1][capabilities u32 LE]`. The capabilities bits are bit0 ANCS client, bit1 AMS client (reserved).

## SETTINGS channel (implemented)
iOS writes request messages and the watch answers with notifications. Notification rules are **stored and
applied on the watch** (ANCS goes straight to it), and the iPhone app is only the editor.

**Chunk framing** (`wearos/protocol/Framing.kt`, `ios/PairingPOC/BridgeProtocol.swift`):
`| version u8 = 1 | type u8 | messageId u8 | flags u8 (bit0 = more) | data… |`. Each chunk fits one ATT
packet (ATT_MTU − 3). There are no prepared/long writes. Chunks of one message are consecutive, and a new
message abandons an unfinished one. The watch caps a request at 4 KB.

| Type | Direction | Payload |
|---|---|---|
| `0x01` GET_SETTINGS | iOS → watch | empty |
| `0x02` SET_APP_RULE | iOS → watch | str bundleId, u8 flags (bit0 enabled, bit1 vibrate), u8 hide (0/1/2 = default) |
| `0x03` SET_MODE | iOS → watch | u8 mode (0 all, 1 important, 2 messages & calls, 3 calls only) |
| `0x81` SETTINGS | watch → iOS | u8 mode, u16 count, then per app: str bundleId, str name, u8 flags (bit0 enabled, bit1 vibrate, bit2 hiddenByDefault), u8 hide, u32 ARGB colour, str logo slug |

`str` = u8 length + UTF-8 (≤ 255 bytes, cut on a character boundary). u16/u32 are big-endian.
The watch sends SETTINGS when the iPhone subscribes (CCCD), on GET_SETTINGS, and (debounced 400 ms) after any
change, including a new app sending its first notification. Rules for apps the watch hasn't seen are ignored.

**Security today:** the characteristic and its CCCD require an encrypted (bonded LESC) link. Payloads carry app
names and rules, not notification content. App-layer AEAD and replay counters (below) come in Phase 10.

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
