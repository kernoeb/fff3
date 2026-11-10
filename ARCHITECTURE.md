# fff3 architecture

fff3 controls Bluetooth LE RGB LED strips (ELK-BLEDOM and MELK families). One activity, Jetpack Compose, Material 3 Expressive, no DI framework, no Room, no BLE library.

Read this before you add a screen or a system feature. Keep the public interfaces below stable: other work depends on them.

## Build

- JDK 25 (or 17 to 25) for Gradle: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home`
- `./gradlew :app:testDebugUnitTest :app:assembleRelease`
- `./gradlew :app:installDebug` to put a debug build on the phone
- Versions live in `gradle/libs.versions.toml`. Material 3 is pinned to `1.5.0-rc01` by hand for the Expressive APIs.
- Release builds use R8 (`optimization { enable = true }`) and are signed with the debug key, so you can sideload them.

## Packages

All code is in `app/src/main/java/com/kernoeb/fff3/`.

| Package | What it holds | Android imports |
|---|---|---|
| `protocol/` | commands, families, byte encoders, effect catalogues | none |
| `ble/` | links to strips, write queue, controller, scanner | yes |
| `data/` | DataStore files, devices, groups, favourites, last state | yes |
| `calibrate/` | camera light delay calibration: Camera2 and OpenGL reader, and its pure maths (`LightDelayMath`) | yes, except `LightDelayMath` |
| `ui/` | theme, navigation, screens and their ViewModels | yes |
| root | `Fff3Application`, `AppContainer`, `MainActivity` | yes |

`:baselineprofile` holds a startup-only profile generator. Add the colour drag and effect switching to it once the real control screen exists.

## Getting objects

Everything long-lived is built once in `AppContainer` (`context.appContainer`):

```kotlin
val repository: LedRepository
val deviceStates: DeviceStateStore
val bluetooth: BluetoothEnvironment
val controller: LedController
val scanner: BleScanner
val appScope: CoroutineScope
val clockSync: ClockSyncer          // sends ClockSync on each connect; syncedAt by address
val stripSettings: StripSettingsStore  // wire order and schedules we sent (strips are write-only)
val linkLatency: WriteLatency          // rolling median of send-to-written time, for the music beat clock
val queueWait: WriteLatency           // rolling median of the wait in the write queue before the GATT write
val frameWrites: FrameWrites          // reports each frame as it reaches the Bluetooth stack, for the camera calibration
```

In a composable, create a ViewModel from it with `appViewModel { MyViewModel(it.repository, it.controller) }` (`ui/AppViewModels.kt`). Inside a navigation entry, the ViewModel is scoped to that entry.

## Main interfaces

### Sending commands: `ble/LedController.kt`

```kotlin
interface LedController {
    val connectionStates: StateFlow<Map<String, ConnectionState>>   // by device address
    fun send(target: ControlTarget, command: Command, record: Boolean = true)
    fun send(target: ControlTarget, commands: List<Command>, record: Boolean = true)
    suspend fun sendAndAwait(target: ControlTarget, commands: List<Command>, timeoutMillis: Long = 10_000, record: Boolean = true): Boolean
    fun openSession(): LedSession   // keeps all known strips connected until close()
    fun retryNow()
}

sealed interface ControlTarget { data class Device(val address: String); data class Group(val id: String) }
```

- `send` never blocks. Call it on every drag event: drag values (colour, brightness, speed, mic sensitivity) are coalesced, newest wins, at most one frame every `BleTuning.MIN_FRAME_INTERVAL_MS` (35 ms), and the final value is always sent.
- Groups fan out to every member. A command a member's family cannot do is skipped for that member.
- Each send also updates `DeviceStateStore`, so screens show the new state. Short-lived light (music, calibration flashes, wire order test, identify blinks) passes `record = false`, then sends the real state again, recorded, when it ends.
- Links open on demand and close `BleTuning.IDLE_DISCONNECT_MS` after the last write, unless a session is open. `MainActivity` holds a session while visible.
- `ControlTarget.encode()` and `ControlTarget.decode()` give a stable string for intents and widget settings.

`ConnectionState` is one of `Idle`, `Connecting(attempt)`, `Connected`, `WaitingToRetry(failures, retryInMillis)`, `BluetoothOff`, `PermissionMissing`, `Unsupported`.

### Commands and families: `protocol/`

```kotlin
sealed interface Command {
    Power(on), Color(rgb: Rgb), Brightness(percent), Effect(id, speed: Int? = null), Speed(percent),
    MicEnabled(on), MicMode(index), MicSensitivity(percent), Scene(id), WireOrder(order: ChannelOrder),
    PixelCount(count), ClockSync(hour, minute, second, dayOfWeek), Schedule(action, hour, minute, days, enabled)
}

