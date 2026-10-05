# Battery Benchmarks

> Status: **method defined, no measurements yet.** Numbers go here only after a real run on the
> test rig (Galaxy Watch4 Classic LTE SM-R895F, Wear OS 6, and iPhone 16 Pro).

## Design choices that should keep drain low (to be confirmed by measurement)
- The watch **never scans**. It only advertises while disconnected, in `ADVERTISE_MODE_LOW_POWER` (1 s interval).
- Everything is event-driven: GATT notifications, broadcast receivers. There's no polling, no wakelocks and no periodic timers.
  The only timers are bounded retry backoff (≤ 8 attempts, capped at 60 s) and per-request ANCS timeouts.
- Attribute fetches are lazy and serial. Message bodies are capped at 512 bytes and the MTU is 185.
- On reconnect, the replay goes to the in-app inbox without haptics.

## Method
**Wireless ADB keeps Wi-Fi awake and skews results.** Turn off *Wireless debugging* (or the watch's
Wi-Fi) for the whole measurement window, and only reconnect afterwards to read the stats.

1. Charge the watch to 100 %, then unplug it. Keep the iPhone within 1 m (unless testing range).
2. `adb shell dumpsys batterystats --reset`, then turn off wireless debugging.
3. Run the scenario for the window below. Wear the watch normally, with the screen timeout unchanged.
4. Reconnect ADB and collect:
   - `adb shell dumpsys battery | grep level` (whole-watch drop)
   - `adb shell dumpsys batterystats --charged io.wearbridge.watch` (estimated mAh attributed to Bridge,
     plus Bluetooth time)
5. **Baseline:** repeat the same window with Bridge force-stopped (`adb shell am force-stop io.wearbridge.watch`)
   and Bluetooth on. Bridge's cost = scenario minus baseline.

## Scenarios × windows

| Scenario | 1 h | 8 h | 24 h | Notes |
|---|---|---|---|---|
| Baseline (Bridge stopped, BT on) | | | | |
| Idle connected (READY, no notifications) | | | | |
| Normal use (~30 notifications/h) | | | | |
| Notification flood (50/min for 10 min, then idle) | | | | §41 flood test |
| Calls (5 incoming, answer/decline from the watch) | | | | |
| Disconnected (iPhone out of range, advertising) | | | | Advertising cost |
| Reconnect churn (walk out/in range every 10 min) | | | | |

## Targets (proposed)
- Idle connected: **≤ 1 % per hour** above baseline.
- Disconnected advertising: **≤ 0.5 % per hour** above baseline.
- No Bridge-attributed wakelocks in `batterystats` outside notification handling.
