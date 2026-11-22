# skylight-switcher

A tiny Android app for a Skylight Calendar (15", model D156, Android 12) that
switches between the Skylight app and the Home Assistant app with a swipe.

The Skylight has no navigation bar, no recents screen and no gesture
navigation: its own app is the home screen, and Android 12's gestures and
recents come from the launcher. So this app draws two invisible strips on the
left and right screen edges. Swiping inward from either one brings the Home
Assistant app to the front when the Skylight app is showing, and goes back to
the Skylight app (the home screen) otherwise. It runs as a foreground service
and starts on boot.

It needs ADB access to the Skylight once, to install it and grant two
permissions:

- **Display over other apps** (`SYSTEM_ALERT_WINDOW`), for the edge strips.
  It also lets the service bring apps to the front from the background.
- **Usage access** (`GET_USAGE_STATS`), to see which app is in front.

## Build

Needs a JDK 17 and the Android SDK's API 28 platform and build-tools 34. No
Gradle: `build.sh` runs `javac`, `d8`, `aapt2`, `zipalign` and `apksigner`
directly.

```sh
sh build.sh        # build/skylight-switcher.apk
```

`ANDROID_HOME` (or the Windows default `%LOCALAPPDATA%\Android\Sdk`), `BT`
(build-tools dir), `ANDROID_JAR` and `KEYSTORE` override the defaults. The
APK is signed with `$KEYSTORE`, created on first use outside the repo; keep it,
since updates must be signed with the same key.

## Install

```sh
adb install -r build/skylight-switcher.apk
adb shell appops set house.pape.switcher SYSTEM_ALERT_WINDOW allow
adb shell appops set house.pape.switcher GET_USAGE_STATS allow
adb shell am start -n house.pape.switcher/.MainActivity
```

The last step only matters the first time: Android sends no boot broadcast to
an app that was never opened.

## Notes

- The Home Assistant package is `io.homeassistant.companion.android.minimal`
  (the build without Google services, which the Skylight lacks); change
  `EdgeService.HA` for the full build.
- The strips are 16 dp wide and a swipe has to travel 60 dp inward
  (`STRIP_DP`, `SWIPE_DP`). Touches inside the strips don't reach the apps.
- A two- or three-finger swipe anywhere on screen isn't possible for an
  ordinary app: it would have to see every touch meant for the other apps.
  That takes root, the ADB shell user, or accessibility touch exploration,
  which changes how every tap works.
