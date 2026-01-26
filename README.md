# FlixtorTV

![Platform](https://img.shields.io/badge/platform-Android%20TV-3DDC84)
![Language](https://img.shields.io/badge/language-Kotlin-7F52FF)
![License](https://img.shields.io/badge/license-MIT-blue)

Android TV app (Jetpack Compose + WebView) that loads the Flixtor website in a TV-friendly shell with:
- a splash screen animation + launch sound
- a **D‑pad/remote cursor** ("mouse" pointer) for navigation
- focus/keyboard handling tuned for Android TV/WebView
- persistence of the last visited URL

> Note: This project is primarily a **WebView wrapper**. Availability of content depends on the upstream website.

## Tech stack
- Kotlin
- Android Gradle (Kotlin DSL)
- Jetpack Compose (Material3 + AndroidX TV)
- Android WebView

## Project structure
- `app/src/main/java/io/pushdream/flixtortv/MainActivity.kt` — Compose UI + splash + main WebView screen
- `app/src/main/java/io/pushdream/flixtortv/CursorWebView.kt` — cursor/pointer overlay + D-pad navigation + keyboard/focus logic
- `app/src/main/AndroidManifest.xml` — Android TV launcher intent (`LEANBACK_LAUNCHER`)
- `app/src/main/res/drawable/tvbro_pointer.xml` — pointer drawable
- `app/src/main/res/drawable/logo.png` — splash logo
- `app/src/main/res/raw/launch_sound.mp3` — splash sound

## Screenshots
Add screenshots here (recommended when the repo is public).

Suggested captures:
- Home / splash screen
- WebView + cursor overlay in action
- Error/retry screen (if any)

## Requirements
- Android Studio (latest stable)
- JDK 11 (project is configured for Java/Kotlin target 11)
- Android SDK (compileSdk/targetSdk: 35)

## Build & run
### Android Studio
1. Open the repo folder in Android Studio.
2. Let Gradle sync.
3. Select an Android TV device/emulator.
4. Run the `app` configuration.

### Command line
```bash
./gradlew :app:assembleDebug
```

## Configuration
- Default start URL is set in `MainActivity.kt`:
  - `https://flixtor.to/`
- The app stores the last visited URL in SharedPreferences under `last_url`.

## Notes / known behaviors
- The cursor/keyboard logic in `CursorWebView.kt` includes safeguards against **"ghost keyboard"** behavior by blurring the active DOM element when the keyboard closes.
- If you run into focus issues on specific TV devices/remotes, start by checking:
  - `isKeyboardVisible` detection thresholds
  - D-pad key handling in `onKeyDown` / `onKeyUp`

## License
MIT (see `LICENSE`).

## Disclaimer
This project is provided for educational/personal use. You are responsible for complying with applicable laws, the terms of service of any websites accessed, and the policies of the Android TV platform.
