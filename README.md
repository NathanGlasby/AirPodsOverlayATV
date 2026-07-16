# AirPods Overlay for Android TV

AirPods do not get Apple's connection popup on Android TV. This app watches for
AirPods wake beacons and shows a small connection prompt over the current TV app.
Press OK on the remote to start a connection attempt or Back to dismiss it.

Hardware testing so far covers wake-beacon detection and the popup controls on a
Xiaomi Mi Box S (2nd Gen, Android 11) with AirPods 4 with ANC. The other paths below
are implemented, but they still need a recorded test on physical hardware.

## Feature status

| Feature | Current status |
|---|---|
| Detect the AirPods 4 pairing-wake beacon | Verified on the setup named above |
| Show and control the connection popup | Verified on the setup named above |
| Connect and disconnect through Android Bluetooth profiles | Implemented, needs a current hardware test |
| Block and restore the TV's own auto-connect policy | Implemented, needs a current hardware test |
| Open an Apple Accessory Protocol (AAP) session | Experimental |
| Show exact left, right, and case battery levels | Experimental, depends on AAP |
| Change Off, ANC, Transparency, and Adaptive modes | Experimental, depends on AAP |
| Pause on ear removal and disconnect after case closure | Experimental |
| Capture an identity key and ignore other nearby AirPods | Experimental |
| Filter by estimated distance | Implemented, but the distance labels are not calibrated measurements |

"Verified" means someone observed the behavior on the named TV and AirPods. It does
not mean every Android TV Bluetooth stack supports it. "Implemented" means the code
path exists and may have unit coverage, but the repository does not yet contain a
complete physical test record for it.

See [Device validation](docs/device-validation.md) for the test procedure and the
information required before changing an experimental feature to verified.

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

Keep automatic pause and lid-close disconnect off during the first validation run.
Enable them one at a time after basic scanning and connection recovery work.

## Build

Standard Android Gradle project in Kotlin with a pinned Gradle wrapper:

```
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Requires JDK 17 and an Android SDK (compileSdk 34, minSdk 28).

On a OneDrive checkout, build outside the synchronized folder with
`-PexternalBuildDir=C:/GradleBuilds/AirPodsOverlayATV`. Tagged releases are signed
in GitHub Actions using the `AIRPODS_KEYSTORE_B64`, `AIRPODS_KEYSTORE_PASSWORD`,
`AIRPODS_KEY_ALIAS`, and `AIRPODS_KEY_PASSWORD` repository secrets. The release
workflow verifies the APK signature and publishes a SHA-256 checksum.

Before describing a build as hardware-verified, record its commit and APK checksum
using the [device validation checklist](docs/device-validation.md).

## How it works

AirPods broadcast a manufacturer-specific BLE frame (company ID `0x004C`, type
`0x07`). The current parser reads fields used for lid, ear, and approximate battery
state. A foreground service scans for these frames and parses them in
[BeaconParser.kt](app/src/main/java/dev/nathan/airpodstv/BeaconParser.kt). Connecting
uses the hidden `BluetoothA2dp`/`BluetoothHeadset` profile APIs through reflection
and [hiddenapibypass](https://github.com/LSPosed/AndroidHiddenApiBypass). The
experimental AAP path tries bonded secure and insecure Classic L2CAP socket variants
on PSM `0x1001` for data the beacons cannot provide. The order depends on the Android
version because vendor Bluetooth stacks do not behave consistently here.

## Credits

This app exists because these projects reverse-engineered Apple's protocols, all
under GPL-3.0:

- [CAPod](https://github.com/d4rken-org/capod) by d4rken-org: beacon parsing and the AAP device profile
- [OpenPods](https://github.com/adolfintel/OpenPods) by adolfintel: the original BLE beacon parser
- [LibrePods](https://github.com/kavishdevar/librepods) by kavishdevar: AAP protocol documentation
- [PodsCompanion](https://github.com/Domi04151309/PodsCompanion) by Domi04151309: popup UI ideas

AirPods 4 product image courtesy of iStore United Kingdom. Additional AirPods
icon by [Icons8](https://icons8.com). See
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for details.

## License

[GPL-3.0](LICENSE). AirPods is a trademark of Apple Inc. This project is not
affiliated with or endorsed by Apple.
