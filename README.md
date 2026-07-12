# AirPods Overlay for Android TV

Android TV does nothing when you open your AirPods case near the TV. This app fixes
that: a small "Connect your AirPods" popup appears over whatever you're watching.
Press OK on the remote to connect, Back to dismiss.

Built for a Xiaomi Mi Box S (2nd Gen, Android 11) and AirPods 4 with ANC. Other
Android TV devices and AirPods models should work, but that's the tested combo.

## What it does

- Shows a connect popup when your AirPods case wakes up nearby. There's an optional
  auto-connect mode that skips the popup entirely.
- Can block the TV's own auto-connect, so the AirPods connect only when you say so
  and not every time you open the case in the same room.
- After connecting, it can open a direct session with the AirPods over Apple's
  accessory protocol. That gets you exact battery levels, noise control switching
  (Off / ANC / Transparency / Adaptive), and in-ear detection.
- Pauses playback when you take a pod out of your ear.
- Disconnects when you close the case lid.
- Can learn and verify your AirPods' identity key, then use the optional strict
  identity filter to ignore other AirPods that wander past.
- Trigger distance presets from about 1 m to anywhere in the room.

## AirPods 4: double-tap the case

When paired to a non-Apple device, AirPods 4 broadcast nothing when you just open
the lid. Double-tap the front of the case (the pairing wake gesture) and they start
broadcasting. That's the gesture that triggers the popup.

## Install

Sideload the APK from [Releases](https://github.com/NathanGlasby/AirPodsOverlayATV/releases)
onto your TV (LocalSend, Send Files to TV, or `adb install`). Then in the app:

1. Grant the Bluetooth/scan permissions and the **Display over other apps** permission.
   On Android 10/11, Location must be set to **Allow all the time** because the
   scanner runs while other TV apps are in front, and the system Location toggle
   must remain on for BLE discovery.
2. Pair your AirPods in the TV's Bluetooth settings (once), then select them in the app.
3. Turn on the background scanner.

The model and identity filters are strict opt-in controls. Leave them off until the
live-beacon view is working; every beacon line shows whether it was accepted and, if
not, the exact rejection reason.

## Build

Standard Android Gradle project in Kotlin, no wrapper committed:

```
gradle assembleDebug
```

Requires JDK 17 and an Android SDK (compileSdk 34, minSdk 28).

## How it works

Apple devices broadcast a manufacturer-specific BLE frame (company ID `0x004C`, type
`0x07`) carrying lid state, in-ear flags, and battery nibbles. A foreground service
scans for these frames and parses them in
[BeaconParser.kt](app/src/main/java/dev/nathan/airpodstv/BeaconParser.kt). Connecting
uses the hidden `BluetoothA2dp`/`BluetoothHeadset` profile APIs through reflection
and [hiddenapibypass](https://github.com/LSPosed/AndroidHiddenApiBypass). The
optional AAP session opens an insecure L2CAP channel on PSM `0x1001` for the things
the beacons can't provide.

## Credits

This app exists because these projects reverse-engineered Apple's protocols, all
under GPL-3.0:

- [CAPod](https://github.com/d4rken-org/capod) by d4rken-org: beacon parsing and the AAP device profile
- [OpenPods](https://github.com/adolfintel/OpenPods) by adolfintel: the original BLE beacon parser
- [LibrePods](https://github.com/kavishdevar/librepods) by kavishdevar: AAP protocol documentation
- [PodsCompanion](https://github.com/Domi04151309/PodsCompanion) by Domi04151309: popup UI ideas

AirPods icon by [Icons8](https://icons8.com). See
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for details.

## License

[GPL-3.0](LICENSE). AirPods is a trademark of Apple Inc. This project is not
affiliated with or endorsed by Apple.
