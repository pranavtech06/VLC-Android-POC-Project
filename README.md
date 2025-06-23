# VLC-Android-POC-Project

VLC Video Player (Jetpack Compose + LibVLC)
This is a simple Android video player built using Jetpack Compose and the VLC media player library
(libVLC).

The app supports playing videos from both the file system and online URLs. It includes support for:
- Picking videos from local storage (Android 13+ Media Picker)
- Streaming videos via URL
- Play/Pause controls
- Seek bar with current time and duration
- Fullscreen toggle (immersive mode)

Features
- Play video from local storage using Androids Media Picker.
- Stream video via a public URL.
- Seek bar to navigate through the video.
- Play/Pause control button.
- Fullscreen toggle with system UI hidden/shown.

Tech Stack
- Kotlin
- Jetpack Compose
- LibVLC Android
- AndroidX Activity & Compose Libraries

Setup & Run
1. Clone the repo
2. Open in Android Studio
3. Add LibVLC dependency
 Make sure to add the official LibVLC SDK as a .aar or via Maven in your build.gradle.
 dependencies {
 implementation 'org.videolan.android:libvlc-all:3.5.1'
 }
4. Permissions
 Ensure the following permissions are in AndroidManifest.xml:
 <uses-permission android:name="android.permission.INTERNET" />
 <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
 <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
5. Run the app
 Connect a physical device or use an emulator (API 30+ recommended) and click Run.

Requirements
- Android Studio Flamingo or newer
- Android 10 (API 29) or above recommended
- LibVLC 3.5.1 or newer

Known Issues
- Some URLs may not stream if CORS or encoding is unsupported.
- Fullscreen UI behavior may differ slightly between devices.
- Video layout scaling may require fine-tuning for various aspect ratios.