enum class LedFamily { DOM, DOB, MELK, MELK_SCENE
    val effects: List<EffectInfo>; val scenes: List<EffectInfo>; val micModes: List<String>
    val hasMic; val hasWireOrder; val hasPixelCount
    fun supports(command: Command): Boolean
    fun encode(command: Command): List<ByteArray>
    companion object { fun detect(advertisedName: String?): LedFamily? }
}
```

- `Rgb` is a 24-bit colour value class, with `Rgb.fromHsv(h, s, v)` and `Rgb.fromKelvin(k)`.
- No family has a white channel. For white or colour temperature, send `Command.Color(Rgb.fromKelvin(k))`.
- Show only what a family supports: use `family.effects`, `family.scenes`, `family.micModes` and `family.supports(...)`.
- All bytes live in `DomProtocol.kt` and `MelkProtocol.kt`, one file per family. Correct bytes there; `DomProtocolTest` and `MelkProtocolTest` hold the expected hex from the protocol notes, kept outside this repo.

### Stored data: `data/`

```kotlin
interface LedRepository {
    val snapshot: StateFlow<AppData?>      // null until read
    val devices: Flow<List<KnownDevice>>; val groups: Flow<List<DeviceGroup>>
    val favourites: Flow<List<Favourite>>; val settings: Flow<Settings>
    suspend fun saveDevice(device); suspend fun renameDevice(address, label); suspend fun removeDevice(address)
    suspend fun createGroup(name, memberAddresses): DeviceGroup; suspend fun saveGroup(group); suspend fun deleteGroup(id)
    suspend fun saveFavourite(favourite); suspend fun deleteFavourite(id)
    suspend fun updateSettings(transform: (Settings) -> Settings)
}

