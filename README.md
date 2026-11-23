# skylight-switcher

A tiny Android app for a Skylight Calendar (15", model D156, Android 12) that
switches between the Skylight app and the Home Assistant app with a two- or
three-finger swipe left or right, anywhere on screen.

The Skylight has no navigation bar, no recents screen and no gesture
navigation: its own app is the home screen, and Android 12's gestures and
recents come from the launcher. An ordinary app can't see touches meant for
other apps, but on the Skylight the touchscreen's event device
(`/dev/input/event3`) is world-readable and SELinux is permissive, so this app
reads the raw touch events itself. The touches still reach the apps as normal.
A multi-finger swipe brings the Home Assistant app to the front when the
Skylight app is showing, and goes back to the Skylight app (the home screen)
otherwise. It runs as a foreground service and starts on boot.

If the touchscreen can't be read (another device, or a firmware update that
tightens permissions), it falls back to two invisible strips on the left and
right edges: swipe inward from either one to switch.

It needs ADB access to the Skylight once, to install it and grant two
permissions:

- **Display over other apps** (`SYSTEM_ALERT_WINDOW`), which lets the service
  bring apps to the front from the background (and draw the fallback strips).
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
- A swipe fires when the fingers' average position moves 250 touchscreen units
  sideways (about 250 px; `SWIPE_UNITS`) and more than twice as far as it moves
  up or down, so pinches and vertical scrolls don't count. The app under your
  fingers still gets the swipe too.
- The fallback strips are 16 dp wide and a swipe has to travel 60 dp inward
  (`STRIP_DP`, `SWIPE_DP`). Touches inside the strips don't reach the apps.
- An accessibility service can detect multi-finger gestures, but only in touch
  exploration mode (taps become select-then-double-tap). Android 11's
  passthrough regions restore normal taps, but a full-screen passthrough region
  also turns the gesture detection off, so that route doesn't work.
