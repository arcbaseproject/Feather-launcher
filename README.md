<div align="center">

# Feather Launcher

**An Android home app with a home grid, a dock and a searchable app drawer.**

It looks like stock Android, asks for one permission (to uninstall apps) and has no network code.

![Android 13+](https://img.shields.io/badge/Android-13%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)
![Permissions](https://img.shields.io/badge/permissions-1-blue)
![Internet](https://img.shields.io/badge/internet-none-critical)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

<br>

<img src="docs/screenshots/home.png" width="200" alt="Home screen">&nbsp;&nbsp;
<img src="docs/screenshots/drawer.png" width="200" alt="App drawer">&nbsp;&nbsp;
<img src="docs/screenshots/search.png" width="200" alt="Search">

</div>

---

## Features

<table>
<tr>
<td width="50%" valign="top">

### Home and dock
- Icon grid: you pick the rows and columns
- Dock for your favorite apps
- Your system wallpaper, edge to edge
- Long-press empty space for Wallpaper and Settings

### App drawer
- Swipe up from anywhere on the home screen
- Alphabetical list with a fast-scroll thumb
- Search ignores case and accents: "cafe" finds "Café"
- Enter opens the first result

</td>
<td width="50%" valign="top">

### Drag and drop
- Long-press an icon and drag it
- The cell under your finger lights up
- Drop it on another app and the two swap places

### More
- App shortcuts in the long-press menu
- Work profile apps, with the work badge
- Material You colors in light and dark themes
- An "All apps" TalkBack action on the home grid
- Your layout stays put through reboots, app updates and paused work profiles

</td>
</tr>
</table>

<div align="center">
<img src="docs/screenshots/drag.png" width="200" alt="Drag target highlight">&nbsp;&nbsp;
<img src="docs/screenshots/menu.png" width="200" alt="Long-press menu with an app shortcut">&nbsp;&nbsp;
<img src="docs/screenshots/settings.png" width="200" alt="Settings">
<br>
<sub>Drag target highlight · Long-press menu with an app shortcut · Settings</sub>
</div>

---

## How Feather saves memory

| | |
|---|---|
| **Plain Android views on the home screen** | The launcher uses `Activity`, `GridView` and one custom grid view. Only the settings screen loads Material Components. |
| **One permission** | `LauncherApps` needs no permission. A `<queries>` entry lets Feather see launchable apps without `QUERY_ALL_PACKAGES`. The only permission, `REQUEST_DELETE_PACKAGES`, backs the Uninstall item. |
| **Icons drawn once, at display size** | Feather draws each icon into a `HARDWARE` bitmap, which keeps one copy on the GPU and none on the Java heap. A size-capped LRU cache saves reloading them. |
| **Minimal memory mode, on by default** | Closing the drawer releases its icons and runs one garbage collection. With the mode off, memory stays about 72 MiB higher after you open the drawer. |
| **No background work** | Feather runs no services, polls nothing and schedules no wakeups. Android tells it when you install or remove an app. An idle home screen draws 0 frames. |
| **No offscreen layers** | The drawer slides in. A fade would make the GPU render the full-screen drawer into an extra buffer. |

### Measurements

Pixel 9 Pro XL, LineageOS (Android 17), release build, 39 apps, median of 5 samples:

| | Feather | Trebuchet (stock) |
|---|---|---|
| Total PSS on home | **77 MiB** | 111 MiB |
| Java heap | 1.4 MiB | n/a |
| Cold start | about 120 ms | n/a |
| Warm start | 31 ms | n/a |

> [!NOTE]
> These numbers predate the visual refresh, and the Trebuchet figure comes from a single earlier measurement.
> LineageOS keeps Trebuchet running for Recents and gesture navigation even when Feather is your home app, so their memory adds up.
> The window's own screen buffers take about 43 MiB of Feather's total, and Android decides how many to allocate.
> Run `./benchmark.sh` to measure your own device.

---

## Install

Build the APK and install it over adb:

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Then make Feather your home app in **Settings > Apps > Default apps > Home app**, or run:

```bash
adb shell cmd role add-role-holder android.app.role.HOME dev.feather.launcher
```

To switch back, pick your old launcher on the same screen. Feather leaves it installed.

<details>
<summary><b>Building on NixOS</b></summary>

<br>

You need JDK 21 and an Android SDK with `platforms;android-37.0` and `build-tools;37.0.0`.
Set `sdk.dir` in `local.properties`, or set `ANDROID_HOME`.

Gradle downloads a generic-Linux `aapt2` that NixOS can't run. Point Gradle at the SDK's copy:

```bash
./gradlew -Pandroid.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/37.0.0/aapt2 assembleRelease testDebugUnitTest
```

Gradle signs the release build with your local debug key so you can install it. Sign it with your own key before you distribute it.

</details>

---

## Gestures

| Gesture | Result |
|---|---|
| Swipe up | Opens the app drawer |
| Swipe down at the top of the list, Back, or Home | Closes the drawer |
| Tap | Opens the app |
| Long-press in the drawer | Shortcuts · Add to home · Add to dock · App info · Uninstall |
| Long-press and drag on home | Moves the icon; the target cell highlights |
| Long-press on home without moving | Shortcuts · Remove · App info · Uninstall |
| Long-press empty home | Wallpaper · Settings |

Android shows app shortcuts only to the default home app, so set Feather as default to get them.

---

## Benchmark it yourself

```bash
./benchmark.sh dev.feather.launcher 5      # Feather
./benchmark.sh com.android.launcher3 5     # LineageOS Trebuchet
```

The script makes the launcher you name the default home app and leaves it set. It reports median, min and max for:
- Total PSS, RSS, Java heap, Native heap, Graphics and Swap, on the home screen and after a drawer cycle
- Cold start, warm start and idle CPU

It also lists which launchers are running.

---

## Project layout

```text
app/src/main/java/dev/feather/launcher/
├── MainActivity.kt      home grid, dock, drawer, gestures, menus
├── Apps.kt              app list (LauncherApps), icon loading, LRU cache, layout storage
├── Views.kt             IconView and CellGrid (the home and dock grid)
├── Prefs.kt             settings keys and the theme switch
└── SettingsActivity.kt  settings screen
benchmark.sh             adb benchmark script
```

## Not included

Widgets, icon packs, notification dots, feeds, weather and a frequent-apps row.

## License

[MIT](LICENSE)