interface DeviceStateStore {
    val states: StateFlow<Map<String, LedState>>   // what we last sent; strips cannot report state
    val lastControlled: StateFlow<String?>
    fun record(address: String, command: Command); fun forget(address: String); fun flush()
}
```

- A `Favourite` is a name plus a list of `Command`s, with an optional `family` when it holds effects or scenes. Apply it with `controller.send(target, favourite.commands)`.
- Files use a hand-written binary format (`BinaryCodec.kt`). To add a field, follow the versioning note at the top of that file, and add it to `CodecTest`.

### Adding strips: `ble/BleScanner.kt`

- `scanner.scan(): Flow<List<ScannedDevice>>` lists supported strips nearby, strongest signal first. It needs `BLUETOOTH_SCAN`, declared `neverForLocation`, so it works with Location off.
- The Add screen starts a scan when it opens and saves the strip you pick with `repository.saveDevice`. See `ui/add/AddDeviceScreen.kt`.

### Theme: `ui/theme/`

```kotlin
@Composable fun Fff3Theme(seed: Color? = null, content: @Composable () -> Unit)
fun stripDarkColorScheme(seed: Color): ColorScheme
fun Rgb.toColor(): Color; fun Color.toRgb(): Rgb
```

The theme is always dark. Accents come from `seed`; the app root passes the colour of the strip used last (`AppViewModel.accentSeed`, debounced). You can nest `Fff3Theme` to tint one part of the UI with one strip's colour. Pass a settled colour, not every drag event. The OKLCH maths runs once per seed; animation frames only blend two ready schemes.

### Navigation: `ui/navigation/`

`Route` is `Home`, `Control(target)`, `AddDevice`, `Settings`, `StripSettings(address)` (rename, timers, wire order, light delay, forget: open it from a strip overflow menu), `Music` (phone music mode) or `CalibrateLight(address)` (camera light delay calibration). `AppNavigation.kt` maps routes to screens with Navigation 3. `Navigator.navigate(route)` and `Navigator.back()` change the back stack.

## Where new work plugs in

- **New or replaced screen:** replace the composable in `ui/<screen>/` and keep its signature, or add a `Route`, its `encode`/`decode` case, and an `entry` in `AppNavigation.kt`.
- **Colour wheel and effects:** in `ui/control/`. Keep drag state in the composable and read it in draw lambdas, as `ControlScreen` does with `SliderState` and `drawBehind`. Call `controller.send` on each drag event.
- **Quick Settings tile:** a `TileService` in a new `system/` package. Use `context.appContainer.controller.sendAndAwait(target, listOf(Command.Power(on)))` and read `deviceStates.states` for the tile state. Declare it in the manifest.
- **Widget:** an `AppWidgetProvider` with `RemoteViews` in `system/`, same calls as the tile. Store each widget's target as `ControlTarget.encode()`.
- **App shortcuts:** open a screen with `MainActivity.intentFor(context, route)`, or send directly from a small trampoline activity with `sendAndAwait`.
- **Phone music mode:** a foreground service (types `microphone|connectedDevice`, plus `mediaProjection` for phone audio) in `system/`. Sources implement `music/AudioSource` (`MicSource`, `PlaybackSource`); `MusicEngine` runs the same analyser on either. The saved choice is `Settings.musicSource`. Hold `controller.openSession()` while running, close it in `onDestroy`, and send `Command.Color` and `Command.Brightness` through `controller.send`. The Beat lock style runs its own beat clock (`BeatTracker`) and reads it ahead by `LightTiming.lead`: link time (`linkLatency`) plus capture lag, minus the phone's output delay (measured with the microphone by `OutputCalibration`, saved per `AudioOutput` in `Settings`), minus the user's light delay. The link time comes from `LightTiming.linkSeconds`: per target strip, its camera-measured light delay (`Settings.lightDelaysMs`, timed from the GATT write) plus `queueWait`, else `linkLatency`, which only covers the write; the median over the strips, since they all get the same colour. The user's light delay (`Settings.musicLightDelayMs`, +50 ms by default) is a saved fine-tune added on top, measured or not. It logs one line a second under the `fff3-beat` tag (`adb logcat -s fff3-beat`): tempo heard, confidence, locked tempo, state and the reason for any re-lock. Add `RECORD_AUDIO`, `FOREGROUND_SERVICE*` and `POST_NOTIFICATIONS` to the manifest then, not before.
- **Tile, widget and shortcuts (built):** all sends go through `system/SystemActionReceiver` (`goAsync`, so a cold process stays alive until the write ends). Settings can call `QuickSettingsTile.requestAdd(context)` and `QuickSettingsTile.setTarget(context, target)`. `SystemSync` keeps the widget and favourite shortcuts up to date.
- **Light delay calibration:** `ui/calibrate/` flashes one strip red and blue while `calibrate/LightCamera` watches it. Send times come from `FrameWrites` (`SystemClock.elapsedRealtimeNanos` at the GATT write); frame times are the sensor timestamps, which must be on the same clock (`SENSOR_INFO_TIMESTAMP_SOURCE` REALTIME), corrected to the middle of the exposure and for rolling shutter. The result is saved per strip in `Settings.lightDelaysMs`.
- **Tuning:** frame interval, retries, timeouts and idle delay are all in `ble/BleTuning.kt`.

## Rules

- No `INTERNET` permission, no analytics, no `material-icons-extended`. Icons are Material Symbols vector drawables in `res/drawable/`.
- `protocol/` stays free of Android imports and fully unit tested.
- Never call `createBond()` on these strips.
- Measure smoothness on release builds, not debug builds.
