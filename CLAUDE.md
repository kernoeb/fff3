# fff3: instructions for Claude

Personal Android app to control 2 ELK-BLEDOM LED strips over Bluetooth LE. Kotlin, Jetpack Compose, Material 3 Expressive. Read [ARCHITECTURE.md](ARCHITECTURE.md) before changing code: it lists the public interfaces and where new work plugs in.

## Decisions already made

- Production app, not a prototype. No "MVP" framing. Personal use only: no Google Play, no other strip families for now.
- No ads, analytics or `INTERNET` permission. No Hilt, Room, Glance, WorkManager or BLE library. Ask before adding any dependency.
- minSdk 33, target 37. Material 3 is pinned to `1.5.0-rc01` by hand for the Expressive APIs.
- Dark theme, accent follows the strip's colour.
- The strips are write-only: they cannot report power or colour. Unknown state is shown as unknown, and power uses separate On and Off buttons. Do not add a "restore state on connect" behaviour unless asked.
- Release APK target: under 3 MB. Only English and French resources ship (`localeFilters`).

## Build and check

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
./gradlew :app:testDebugUnitTest :app:installDebug
```

- Check Gradle's real exit code before committing. A pipe such as `./gradlew … | tail` hides failures, and a broken merge was committed once this way.
- The default JDK on this Mac is too new for Gradle. Always set `JAVA_HOME` as above.
- The phone is connected over adb. Installing debug builds is allowed at any time. Check UI changes with a screenshot: `adb exec-out screencap -p > <scratchpad>/name.png`.
- Never tap Forget, Delete or the system audio capture consent on the user's behalf.
- Logs: `adb logcat --pid="$(adb shell pidof com.kernoeb.fff3)"`, and `adb logcat -s fff3-beat` for Beat lock.

## Testing on the real strips

- Each strip accepts one Bluetooth connection at a time. To test from the Mac, stop the app first: `adb shell am force-stop com.kernoeb.fff3`.
- The Mac can talk to the strips with Python `bleak` (write to `fff3`, service `fff0`, write without response). The strips advertise as `ELK-BLEDOM ` with a trailing space.
- Firmware `P10FT-V5.12R2776`. `fff4` sends no notifications, and reading `fff3` returns a stale buffer.
- Bluetooth write callbacks fire when Android queues the write, not when the strip receives it. The real light delay is measured with the camera: Calibrate light delay, from strip settings or the music screen. Logs: `adb logcat -s fff3-calibrate`.

## Code rules

- `protocol/` has no Android imports and every frame has a unit test with its exact hex.
- Make sure a new test can fail: break the expected value once, see it fail, then revert.
- Code comments: simple English, explain why, 3 lines at most, no banners.
- UI strings: sentence case, simple English.
- Commits end with the `Co-Authored-By` line from the session. Push to `origin main` (private repo `kernoeb/fff3`).
- After a large change, run `/code-review high --fix` before calling it done.
