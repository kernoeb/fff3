# fff3

fff3 is a light, ad-free Android app to control Bluetooth LE RGB LED strips (ELK-BLEDOM and MELK controllers). It replaces the usual store app for these strips, for personal use.

The name is the Bluetooth characteristic every command is written to: `0000fff3-…`.

## What it does

- pair a strip once with the system pairing sheet, then reconnect on every launch
- change colour live while you drag the colour wheel, with no "Set" button
- control several strips at once with groups
- power, brightness, white presets, built-in effects with speed, and the strip's own microphone modes
- favourites you apply in one tap
- per-strip settings: name, wire order with a live test, on and off timers, automatic clock sync
- Quick Settings tile, home-screen widget and app shortcuts
- music sync from phone audio or the microphone, with 5 light styles, including Beat lock, which follows the song's tempo

## Compared with the usual store app

| | Usual store app | fff3 |
|---|---|---|
| Release APK | 9.4 MB | about 3 MB |
| Permissions | 16 | Bluetooth, microphone and notifications only |
| Ads and tracking | AdMob, Firebase Analytics | none, no internet permission |
| Commands | 1 every 2 seconds, after a "Set" tap | up to 28 a second, live |
| Connection | scan and connect for each command | paired once, kept open |

## Limits

The strips cannot report their state. fff3 only knows what it sent, so after you use the remote or another app it shows the state as unknown. Power uses separate On and Off buttons, so each tap does what it says.

## Build and install

You need JDK 17 to 25 and the Android SDK. A phone running Android 13 or later must be connected with USB debugging on.

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:installDebug           # debug build on the phone
./gradlew :app:assembleRelease        # release APK, signed with the debug key
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Project layout

- `app/src/main/java/com/kernoeb/fff3/`:
  - `protocol/`: commands and byte encoders, no Android code
  - `ble/`: pairing, links to strips and the write queue
  - `data/`: DataStore files
  - `music/`: audio capture, beat detection and Beat lock
  - `system/`: tile, widget, shortcuts and the music service
  - `ui/`: Compose screens
- `baselineprofile/`: startup profile generator
- [ARCHITECTURE.md](ARCHITECTURE.md): main interfaces and where new work plugs in

The byte protocol was worked out from the existing app and from live tests on the strips. The full notes are kept outside this repo.
