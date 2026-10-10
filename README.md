<p align="center">
  <img src="assets/icon-rounded.png" width="128" alt="SparkleDNS icon">
</p>

<h1 align="center">SparkleDNS</h1>

<p align="center">
  <a href="https://git.skysparkle.cc/kalarina/Sparkledns/releases"><img src="assets/get-it-on-gitea.png" height="60" alt="Get it on Gitea"></a>
</p>

<p align="center">
  <img src="https://img.shields.io/github/downloads/kalarina-bit/Sparkledns/latest/total?label=downloads&color=2e7d32&labelColor=1b1b1b" alt="downloads counter">
</p>

**Lightweight DNS-over-HTTPS client for Android** (Kotlin, Jetpack Compose).

The app starts a local VPN that captures only DNS traffic and forwards every query to the selected DoH server (RFC 8484) — everything else goes through your normal connection.

## Features

- 🔌 **One-tap connect/disconnect**, with a Quick Settings tile
- 🔄 **Switch servers on the fly** — the tunnel restarts automatically
- 🚀 **Built-in server test** showing protocol and latency
- 📊 **Live query monitor** with stats, filters and one-tap blocking
- 📱 **Per-app DNS** — chosen apps bypass the tunnel
- 🚫 **Custom blocklists** — your own domains plus hosts/domain/AdBlock lists by URL (HaGeZi Pro++ and TIF presets)
- 🔓 **No analytics, no Google Play Services**

## Screenshots

<p align="center">
  <img src="assets/screenshots/1-your-dns-your-choice.png" width="200" alt="Your DNS, your choice">
  <img src="assets/screenshots/2-protected.png" width="200" alt="Protected via SkySparkle">
  <img src="assets/screenshots/3-stay-informed.png" width="200" alt="Stay informed">
  <img src="assets/screenshots/4-block-your-way.png" width="200" alt="Block your way">
  <img src="assets/screenshots/5-choose-your-own-route.png" width="200" alt="Choose your own route">
</p>

## Installation

<img src="assets/icon-install.png" width="36" align="left">

1. Download the APK for the version you want from the [Releases page](https://github.com/kalarina-bit/Sparkledns/releases).
2. On your Android device, allow installs from unknown sources for the app you use to open the file (Settings → Apps → Special access → Install unknown apps).
3. Open the downloaded `.apk` file and confirm the install.

## Verifying a download

<img src="assets/icon-verify.png" width="36" align="left">

Every official APK is signed with the same key. Check the signing certificate before installing:

```sh
apksigner verify --print-certs SparkleDNS-v1.0.6.apk
```

The SHA-256 fingerprint of the signing certificate must be:

```
42:F6:30:63:4B:D0:F8:7F:49:17:85:A8:84:87:70:E4:45:96:7B:36:F1:02:8E:FD:16:F8:F9:52:01:27:3A:29
```

(`apksigner` prints the same value in lowercase without colons.)

On Android you can check it with [AppVerifier](https://github.com/soupslurpr/AppVerifier) (app ID `cc.skysparkle.sparkledns`).

## Building from source

```sh
./gradlew assembleRelease
```

Or open the project in Android Studio and run the `app` configuration. Requires Android 8.0 (API 26) or newer; HTTP/3 requires Android 14.

The version lives in `app/build.gradle.kts` (`versionName`, `versionCode = major * 10000 + minor * 100 + patch`). For every release, add `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` and tag the commit as `v<versionName>` (for example `v1.0.6`) — F-Droid detects new releases from these tags.

## Repository structure

<img src="assets/icon-structure.png" width="36" align="left">

```
.
├── app/                 # Android app source (Kotlin, Jetpack Compose)
├── assets/              # README images
│   └── screenshots/     # App screenshots
├── fastlane/            # F-Droid store listing, screenshots and changelogs
├── gradle/              # Gradle wrapper
├── LICENSE              # GNU GPLv3
└── README.md
```

APK builds are published as assets on the [Releases page](https://github.com/kalarina-bit/Sparkledns/releases), not stored in this repository.

## License

This project is licensed under the **GNU General Public License v3.0 or later** (GPL-3.0-or-later) — see the [LICENSE](LICENSE) file for details.
