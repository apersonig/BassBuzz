# BassBuzz

Two apps and a shared library:

- **BassBuzz** (`app/`): analyses a song, plays it with haptics, has the frequency tester, and **exports a
  `.bbz` haptic file** (the song plus its bass envelope, see `docs/FORMAT.md`).
- **BassBuzz Player** (`player/`): opens `.bbz` files and plays them with haptics, **in the background and
  with the screen off**, with notification and lock-screen controls.
- **LDAF Scope** (`ldaf/`): a separate experiment that uses the Pixel's laser autofocus as a rangefinder.
  Android doesn't give apps the raw laser sensor, so it reads the camera's autofocus distance (which the
  phone steers with the laser), shows it live, plots a top-down scan map as you sweep the phone around, and
  has a device explorer that dumps every sensor and camera key so the laser can be hunted for.
- **Ultrasonic Tone** (`ultrasonic/`): plays a pure sine through the speaker from 1 kHz up to just under half the
  phone's output rate (24 kHz on a 48 kHz phone), as a steady tone, a repeating sweep, or pulsed. Volume is
  eased in and out so it doesn't click, the volume is capped when sound isn't going to the phone speaker, and
  it stops when you leave the app. An optional **mic check** measures the tone against the background noise
  (Goertzel), because you can't hear the thing you're testing.
- **core** (`core/`): the shared engine and the `.bbz` reader/writer, with unit tests.

Plays an MP3 (or any audio file Android can decode) and makes the phone's vibration motor follow the bass.
Built for a Pixel 10 Pro XL (LRA motor, amplitude control), but works on any Android 12+ phone with a vibrator.
The frequency tester needs Android 16 for true frequency control (older versions get pulse mode only).

## How it works

1. **Convert**: the file is decoded with `MediaCodec`, mixed to mono, and run through a 4th-order
   Butterworth low-pass (default 120 Hz). The loudness of what's left is measured every 20 ms
   and stored as the bass envelope (this is the "haptic track"; the waveform you see in the app).
2. **Play**: `MediaPlayer` plays the audio. Every 200 ms a background thread reads the audio position and
   sends the motor the next ~240 ms of the envelope as a `VibrationEffect.createWaveform` (20 ms steps,
   amplitude 1-255). Because each chunk is computed from the live audio position, haptics can't drift.
3. **Feel**: an LRA resonates at one fixed frequency (~150-200 Hz), so it can't reproduce a 50 Hz tone itself.
   Instead its *strength* follows the bass level, and sharp rises (kick drums, bass hits) get an extra "punch".

## Build

**Android Studio**: open this folder, let Gradle sync, plug in the phone (USB debugging on), press Run.

**Command line**: `./gradlew assembleDebug` -> `app/build/outputs/apk/debug/app-debug.apk`, then
`adb install -r app/build/outputs/apk/debug/app-debug.apk`.

**No dev tools**: push this folder to a GitHub repo; the included workflow builds the APK
(Actions tab -> latest run -> Artifacts -> `BassBuzz-debug-apk`). Download it on the phone and install.

## Using it

- **Open audio file**, wait for "Ready", press **Play**.
- **Test motor** plays a rising buzz and three hits, a quick way to check your settings.
- **Intensity**: overall strength. **Noise gate**: bass below this level stays silent (raise it if quiet
  passages buzz too much). **Punch**: extra kick on drum hits.
- **Timing**: if the buzz comes after the sound, raise it. Bluetooth earbuds delay the audio, so the buzz
  will come first: lower it (negative values) until kicks and thumps line up.
- **Bass range**: the low-pass cutoff. Lower = only sub-bass, higher = includes bass guitar and
  the body of kick drums. Releasing the slider re-analyses the song.
- **Frequency tester** (bottom of the app): a 1-500 Hz slider (plus -10/-1/+1/+10 buttons) that drives the
  motor directly while **Start vibration** is on. On Android 16 it asks the motor for exactly that frequency,
  and the app shows the motor's real range and strongest frequency. Below the motor's range it pulses the
  motor's own buzz on/off at that rate (the only way to feel 1-30 Hz); above the range nothing can play.
  Starting the tester pauses the music, and pressing Play stops the tester.
  **Subwoofer roll-off** (on by default) makes the tester get quieter toward low frequencies, like a real
  subwoofer: full strength well above the corner (default 60 Hz), -3 dB at the corner, then -12 dB per octave
  below it. **Above the motor's range** can be Silent, Clamp to max (plays the motor's highest frequency), or
  Force anyway (asks Android for the exact frequency; the docs say Android usually plays nothing out of range,
  so switch to Clamp if you feel nothing).
- **Compatibility mode**: switch on if you feel nothing at all. It sends the vibration on the alarm
  channel, which ignores the "media vibration" setting. Also check Settings -> Sound & vibration ->
  Vibration & haptics, and that battery saver is off.

## Limits

- Haptics only run while the app is on screen (the screen is kept awake while playing). Playing with the
  screen off would need a foreground service, which isn't included.
- Timing is ~20 ms granular and the motor has a little spin-up lag, so it's a feel of the bass, not a
  sample-accurate reproduction.
- Not yet compiled on a device in the environment this was written in; if Gradle reports a compile error,
  paste it back and it's a quick fix.

## Layout

```
app/src/main/java/com/example/bassbuzz/
  AudioDecoder.kt   MediaCodec decode to mono PCM
  BassAnalyzer.kt   low-pass filter, envelope, onset detection
  HapticPlayer.kt   MediaPlayer + synced vibration chunks + mapping to amplitude
  EnvelopeView.kt   waveform display and scrubbing
  MainActivity.kt   UI
```

## Exporting and the Player

In BassBuzz, load a song, wait for "Ready", then tap **Export haptic file (.bbz)** and pick where to save it.
Copy that file to any phone with **BassBuzz Player** and open it from the app (or "Open with" in a file
manager). Player settings (intensity, gate, punch, timing, background-proof mode) are remembered.

Background playback uses a foreground media service, so Android keeps it alive with the screen off or the
app closed, and the notification has Pause/Play and Stop. If music continues but the motor stops once the
screen is off, turn on **Background-proof mode**, which sends the vibration on the alarm channel.

## Watch Launcher

A home-screen launcher styled after the Apple Watch app grid: round icons packed in a honeycomb, large in the
middle and shrinking smoothly towards the edges. Drag or fling to roam, and the nearest icon settles into the
middle. Tap to open, long-press an icon for App info / Uninstall / Move to centre, long-press empty space for
settings (list view, order, snap, haptic ticks, clock, status bar). Set it as your home app from that menu or in
Settings > Apps > Default apps > Home app. The layout is built once per load, so it doesn't reshuffle while you use it.
