# ANCS: protocol notes and our implementation

Reference: Apple, *Apple Notification Center Service (ANCS) Specification*. All multi-byte integers are
**little-endian**.

## Service and characteristics

| Name | UUID | Properties | Our use |
|---|---|---|---|
| ANCS service | `7905F431-B5CE-4E99-A40F-4B1E122D00D0` | | |
| Notification Source | `9FBF120D-6301-42D9-8C58-25E699A21DBD` | notify | subscribed **second** |
| Control Point | `69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9` | write w/ response | commands |
| Data Source | `22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB` | notify | subscribed **first** |

All of these require an encrypted (bonded) link. Unauthenticated access returns
*Insufficient Authentication / Encryption*, and Android then starts bonding.

## Notification Source (8 bytes)

| Offset | Field | Values |
|---|---|---|
| 0 | EventID | 0 Added, 1 Modified, 2 Removed |
| 1 | EventFlags | bit0 Silent, bit1 Important, bit2 PreExisting, bit3 PositiveAction, bit4 NegativeAction |
| 2 | CategoryID | 0 Other, 1 IncomingCall, 2 MissedCall, 3 Voicemail, 4 Social, 5 Schedule, 6 Email, 7 News, 8 HealthAndFitness, 9 BusinessAndFinance, 10 Location, 11 Entertainment |
| 3 | CategoryCount | count of active notifications in that category |
| 4–7 | NotificationUID | uint32 |

## Control Point commands

| CommandID | Command | Format |
|---|---|---|
| 0 | Get Notification Attributes | `00` UID(4) { AttrID [MaxLen(2) for Title/Subtitle/Message] }… |
| 1 | Get App Attributes | `01` AppIdentifier NUL-terminated { AttrID }… |
| 2 | Perform Notification Action | `02` UID(4) ActionID(1: 0 Positive, 1 Negative) |

Notification AttributeIDs: 0 AppIdentifier, 1 Title*, 2 Subtitle*, 3 Message*, 4 MessageSize, 5 Date,
6 PositiveActionLabel, 7 NegativeActionLabel (* = needs a 2-byte max length). App AttributeID: 0 DisplayName.

ANCS ATT errors: `0xA0` Unknown command, `0xA1` Invalid command, `0xA2` Invalid parameter, `0xA3` Action failed.

## Data Source responses
- Notification attributes: `00` UID(4) { AttrID(1) Length(2) Value(Length) }…
- App attributes: `01` AppIdentifier NUL-terminated { AttrID(1) Length(2) Value }…
- A response can span **many GATT notifications** (each ≤ ATT_MTU − 3 bytes). There's **no total length field**.
  The consumer knows it's done once every requested attribute has been parsed. Our
  `DataSourceReassembler` appends fragments and parses incrementally from a byte buffer. It reports
  `Incomplete` until all requested attribute IDs are present, and drops the response if no fragment
  arrives within 5 s (session reset).
- Values are UTF-8 (not NUL-terminated). `Date` is `yyyyMMdd'T'HHmmSS` (no timezone).
- `MessageSize` is the decimal string of the full message length, so we can tell whether a body was truncated.

## Implementation rules
1. **One outstanding Control Point command at a time.** The next request goes out only after the current
   response completes (or times out after 5 s).
2. **Lazy fetching:** request attributes when the event arrives (needed for a heads-up), but request
   action labels only when the corresponding flag is set.
3. **Never hard-code action meaning.** The UI renders `positiveLabel` / `negativeLabel` exactly as iOS
   provides them. An absent flag means no button. The call surface puts whichever action iOS labels
   with a decline-like word on the left only if that label is present. Otherwise it keeps the order
   iOS gives.
4. **PreExisting** events are stored without a haptic (reconnect grace).
5. **Removed** events drop pending fetches for that UID.
6. **Session reset** on disconnect: clear the queue, reassembler and UID map, and keep the app-name cache.
7. Request an MTU of 185 after connecting (iOS typically grants 185). The parser is MTU-agnostic.

## Tested behaviour (iPhone 16 Pro ↔ Galaxy Watch4 Classic LTE, 2026-10-05)
- **ANCS is discoverable before bonding** (it's in the GATT database). Its characteristics still need the bond.
- **Latency** from the Notification Source event to the notification posted on the watch: 71–194 ms for new notifications.
- **Replay on subscribe:** iOS sends every existing notification flagged `PreExisting`. Fetching costs about 0.6 s per notification, serially (16–50 notifications ⇒ 10–30 s). Fresh events must jump the backlog. Note: one reconnect replayed nothing at all (cause unknown, under investigation).
- **Call labels (verbatim from iOS):** IncomingCall = Answer / Decline. MissedCall = Dial / Clear. Category 12 = End Call (negative only).
- **WhatsApp (Social):** only a negative action, labelled **"Clear"**.
- **Incoming call (`com.apple.mobilephone`, Category 1):** a positive action **answers the cellular call** on the iPhone. The audio stays on the iPhone route. The call notification is `Removed` as soon as it's answered (on either device).
- **Undocumented Category 12:** posted by `com.apple.mobilephone` right after a call connects, with flags `0x12` (Important | NegativeAction), and removed when the call ends. It looks like an "active call" notification. Its single action is negative, labelled **"End Call"**. **Performing it ends the cellular call** (verified). This is undocumented, so treat it as fragile across iOS updates.
