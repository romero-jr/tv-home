# TV Home

![TV Home — Your space. On screen.](art/tv-home-banner.png)

A minimal launcher for Android TV boxes: clock, date, weather, and your installed apps. Nothing else.

The [visual identity](art/IDENTITY.md) echoes the launcher's three-card layout in charcoal, warm white, and muted red. Editable artwork and PNG exports are in `art/`.

- **Weather** from [Open-Meteo](https://open-meteo.com) (no API key). Location is detected from your IP via ipapi.co, or set a city in Settings. The clock and weather cards use translucent charcoal panels over `background_art.png`.
- **Apps** show their TV banner when they have one, otherwise icon + name. App artwork is cached in memory while the launcher stays alive, with refreshes on package changes rather than every return home. App loading runs separately from weather requests. Tiles reuse their child views and only animate the previous and current selection.
- **Hold OK** or press **Menu** on an app to hide it or open App info (uninstall).
- **Gear button** (top right): weather city, °F/°C, hidden apps, Android settings.

## Build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

## Make it the default home screen

Install the APK, then press **Home** on the remote. Most generic Android boxes ask which launcher to use: pick **TV Home** → **Always**.

If no prompt appears, go to **Settings → Apps → Default apps → Home app**. On Google TV / Android TV boxes that hide this option, set it over ADB:

```bash
adb shell cmd package set-home-activity com.tvhome.launcher/com.tvhome.launcher.MainActivity
```

## Clock and weather screen saver

TV Home includes a full-screen Android Dream with clock, current weather, and a five-day forecast. The screen fades in while the cards gently zoom into place and the wallpaper dims, with a short fade on exit. It uses the launcher's weather settings and cached forecast, refreshes while active, and slowly shifts the cards to reduce image retention. Select it in the TV's screen saver settings, or over ADB:

```bash
adb shell settings put secure screensaver_components com.tvhome.launcher/com.tvhome.launcher.AmbientDreamService
adb shell settings put secure screensaver_enabled 1
```

To restore the onn Google TV screen saver:

```bash
adb shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop
```

## Remote shortcut buttons

The remote's gear button normally opens a panel inside the Google TV launcher. With that launcher disabled, TV Home's `RemoteKeyService` (an accessibility service) catches the button instead and opens Android Settings. The onn remote sends `KEYCODE_NOTIFICATION`; other remotes send `KEYCODE_SETTINGS`. The onn remote's YouTube button sends `KEYCODE_BUTTON_3`; the service opens YouTube TV for that button.

Enable it over ADB. After every reinstall of TV Home, turn it off and on again, or the button stops working:

```bash
adb shell settings put secure enabled_accessibility_services '""'
adb shell settings put secure enabled_accessibility_services com.tvhome.launcher/com.tvhome.launcher.RemoteKeyService
adb shell settings put secure accessibility_enabled 1
```

## Local overrides

To build under a different application ID, for example to update an install made under an older ID, create `defaults.properties` in the project root. It's ignored by git:

```properties
applicationId=com.example.tvhome
```

In the ADB commands above, replace the part before the `/` with your application ID. The class names after the `/` stay `com.tvhome.launcher.*`.
