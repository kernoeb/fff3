# fff3

fff3 is a light app to control Bluetooth LED strips with ELK-BLEDOM and MELK controllers, on Android and on the Mac. It has no ads, no tracking and no internet access. It is for personal use and is not on Google Play or the Mac App Store.

![The home screen with 2 strips, the colour wheel, and music sync locked on the beat](docs/screenshots.png)

The name comes from the Bluetooth characteristic that every command is written to: `0000fff3-…`.

## Why it exists

The usual store app for these strips is large, slow and full of ads. It sends a command only after you tap "Set", and it connects again for every command. fff3 keeps the strip connected and changes the colour while you drag.

| | Usual store app | fff3 |
|---|---|---|
| Release APK | 9.4 MB | about 3 MB |
| Permissions | 16 | Bluetooth, microphone, camera and notifications only |
| Ads and analytics | yes | none, no internet permission |
| Commands | 1 every 2 seconds, after a "Set" tap | up to 28 a second, live |
| Connection | scan and connect for each command | added once, kept open |

## Control your strips

You can:

- add a strip once from the app's own scan, then reconnect on every launch
- change colour live while you drag the colour wheel
- control several strips at once with groups
- set power, brightness, white presets and built-in effects with their speed
- use the strip's own microphone modes
- save favourites and apply them in one tap
- set per-strip options: name, wire order with a live test, on and off timers, and automatic clock sync
- use the Quick Settings tile, the home-screen widget and app shortcuts

fff3 saves the last state it sent to each strip. When you open the app again, it shows that state.

## Music sync

Your strips can follow music from 2 sources:

- device audio: what your phone or Mac plays (on the phone, only from apps that allow audio capture)
- microphone: the sound in the room, for a speaker or another device

There are 5 light styles: Pulse, Spectrum, Energy, Calm and Beat lock.

Beat lock finds the tempo and keeps the lights on the beat, even in quiet parts and breaks. You can:

- choose the colours: a new colour each bar, one colour per tempo, or the strip's own colour
- tap the beat to set the tempo, and use ×2 or ÷2 if the lights go twice too fast or too slow

Brightness follows the part of the song. A calm verse is dimmer and a chorus is brighter, whatever the volume.

## Keep lights in time with the sound

fff3 corrects the delay between the sound and the light. It does this in 3 ways:

- it measures the phone's audio output delay with the microphone
- it measures each strip's light delay with the camera: the strip flashes and the camera sees when it changes
- you can fine-tune the delay by hand if the lights still come early or late

You can start the camera measurement from the strip settings or the music screen. The Mac app uses a strip delay measured once with a camera, and reads the audio delays from macOS.

## On the Mac

A native Mac version lives in [macos/](macos). It controls the same strips over Bluetooth: power, colour, brightness, white presets and effects. Music sync follows what the Mac plays or what the microphone hears, with the same 5 styles. Beat lock runs the same code as on Android and gives the same results on every test song.

Groups, favourites, timers, wire order and the strip's own microphone modes are only on Android for now.

![The Mac app with 2 strips connected and music sync locked on the beat at 97 BPM](docs/macos.png)

## Limits

The strips are write-only. They cannot tell the app their power or colour. fff3 only knows what it sent, so the state shows as unknown after you use the remote or another app. For this reason, power has separate On and Off buttons, so each tap does what it says.

Microphone mode is less accurate than phone audio. Room noise and distance from the speaker make the beat harder to find.

Each strip accepts only 1 Bluetooth connection at a time. Close other apps that control the strip before you use fff3. While the Mac app runs, it holds the strips: quit it before you use the phone.

## Install

### Android

Download `fff3-<version>.apk` from the [releases page](https://github.com/kernoeb/fff3/releases) and open it on your phone. You need Android 13 or later.

To build it yourself, you need JDK 17 to 25 and the Android SDK. Then run `./gradlew :app:assembleRelease`. Your build is signed with your own debug key, so it does not install over a release APK.

### Mac

You need macOS 14 or later, and 14.2 or later to sync with the Mac's own sound. There is no download yet: build it with the Xcode command line tools.

```sh
cd macos
./build.sh
rm -rf /Applications/Fff3.app && cp -R Fff3.app /Applications/
```

The app is signed ad hoc, so macOS may ask again for Bluetooth and audio access after each new build. See [macos/README.md](macos/README.md) for details.

## For developers

Read [CLAUDE.md](CLAUDE.md) and [ARCHITECTURE.md](ARCHITECTURE.md) before you change code. For the Mac app, read [macos/README.md](macos/README.md) too. They cover decisions already made, the build, releases, signing and the tools to test Beat lock on real songs.

The byte protocol was worked out from the existing app and from live tests on the strips. The full notes are kept outside this repo.

## Licence

fff3 is under the [MIT licence](LICENSE).
