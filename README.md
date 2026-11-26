# Oto

A lightweight, personal Windows (x64) music player. No account, no sign-in.

## Download

Get the latest build from **Releases → v1**:

- `Oto-setup-x64.exe` — installer (installs for your user only, no admin needed)
- `Oto-portable-x64.zip` — unzip and run `Oto.exe`, nothing to install

## Features

### Playback
- **Search, browse and play** music from the catalogue: Home, Explore, Search and artist / album pages.
- **Convert to video** — switch the playing song to its official music video, kept in step with the audio, with a video quality picker (Auto, plus every quality the video really offers) and full screen.
- **Hi-Res lossless audio** — FLAC from addon sources you add yourself, with the standard catalogue as fallback.
- **Gapless playback with crossfade**, adjustable 0–12 s.
- **Automix [Beta]** — DJ-style transitions with beat-matching and tempo-stretching.
- **Offline downloads** — save tracks with embedded metadata.
- **Local music library** — play audio files from your own folders.
- **Media keys and Windows media controls**, plus a tray icon with playback controls.

### Experience
- **Clean player** — artwork, controls and volume, with the current lyric line shown live under the title; the full lyrics and the queue open from their buttons.
- **Word-synced lyrics** from multiple providers, with translation.
- **Animated album canvas** — motion artwork on the player.
- **Dynamic, artwork-driven theming** on a premium black interface.
- **Windows-native window** — Windows-style title bar buttons, Mica/Acrylic options on Windows 11.

### Library
- **Playlists, liked songs and history** kept on your PC — no account needed.
- **Song info** — album, release date, views, likes and credits for any track.
- **Listening stats (Replay)** built from what you play.

### Controls & Tweaks
- **Audio quality ceiling** and source order you control.
- **Playback speed** and **skip silence**.
- **Sleep timer** in Settings.
- **Equalizer** and an output device picker.
- **Stats for nerds** — codec, bit depth, sample rate and more on the player.

## Build locally (optional)

Needs JDK 21 on Windows:

```
gradlew.bat :desktopApp:packageExe
```

The installer lands in `desktopApp/build/compose/binaries/main/exe/`.

## Disclaimer

Oto is not affiliated with, endorsed by, or connected to YouTube or Google in any way. Use it at your own discretion.

## License

GPL-3.0 — see `LICENSE`. This is a modified version of an upstream GPL-3.0 project (modified 2026).
