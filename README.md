# AirPods Overlay for Android TV

A small Android TV app that pops up a **"Connect your AirPods"** overlay when you open
your AirPods case near the TV — the one thing Google TV doesn't do for AirPods out of
the box. Built for and tested on a Xiaomi Mi Box S (2nd Gen, Android 11) with AirPods 4
(ANC), but should work on other Android TV devices and AirPods models.

## What it does

- **Connect popup** — detects Apple's BLE proximity-pairing beacon when the case wakes
  up nearby and shows a focusable overlay: press OK on the remote to connect, Back to
  dismiss. Optional auto-connect skips the popup entirely.
- **Blocks the TV's own auto-connect** (optional) — so your AirPods only connect when
  *you* say so, not every time you open the case in the same room.
- **Enhanced session (AAP)** — after connecting, the app can talk to the AirPods
  directly over L2CAP using Apple's accessory protocol: exact battery levels, ANC /
  Transparency / Adaptive switching from the settings screen, and in-ear detection.
- **Auto-pause** — pauses playback when you take a pod out of your ear.
- **Auto-disconnect** — disconnects when you close the case lid.
- **Identity matching** — once the AirPods' identity key (IRK) is captured, the app
  reacts only to *your* AirPods, not any AirPods that wander past.
- **Trigger distance** — RSSI threshold presets from ~1 m to any distance.

## Heads-up for AirPods 4 (ANC)

When paired to a non-Apple device, AirPods 4 do **not** broadcast the proximity beacon
just from opening the lid. **Double-tap the front of the case** (the pairing wake
gesture) to make it broadcast — that's the trigger gesture for the popup.

## Install

Sideload the APK from [Releases](https://github.com/NathanGlasby/AirPodsOverlayATV/releases)
onto your TV (LocalSend, Send Files to TV, or `adb install`). Then in the app:

1. Grant the Bluetooth/scan permissions and the **Display over other apps** permission.
2. Pair your AirPods in the TV's Bluetooth settings (once), then select them in the app.
3. Turn on the background scanner.

## Build

Standard Android Gradle project — Kotlin, no wrapper committed:

```
gradle assembleDebug
```

Requires JDK 17 and an Android SDK (compileSdk 34, minSdk 28).

## How it works

Apple devices broadcast a manufacturer-specific BLE frame (company ID `0x004C`, type
`0x07`) with lid state, in-ear flags, and battery nibbles. The app runs a foreground
BLE scan service that parses these frames ([BeaconParser.kt](app/src/main/java/dev/nathan/airpodstv/BeaconParser.kt)),
and connects via the hidden `BluetoothA2dp`/`BluetoothHeadset` profile APIs
(reflection + [hiddenapibypass](https://github.com/LSPosed/AndroidHiddenApiBypass)).
The optional AAP session opens an insecure L2CAP channel on PSM `0x1001` for the
features BLE beacons can't provide. The reverse engineering behind all of this came
from the projects in the Credits section below.

## Credits

This app stands on the shoulders of the projects that reverse-engineered Apple's
protocols, all GPL-3.0:

- [CAPod](https://github.com/d4rken-org/capod) by d4rken-org — beacon parsing, AAP profile
- [OpenPods](https://github.com/adolfintel/OpenPods) by adolfintel — the canonical BLE parser
- [LibrePods](https://github.com/kavishdevar/librepods) by kavishdevar — AAP protocol documentation
- [PodsCompanion](https://github.com/Domi04151309/PodsCompanion) by Domi04151309 — popup UI

AirPods icon by [Icons8](https://icons8.com).

See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for details.

## License

[GPL-3.0](LICENSE). AirPods is a trademark of Apple Inc. This project is not
affiliated with or endorsed by Apple.
