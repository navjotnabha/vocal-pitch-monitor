# PitchSwara (`com.singeek.pitchswara`)

A standalone, low-latency Native Android vocal pitch monitor built in Kotlin and Jetpack Compose with zero third-party audio dependencies.

## Features

- **Low-Latency Audio Pipeline**: Uses Android's native `AudioRecord` (`VOICE_RECOGNITION`, `44.1 kHz`, 16-bit PCM mono, 512-sample hop interval for ~86 FPS pitch updates).
- **Subharmonic-Resistant Pitch Detection**: Implements the McLeod Pitch Method (MPM) / Normalized Square Difference Function (NSDF) with first-positive-lobe selection, 2-frame voicing consistency gating, and 3-point median filtering to prevent octave-down spikes on subtle tones.
- **Musical Key & Scale Filtering**:
  - Displays live musical keys (`C`, `C#`, `D`, etc.) and octave notes (`C2`–`C6`).
  - Includes a **Scale / Key Picker** supporting **Chromatic (All Keys)** as well as all 12 individual keys (`C` through `B`), dynamically filtering the horizontal graph bars to show only the relative scale degrees of the selected key.
- **Interactive & Responsive Pitch Graph**:
  - **VSYNC-Locked Auto-Follow**: Smoothly pans the pitch graph vertically to keep your voice in view when singing higher or lower.
  - **Vertical Pan & Pinch Zoom**: Drag with 1 finger to pan across octaves (`G1`–`C6`), or pinch vertically with 2 fingers to zoom key spacing in/out.
  - **Horizontal Pinch Speed Control**: Pinch horizontally with 2 fingers (or tap the `Speed` badge, defaulting to `0.5x`) to control the scrolling speed of the playing line (`0.25x` to `4.0x`).
- **Context Menu (`⋮`)**: Top-right context menu displaying the real-time Microphone Input Level meter and settings.

## Building & Running

1. Open the project in **Android Studio** or build from the command line:
   ```powershell
   .\gradlew.bat assembleDebug
   ```
2. Install onto a connected Android device:
   ```powershell
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   ```
