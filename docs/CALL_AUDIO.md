# Call audio research (CallAudioPOC)

> **Status: not started. No claims.** Call *control* (Answer/Decline via ANCS) and call *audio* are
> separate problems. A working Answer button doesn't mean two-way audio through the watch.

## What call audio would require
The iPhone is an HFP **Audio Gateway (AG)**. For cellular audio to go through the watch, the watch must
connect to the iPhone as an HFP **Hands-Free (HF)** unit over Bluetooth Classic (BR/EDR) and carry SCO/eSCO audio.

## Public-API assessment
| Question | Finding |
|---|---|
| Does iPhone expose HFP AG? | Yes, to any bonded HF device (car kits, headsets). |
| Can a third-party Wear OS app act as HF? | **No.** `BluetoothHeadsetClient` (profile 16) is a hidden/system API. Public `BluetoothHeadset` is the *AG*-side proxy. **NOT POSSIBLE WITH CURRENT PUBLIC API.** |
| Can the app create a Classic bond with the iPhone? | `BluetoothDevice.createBond()` is public, but the system decides which profiles connect afterwards. |
| Can the app observe SCO routing? | Yes: `AudioManager.getDevices(GET_DEVICES_ALL)` for `TYPE_BLUETOOTH_SCO`, `AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED`. |

So the only legitimate path is *the Wear OS system itself* deciding to connect HFP-HF to a
Classic-bonded iPhone. The experiment is there to find out whether it does.

## Experiment protocol (Phase 7)
1. iPhone: Settings → Bluetooth open (discoverable). Watch: Settings → Connections → Bluetooth. Record
   whether the iPhone shows up and can be paired at all from the watch's system UI.
2. If it's paired, record which profiles iOS shows for the watch (car/headset icon? "Phone audio"?).
3. Start a call. Record the iPhone audio-route picker options.
4. Q1–Q10 from the product spec, each with: steps, expected, observed, video/screenshot reference.
5. Watch-side observations via `adb shell dumpsys bluetooth_manager` (profile states) and our
   diagnostics SCO observer.

## Results
| # | Question | Result | Date | Firmware |
|---|---|---|---|---|
| 1 | Can Watch4 establish the required BT relationship with iPhone? | — | | |
| 2 | Does iPhone expose HFP to it? | — | | |
| 3 | Can incoming cellular calls be answered? | — (see M1) | | |
| 4 | Does answering route audio to the watch? | — | | |
| 5 | Watch mic audio transmitted? | — | | |
| 6 | Can the user end the call? | — | | |
| 7 | iPhone / Watch / AirPods switching | — | | |
| 8 | Two simultaneous BT audio devices | — | | |
| 9 | Watch screen off | — | | |
| 10 | After reconnect | — | | |
