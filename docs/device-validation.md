# Device validation

Use this checklist before describing a feature as verified on hardware. A successful
Gradle build or unit test is not a substitute for observing the behavior on a TV.

## Current evidence

The wake-beacon path and remote-controlled popup have been observed on a Xiaomi Mi
Box S (2nd Gen, Android 11) with AirPods 4 with ANC. A commit-specific record was not
kept for that earlier test. All other features remain unverified until a result is
recorded using the template below.

## Test record

Copy this section for each APK and device combination.

```text
Date:
Tester:

Commit SHA:
Version name and code:
APK filename:
APK SHA-256:
Build type and signer:

TV manufacturer and model:
Android version:
Android build number:
Bluetooth stack or firmware details, if known:

AirPods model:
AirPods firmware:
Case model or connection type, if relevant:

Result summary:
Known failures:
Evidence saved at:
```

Use `Pass`, `Fail`, `Blocked`, or `Not run` for every check. Include a short note for
failures and blocked checks. Redact Bluetooth addresses and never save an identity
resolving key in a screenshot, log, issue, or test record.

## Preparation

1. Keep another input device available in case the TV remote or audio route stops
   responding.
2. Pair the AirPods in Android's Bluetooth settings and select the same device in the
   app.
3. Turn off automatic pause, lid-close disconnect, auto-connect, and both strict
   filters.
4. Grant the Bluetooth permissions and overlay permission requested by the app.
5. On Android 10 or 11, grant background location and confirm that the system Location
   toggle is on.
6. Reboot the TV before the first run so the test covers a clean service start.

## Basic scanner and popup

| ID | Check | Expected result | Result | Notes |
|---|---|---|---|---|
| B1 | Start the background scanner | The app reports an active BLE scan without closing | Not run | |
| B2 | Double-tap the front of an AirPods 4 case | A live AirPods beacon appears in diagnostics | Not run | |
| B3 | Repeat the wake gesture while another TV app is open | The connection popup appears over that app | Not run | |
| B4 | Press Back | The popup closes without connecting | Not run | |
| B5 | Trigger the popup again and press OK | The app starts the selected device connection attempt | Not run | |
| B6 | Leave the case quiet for at least 60 seconds, then wake it again | A new popup session can start | Not run | |

## Bluetooth profiles and recovery

| ID | Check | Expected result | Result | Notes |
|---|---|---|---|---|
| P1 | Connect from the popup | A2DP connects and TV audio routes to the AirPods | Not run | |
| P2 | Test a TV that exposes A2DP but no HEADSET profile | Audio connection still succeeds | Not run | |
| P3 | Enable the app's auto-connect block, then open the case | The TV does not connect before the app requests it | Not run | |
| P4 | Restore normal TV auto-connect | The original profile policy returns after the AirPods disconnect | Not run | |
| P5 | Kill and restart the app during a connection attempt | An old callback cannot change the new session | Not run | |
| P6 | Turn Bluetooth off and back on | Scanning recovers without reinstalling or clearing app data | Not run | |

## Enhanced AAP session

Run these checks only after basic A2DP connection and recovery pass.

| ID | Check | Expected result | Result | Notes |
|---|---|---|---|---|
| A1 | Connect with AAP enabled | The status changes from connecting to active after useful AAP data arrives | Not run | |
| A2 | Compare left, right, and case battery values with a trusted reading | Each component updates without erasing the others | Not run | |
| A3 | Remove or disconnect one component | Only that component disappears from the displayed battery state | Not run | |
| A4 | Select each supported noise-control mode | The AirPods change mode and the app reports the matching state | Not run | |
| A5 | Test with one pod in use and the other unavailable | The known pod still produces useful ear-state updates | Not run | |
| A6 | Force the AAP session to fail while BLE remains active | BLE resumes without sending a false pause or play command | Not run | |

## Reactions and identity

Use quiet media at a safe volume. Enable one reaction at a time.

| ID | Check | Expected result | Result | Notes |
|---|---|---|---|---|
| R1 | Remove one in-ear pod while media is playing | Playback pauses once | Not run | |
| R2 | Return a pod after the app paused playback | Playback resumes once | Not run | |
| R3 | Pause media manually, then remove and return a pod | The app does not claim or resume the manual pause | Not run | |
| R4 | Put both pods in the case and close the lid | The selected AirPods disconnect once | Not run | |
| R5 | Interrupt BLE scanning after both pods enter the case | Scanner failure alone does not trigger a disconnect | Not run | |
| R6 | Capture and verify the selected AirPods identity key | Beacons from that pair pass the strict identity filter | Not run | |
| R7 | Wake a second nearby AirPods pair | Its beacon cannot pause, resume, connect, or disconnect the selected pair | Not run | |

## Permissions and lifecycle

| ID | Check | Expected result | Result | Notes |
|---|---|---|---|---|
| L1 | Deny notification permission on Android 13 or later | The scanner can still start when required Bluetooth permissions are granted | Not run | |
| L2 | Revoke Bluetooth scan permission while the service is running | The service stops or reports the problem without a crash loop | Not run | |
| L3 | Reboot with the scanner enabled | The service returns only when its prerequisites are available | Not run | |
| L4 | Reboot with the scanner disabled | The service stays stopped | Not run | |
| L5 | Change the selected paired device | State from the old address cannot affect the new device | Not run | |

## When a feature can be marked verified

A feature can move to the verified section in the README when its relevant checks pass
twice after a reboot on the same APK. Record the commit, APK SHA-256, TV build, AirPods
firmware, and any model-specific limitation. A result on one device does not imply
support for every Android TV Bluetooth stack.
