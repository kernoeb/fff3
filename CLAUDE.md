# fff3: instructions for Claude

Personal app to control 2 ELK-BLEDOM LED strips over Bluetooth LE: an Android app in `android/` (Kotlin, Jetpack Compose, Material 3 Expressive) and a Mac app in `macos/` (SwiftUI). Read [ARCHITECTURE.md](ARCHITECTURE.md) before changing code: it lists the public interfaces and where new work plugs in.

## Decisions already made

- Production app, not a prototype. No "MVP" framing. Personal use only: no Google Play, no other strip families for now.
- No ads, analytics or `INTERNET` permission. No Hilt, Room, Glance, WorkManager or BLE library. Ask before adding any dependency.
- minSdk 33, target 37. Material 3 is pinned to `1.5.0-rc01` by hand for the Expressive APIs.
- Dark theme, accent follows the strip's colour.
- The strips are write-only: they cannot report power or colour. Unknown state is shown as unknown, and power uses separate On and Off buttons. Do not add a "restore state on connect" behaviour unless asked.
- Release APK target: under 3 MB. Only English and French resources ship (`localeFilters`).

## Build and check

The Android project is in `android/`. Run every `./gradlew` command from there.

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
cd android
./gradlew :app:testDebugUnitTest :app:assembleRelease && adb install -r app/build/outputs/apk/release/app-release.apk
```

- The user runs the release build, signed with the fff3 key (see below). Debug builds use the debug key, so they do not install over it: do not use `installDebug` on the user's phone. `run-as` does not work on the release build.
- Release lint (`lintVital`) is off because lint 9.5 crashes on some Compose files. Run `./gradlew :app:lintDebug` by hand if you want lint.
- Check Gradle's real exit code before committing. A pipe such as `./gradlew … | tail` hides failures, and a broken merge was committed once this way.
- The default JDK on this Mac is too new for Gradle. Always set `JAVA_HOME` as above.
- The phone is connected over adb. Installing release builds with `adb install -r` is allowed at any time. Never uninstall the app: that wipes the user's strips and calibrations. Check UI changes with a screenshot: `adb exec-out screencap -p > <scratchpad>/name.png`.
- Never tap Forget, Delete or the system audio capture consent on the user's behalf.
- Logs: `adb logcat --pid="$(adb shell pidof com.kernoeb.fff3)"`, and `adb logcat -s fff3-beat` for Beat lock.

## Releases and signing

- Release builds are signed with the fff3 key. Gradle reads `fff3.keystore`, `fff3.keystorePassword` and `fff3.keyAlias` from `~/.gradle/gradle.properties`; the keystore is `~/.config/fff3/fff3-release.jks`. The password can also come from the `FFF3_KEYSTORE_PASSWORD` environment variable. Without the keystore property, release builds fall back to the debug key with a warning.
- Losing the keystore or its password means the app can never be updated again without uninstalling it, which wipes the user's strips and calibrations. The user keeps a backup.
- To cut a release, only when the user asks: `git tag v0.2.0 && git push origin v0.2.0`. A `v*` tag or a published GitHub release runs `.github/workflows/release.yml`: it runs the unit tests, builds with `-PversionName` from the tag (without the `v`) and attaches `fff3-<tag>.apk` to the release, creating it if needed. `workflow_dispatch` builds an artifact without publishing, for testing.
- The workflow signs with the `FFF3_KEYSTORE_BASE64` and `FFF3_KEYSTORE_PASSWORD` repository secrets. Without them it signs with a new debug key and warns in the run summary; that APK does not install over the user's app.
- `versionCode` stays 1 on purpose: a higher one from CI would block local `adb install -r` builds. `-PversionCode` exists if needed.
- Actions in the workflow are pinned to the full commit SHA of their latest release, with the version in a comment. Keep them on the latest release when you update them.

## Testing on the real strips

- Each strip accepts one Bluetooth connection at a time. To test from the Mac, stop the app first: `adb shell am force-stop com.kernoeb.fff3`.
- The Mac can talk to the strips with Python `bleak` (write to `fff3`, service `fff0`, write without response). The strips advertise as `ELK-BLEDOM ` with a trailing space.
- Firmware `P10FT-V5.12R2776`. `fff4` sends no notifications, and reading `fff3` returns a stale buffer.
- To check Beat lock on a real song without the phone: `ffmpeg -i song.mp3 -ac 1 -ar 48000 -f f32le song.f32`, then `FFF3_SONG=song.f32 ./gradlew :app:testDebugUnitTest --tests '*SongReplayTest*' --rerun -i`. It prints tempo, energy and brightness each second. Commas join files into a playlist.
- To score Beat lock tempo on several songs: `FFF3_BENCH="a.f32=114;b.f32=92" ./gradlew :app:testDebugUnitTest --tests '*BeatBenchTest*' --rerun -i | grep BENCH`. Half and double tempo count as right. `python3 ../tools/micsim.py in.f32 out.f32 -30` turns a song into what the phone microphone hears 50 cm from a speaker (-45 for low volume; needs scipy). Tune on some songs and check on others you did not tune on.
- Bluetooth write callbacks fire when Android queues the write, not when the strip receives it. The real light delay is measured with the camera: Calibrate light delay, from strip settings or the music screen. Logs: `adb logcat -s fff3-calibrate`.

## macOS app

A native Mac version lives in `macos/` (SwiftUI, Swift 6, SwiftPM, no Xcode project). Read [macos/README.md](macos/README.md) first.

```sh
cd macos
./build.sh                      # -> Fff3.app, signed ad hoc, bundle id com.kernoeb.fff3.mac
./tools/test.sh                 # protocol frames with their exact hex
swift build -c release --product Fff3Bench && FFF3_BENCH="a.f32=114" .build/release/Fff3Bench
```

- `Sources/Fff3Core/Music` is a line-for-line port of `music/`. A change to `BeatTracker.kt`, `MusicAnalyzer.kt` or the other music files must be made in both. Check with `Fff3Bench` and `BeatBenchTest` on the same `FFF3_BENCH`: their `BENCH` and `LOCK` lines must match.
- Use `JMath` for maths in the port, never Swift's Float functions: Kotlin computes `ln`, `exp`, `pow` and `log10` on Floats in Double, and rounds half up. Keep Kotlin's types: a Float times a Double is a Double.
- While the Mac app runs it holds the strips' only connection. Quit it before you test on the phone.
- The capture callback of a process tap starts with the output device's own input buffers when it has some (a USB audio interface). Read the tap's buffers, which come last.
- `rm -rf` a signed `Fff3.app` before you replace it. Ad-hoc signing may make macOS ask for permissions again after a rebuild.
- Dispatch closures that leave the main actor must be `@Sendable`, or Swift 6 traps at run time.
- A process tap stamps audio about 35 ms after it was played, and gets no callbacks while nothing plays. See "How late the lights are" in the README before you change the music timing.
- Start the app with `open -n --env … Fff3.app`, never the binary from a shell: that captures silence. Quit the previous copy first.
- `FFF3_PAGE`, `FFF3_STYLE`, `FFF3_AUTOSTART`, `FFF3_AUDIO_FILE`, `FFF3_BLE_LOG`, `FFF3_BEAT_LOG` and `FFF3_TIMING_LOG` make checks scriptable: see the README.

## Code rules

- Until v1.0.0, the app data format stays at version 1: change its layout in place, with no code for older versions, then uninstall and reinstall the app (ask the user first, it wipes their strips). The release notes of a version that changes the layout must say that updating needs a reinstall. From v1.0.0, follow the versioning rule at the top of `BinaryCodec.kt`.
- `protocol/` has no Android imports and every frame has a unit test with its exact hex.
- Make sure a new test can fail: break the expected value once, see it fail, then revert.
- Code comments: simple English, explain why, 3 lines at most, no banners.
- UI strings: sentence case, simple English.
- Commits end with the `Co-Authored-By` line from the session. Push to `origin main` (private repo `kernoeb/fff3`).
- After a large change, run `/code-review high --fix` before calling it done.
