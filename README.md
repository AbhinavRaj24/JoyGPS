# JoyGPS: joystick GPS mock location for Android

JoyGPS starts at your **real GPS position**. You then walk the mocked location around with an
on-screen joystick. Speed follows how far you push the stick: 0 at the centre, rising linearly
to **10 km/h** at the edge. Up is north. Let go and you stop.

- Mocks the `gps`, `network` and (Android 12+) `fused` providers, sending 5 updates per second with speed and bearing.
- In-app joystick, plus a **floating joystick** you can drag around on top of any other app (Maps, etc.).
- A notification with **Stop** / **Show joystick** buttons.
- Optional: type a start point, or jump to coordinates while mocking.
- Android 8.0+ (minSdk 26, targetSdk 35). No third-party libraries.

## Build

**Android Studio:** File → Open → pick the `JoyGPS` folder → let Gradle sync → Run ▶ on your phone
(USB debugging on).

**Command line** (Android SDK installed, `ANDROID_HOME` set):

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

**No Android Studio?** Push this folder to a GitHub repo. The included workflow
(`.github/workflows/build.yml`) builds the APK. Download it from the run's *Artifacts* section.

## One-time phone setup

1. **Enable Developer options:** Settings → About phone → tap *Build number* 7 times.
2. **Developer options → Select mock location app → JoyGPS.** Without this step Android rejects every mock location.
3. Open JoyGPS and allow **Location** and **Notifications**.
4. For the floating joystick, tap *Floating joystick* and allow **Display over other apps**.

## Use

1. Open the app and wait for your real fix. It fills in the start point.
2. Tap **Start mocking**. Your location is now locked to that point.
3. Push the joystick to walk. A small push strolls, and a full push gives 10 km/h.
4. Tap **Floating joystick** and switch to another app to keep walking there.
5. Tap **Stop mocking** (or *Stop* in the notification) to go back to your real GPS.

## Tips / limitations

- If an app keeps jumping back to your real position, turn off **Google Location Accuracy**
  (Settings → Location → Location services) and Wi-Fi/Bluetooth scanning. Google Play services can
  still blend in real Wi-Fi/cell positions.
- Apps can see that a location is mocked (`Location.isMock()`), and some apps refuse mocked
  locations. This is an Android rule and the app can't get around it.
- To change the top speed, edit `MAX_SPEED_KMH` in `MockState.kt`.

## Code map

| File | What it does |
|---|---|
| `MainActivity.kt` | Permissions, reading the real GPS, start/stop, in-app joystick, status |
| `MockLocationService.kt` | Foreground service: registers test providers and moves the position (speed ∝ stick stretch, max 10 km/h) |
| `JoystickView.kt` | The custom round joystick view (dead-zone, spring-back) |
| `JoystickOverlay.kt` | Draggable floating joystick window over other apps |
| `MockState.kt` | Shared state between UI and service |
