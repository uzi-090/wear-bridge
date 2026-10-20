# Low-battery alerts

Implemented 7 October 2026. Each device warns about the other device when its freshly reported battery is **10% or below and it is not charging**.

| Other device state | Behavior |
|---|---|
| 10% or below, not charging | One native notification on this device |
| Further low readings or reconnect | No repeated warning for the same episode |
| Charging or full on its charger | Clear any warning and rearm |
| Unplugged while still at 10% or below | Warn once for the new discharge episode |
| Above 10% | Clear the warning and rearm |
| Unknown battery or unknown charging state | Do not issue a new warning |

Warnings are “iPhone battery low” on the watch and “Watch battery low” on the iPhone. The watch excludes its own battery warning from ANCS mirroring, so the phone notification does not bounce back as a second watch alert. Persistent per-peer episode flags prevent duplicates across app restart/reconnect. All processing stays local; there is no added polling or wake lock.

## Requirements

Enable Bridge notifications on both devices; the iPhone permission is under Settings → Bridge alerts. Focus, DND, notification channel settings and OS presentation rules apply. Bluetooth and the companion channel must be connected for fresh charging-aware reports. Swiping away the iPhone companion prevents companion alerts until it is reopened. The iPhone Battery Service supplies level but no charging flag: the watch only authorizes a low warning with a recent companion charging report, never with an inferred charging trend. Charging means the reported plugged/charging state, including full; a connected charger counts even if the OS temporarily pauses charging.

## Verification

Nine watch regression tests and eight Swift regression tests cover inclusive threshold, invalid/unknown values, duplicate suppression, recovery, charging suppression, unplugging low and the watch reflection filter. The full watch app suite passes 93 tests; Swift passes 21 on simulator and physical iPhone. Both watch lint variants, debug/release watch builds and unsigned iOS Release build pass.

Physical device probes used temporary simulated readings, restored afterward. The watch native notification path passed charging suppression, alert at 10% on unplug, unchanged notification timestamp on repeated low readings, and removal on charging. The watch-to-iPhone encrypted STATUS path was checked against the persistent acknowledgement written only after iOS accepted the local notification request, including charging clearance. See [watch probe](qa-2026-10-06/battery-alert-watch-live.json) and [iPhone probe](qa-2026-10-06/battery-alert-charging-live.json). These prove notification acceptance and state transitions; actual battery depletion, visible banners/haptics under every Focus/DND state, and endurance remain unverified.

During final installation, Android rejected compact SVG arc flags in a tile icon. Apple Music, WhatsApp and Discord vectors now use explicit Android-compatible flags; geometry and attribution are retained. All 52 vector paths pass the argument-count audit.
