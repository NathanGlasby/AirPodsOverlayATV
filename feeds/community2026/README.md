# Community 2026 — custom feed for Aerial Views

A ready-made [Aerial Views](https://github.com/theothernt/AerialViews) custom feed for
the free **Community 2026** pack (20 aerial videos, 4K SDR HEVC, 2024–2025 footage:
Los Angeles, Manhattan, Brooklyn, Iceland, India, France, Switzerland, Italy, England,
Oregon, Washington, New Mexico, Louisiana, Texas). These videos ship with Aerial 4 for
macOS but are not bundled in Aerial Views, so this feed makes them available there.

Paste this URL into **Aerial Views → Custom videos/feeds**:

```
https://raw.githubusercontent.com/NathanGlasby/AirPodsOverlayATV/main/feeds/community2026/entries.json
```

The videos themselves stream from the official
[AerialCommunity releases](https://github.com/AerialScreensaver/AerialCommunity/releases/tag/community2026-4k-sdr)
(tag `community2026-4k-sdr`); this folder only hosts the JSON metadata. `manifest.json`
makes the folder URL also work as a source in Aerial 4 for macOS.

Notes:

- Every quality key (`url-4K-SDR`, `url-1080-SDR`, `url-1080-H264`) points at the same
  4K SDR file — the only format published for this pack — so playback works at any
  quality setting.
- `video_m3_ch_zurich_..._013.mov` is omitted in favour of its `-v2` revision.
- `timeOfDay` is set to `day` and scene/location labels are derived from the filenames;
  edit freely.
- Videos © Joshua Michaels, Hal Bergman & community contributors —
  [license](https://jetsoncreative.com/aeriallicense), support them at
  [magicwindow.app/aerial](https://magicwindow.app/aerial).
