# Open-Source Reference Projects

These repos are the primary references for the AirPods Overlay for Android TV project. All are **GPLv3** — studying and adapting them for a personal sideloaded build is fine; license obligations only apply when distributing to others.

---

## 1. [d4rken-org/capod](https://github.com/d4rken-org/capod) — **Primary Reference**

**Language:** Kotlin | **Status:** Actively maintained

The closest match to what we're building. Key features to mine:

- **Case-open detection** — triggers a popup when the AirPods case lid is opened
- **Auto-connect** — automatically connects AirPods when the case opens nearby
- Battery reporting for buds + case
- Background BLE scanning service

**Use for:** Case-open detection logic and the auto-connect flow — these are the two hardest parts of our project.

---

## 2. [adolfintel/OpenPods](https://github.com/adolfintel/OpenPods) — **BLE Parser Reference**

**Language:** Java | **Status:** Archived / stable

The canonical minimal Apple BLE manufacturer-data parser. Smaller codebase than CAPod — easiest to read for understanding:

- **Lid state decoding** (open/closed)
- **Battery level parsing** for left bud, right bud, and case
- How to filter BLE advertisements for Apple's manufacturer ID (`0x004C`)

**Note:** The developer is deliberately hostile about Play Store redistribution and the app is built to detect and break sideloaded copies. Studying and adapting the parsing code for our own build is fine — just don't republish it.

**Use for:** The Apple manufacturer-data BLE packet decoder — the raw bytes-to-state mapping.

---

## 3. [kavishdevar/librepods](https://github.com/kavishdevar/librepods) — **Deep Protocol Reference**

**Language:** Kotlin + Rust | **Status:** Active

The most advanced of the bunch. Implements Apple's proprietary AAP (Apple Audio Protocol) that AirPods use when talking to Apple devices, unlocking features unavailable via BLE advertisements alone:

- Ear detection (in-ear sensor)
- Accurate battery beyond what BLE exposes
- Noise control mode switching (ANC / Transparency / Off)
- Head gesture detection
- Conversation Awareness

**Note:** Some features require root and the Xposed framework as a workaround for Android Bluetooth stack restrictions. More than we need initially — but it's the deepest reference if we ever want proper ear detection or noise control switching.

**Use for:** Understanding the full AAP protocol if we go beyond basic BLE parsing.

---

## 4. [Domi04151309/PodsCompanion](https://github.com/Domi04151309/PodsCompanion) — **Popup UI Reference**

**Language:** Kotlin | **Status:** Active

A clean, focused battery-status app with:

- **Pop-up overlay** (the visual we're building toward)
- Notification with battery status
- Home-screen widget
- Each element individually toggleable

**Use for:** The popup/overlay UI implementation — this is the cleanest reference for what our overlay should look like and how it should behave.

---

## Claude Code Prompt Note

When prompting Claude Code on this project, include:

> *"Reference d4rken-org/capod for case-open detection and auto-connect, and adolfintel/OpenPods for the Apple manufacturer-data BLE parser."*

---

## Quick Comparison

| Repo | Best for | Complexity | Root required? |
|------|----------|------------|----------------|
| capod | Case-open + auto-connect | Medium | No |
| OpenPods | BLE packet parsing | Low | No |
| librepods | Full AAP protocol | High | Some features |
| PodsCompanion | Popup/overlay UI | Low–Medium | No |
