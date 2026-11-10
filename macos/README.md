# fff3 for macOS

A native Mac version of fff3: control your ELK-BLEDOM and MELK strips over Bluetooth, and make them follow the music this Mac plays or the room hears. SwiftUI, Swift 6, macOS 14 or later. It has no dependencies and no Xcode project.

## Build and run

```sh
cd macos
./build.sh                      # -> Fff3.app, signed ad hoc
rm -rf /Applications/Fff3.app && cp -R Fff3.app /Applications/
./tools/test.sh                 # protocol frames, with their exact hex
```

Always remove a signed app before you copy a new one over it: a copy over the old bundle keeps a stale signature.

Each strip accepts one Bluetooth connection at a time. While the Mac app runs it holds every strip it knows, so the phone cannot reach them. Quit the Mac app to give them back.

## What it does

The Light page has:

- On and Off buttons, as on Android: the strips cannot report their state
- a colour wheel that sends while you drag
- brightness, and 3 whites (2700 K, 4000 K and 6500 K) made from the RGB LEDs
- the family's built-in effects and scenes, with their speed

The Music page has:

- 2 sources: this Mac's sound, through a Core Audio process tap (macOS 14.2 or later), and the microphone
- the 5 styles: Pulse, Spectrum, Energy, Calm and Beat lock
- for Beat lock: tap tempo, ×2 and ÷2, the colour choice (each bar, tempo or strip) and the light delay fine-tune, from -100 to +200 ms

Each strip is remembered by the identifier CoreBluetooth gives it on this Mac, in `~/Library/Application Support/fff3/data.json`. macOS hides Bluetooth addresses, so a strip added on the phone must be added again on the Mac.

## How the code is split

- `Sources/Fff3Core/Protocol`: commands, families, frames and catalogues, ported from `protocol/`.
- `Sources/Fff3Core/Music`: `Fft`, `MusicAnalyzer`, `BeatDetector`, `BeatTracker`, `SongEnergy`, `LightRenderer`, `LightTiming` and `PcmConverter`, ported line for line from `music/`. `JMath` gives Kotlin's maths: Float functions computed in Double and rounded once, and rounding that goes half up.
- `Sources/Fff3`: the app. `WriteQueue` keeps the Android write pacing: one frame every 35 ms per strip, drag values coalesced, newest wins. `Bluetooth` holds one link per strip on one serial queue, reconnects with the same backoff, and sends the Mac's time on each connect. `MusicEngine` is `MusicEngine.kt` on a thread.
- `Sources/Fff3Bench`: the Swift twin of `BeatBenchTest.kt`.

## Check that Beat lock matches Android

The bench takes the same input and prints the same lines as `BeatBenchTest.kt`:

```sh
swift build -c release --product Fff3Bench
FFF3_BENCH="song.f32=114;other.f32=92@40" FFF3_BENCH_LOCKS=1 .build/release/Fff3Bench
```

Songs are 48 kHz mono f32le: `ffmpeg -i song.mp3 -ac 1 -ar 48000 -f f32le song.f32`. Run the Kotlin bench with the same `FFF3_BENCH` and compare the lines. `FFF3_BEATS_DIR=dir` writes every beat time, for a closer check.

On 80 runs (11 songs, clean, microphone and quiet versions, from the start and from 40 s), the Swift and Kotlin benches print the same `BENCH` and `LOCK` lines, and the same 24,813 beat times but one, which differs in the last printed digit. Run this check again after any change to `music/`.

## Test switches

These environment variables help to check the app without clicking through it:

- `FFF3_PAGE=light`, `music` or `add` opens that page
- `FFF3_AUDIO_FILE=song.f32` plays a decoded song into the analyser in real time instead of capturing, so music sync can run on the strips without audio permissions
- `FFF3_BLE_LOG=1` prints each frame as it goes to a strip, with the time in milliseconds
- `FFF3_BEAT_LOG=1` prints one Beat lock line a second, like `adb logcat -s fff3-beat`, plus the loudness

Run the binary itself to see the logs: `FFF3_BLE_LOG=1 Fff3.app/Contents/MacOS/Fff3`. `tools/screenshot.sh out.png` captures the window.

## Not ported

These are Android only, or need the phone's hardware:

- widgets, the Quick Settings tile and app shortcuts
- camera light delay calibration: the Mac has no measured light delay per strip, so the link time is the measured write time, and the light delay fine-tune covers the rest
- measuring the output delay with the microphone (`OutputCalibration`): the Mac uses the latency Core Audio reports for the default output
- companion device pairing and haptics

These exist on Android and are not on the Mac yet:

- groups (the Mac has one strip or all strips) and favourites
- per-strip settings: on and off timers, wire order and its test, pixel count
- the controller's own microphone modes

## Things to know

- Ad-hoc signing changes the app's signature on each build, so macOS may ask again for Bluetooth, system audio or microphone access after a rebuild.
- An output device with inputs, such as a USB audio interface, adds its own input buffers before the tap's in the capture callback. The code reads the tap's buffers, which come last. Reading the first buffer gave near silence with a Scarlett 2i2 as output.
- CoreBluetooth reports a write without response as done when it is queued, not when the strip shows it, as on Android. Without the camera calibration, Beat lock may need a larger light delay on the Mac.
