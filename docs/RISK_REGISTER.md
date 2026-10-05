# Risk Register

Likelihood / Impact: L / M / H. Status: **Open** until closed by a hardware result or a decision.

| ID | Risk | L | I | Mitigation / fallback | Status |
|---|---|---|---|---|---|
| R-01 | Wear OS first-time setup requires an Android phone, so a reset watch can't be onboarded without one | H | H | Document it as a prerequisite. This user's watch is already set up and standalone. | Accepted (closed for this user) |
| R-02 | iOS doesn't initiate pairing from ANCS solicitation alone (the watch doesn't appear in iOS Settings) | M | M | `PairingPOC` iOS app connects via CoreBluetooth and reads an encrypted characteristic to force bonding | Open (milestone 1) |
| R-03 | After bonding, iOS doesn't reconnect to the watch at system level without the companion app | M | H | iOS app keeps a pending `connect()` with state restoration. Document the real behaviour | Open |
| R-04 | Android's `connectGatt()` on the inbound central doesn't reuse the link, or iPhone RPA rotation breaks identity | M | H | Use the `BluetoothDevice` from the server callback directly. Bonding distributes the IRK so Android resolves the identity address | Open (milestone 1) |
| R-05 | Samsung's Bluetooth stack/OEM policy restricts third-party LE advertising or GATT server on the Watch4 | L | H | Check `isMultipleAdvertisementSupported`. Report clearly in the UI. Without this there's no product | Open (milestone 1) |
| R-06 | Samsung's own companion services hold Bluetooth resources or conflict (e.g., Galaxy Wearable remnants) | M | M | Test with the watch unpaired from any Android phone. Document | Open |
| R-07 | Positive action on `IncomingCall` doesn't answer, or answers with confusing audio routing | M | M | Use the labels iOS supplies. Say "Answered — audio on iPhone" in the UI | Open (milestone 1) |
| R-08 | Call audio through the watch is impossible (no public HFP-client API) | H | M | Separate call control from audio. Document as NOT POSSIBLE unless the system does it on its own | Expected |
| R-09 | Wear OS kills the foreground service / Doze delays BLE callbacks | M | H | FGS type `connectedDevice`, `BOOT_COMPLETED` restart, no wakelocks. Measure | Open |
| R-10 | Battery drain from advertising/GATT | M | H | Advertise only while disconnected in low-power mode. Event-driven, no polling. `BATTERY_BENCHMARKS.md` | Open |
| R-11 | App Store rejection: trademarks ("Galaxy Watch" in the name), unclear purpose of background Bluetooth | M | M | Neutral name ("Bridge for Wear OS"), honest review notes, no misleading claims | Open |
| R-12 | Play rejection: FGS declaration for `connectedDevice`, full-screen-intent use | M | M | Prepare the Play Console declarations and justify with the incoming-call use case | Open |
| R-13 | Brand app icons bundled without permission | M | M | Ship neutral glyph/monogram icons by default. Curated logos only after a legal review | Open |
| R-14 | Sensitive data leaking via logs/diagnostics | L | H | Single redaction chokepoint (`Redact`, `DiagLog`). Lint check later. No content in logs | Mitigated (by design) |
| R-15 | Spoofing device pretends to be the watch to the iOS app | M | M | Bond-only (encrypted characteristics), then app-layer ECDH + AES-GCM with replay counters (Phase 10) | Open |
| R-16 | Malicious nearby central connects to the watch | M | M | The watch only runs ANCS against bonded devices. Unknown centrals get no data. Future: allow-list of the bonded iPhone identity | Open |
| R-17 | One UI 8 Watch sensor regressions on the Watch4 affect health features | M | L | Health is Phase 8. Re-test after Samsung's fix updates | Open |
| R-18 | Apple changes ANCS behaviour (e.g., in favour of AccessoryNotifications) | L | H | Monitor iOS betas. ANCS has been stable since iOS 7 | Watch |
| R-19 | AccessoryNotifications stays EU-only, so no replies for this user | H | M | Honest "Reply isn't available" UX | Accepted |
| R-20 | The ANCS date has no timezone, so travellers see skewed times | L | L | Interpret in the watch's zone (the same as the phone's in practice) | Accepted |
| R-21 | Notification flood after reconnect (pre-existing replay) buzzes the user | H | M | Never vibrate on `PreExisting`. Coalesce haptics (≥ 1.5 s apart) | Mitigated (by design) |
