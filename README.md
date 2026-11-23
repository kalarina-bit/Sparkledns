<p align="center">
  <img src="assets/icon-rounded.png" width="104" alt="SparkleDNS icon">
</p>

<h1 align="center">SparkleDNS: Private DNS &amp; DoH</h1>

<p align="center">
  <b>Encrypted DNS over HTTPS, tracker blocklists, live query log and per-app rules</b><br>
  <sub>Only DNS goes through the app — the rest of your traffic stays on your normal connection.</sub>
</p>

<p align="center">
  <a href="https://github.com/kalarina-bit/Sparkledns/releases/latest"><img src="https://img.shields.io/github/v/release/kalarina-bit/Sparkledns?style=flat-square&label=release&labelColor=1b1b1b&color=2e7d32" alt="Latest release"></a>
  <a href="https://f-droid.org/packages/cc.skysparkle.sparkledns/"><img src="https://img.shields.io/f-droid/v/cc.skysparkle.sparkledns?style=flat-square&label=f-droid&labelColor=1b1b1b&color=1976d2" alt="F-Droid version"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-1b1b1b?style=flat-square" alt="License: GPL-3.0"></a>
</p>

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=cc.skysparkle.sparkledns"><img src="assets/badge_google_play.png" height="52" alt="Get it on Google Play"></a>
  <a href="https://f-droid.org/packages/cc.skysparkle.sparkledns/"><img src="assets/badge_fdroid.png" height="52" alt="Get it on F-Droid"></a>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/kalarina-bit/Sparkledns"><img src="assets/badge_obtainium.png" height="52" alt="Get it on Obtainium"></a>
  <a href="https://github.com/kalarina-bit/Sparkledns/releases/latest"><img src="assets/badge_github.png" height="52" alt="Get it on GitHub"></a>
</p>

<p align="center">
  <img src="assets/screenshots/1-private-dns-left.png" width="160" alt="Private DNS in one tap"><img src="assets/screenshots/2-private-dns-right.png" width="160" alt="You're protected">
  <img src="assets/screenshots/3-see-every-request.png" width="160" alt="See every request">
  <img src="assets/screenshots/4-block-trackers-and-malware.png" width="160" alt="Block trackers and malware">
</p>

## Features

- **Encrypted DNS in one tap** — DoH (RFC 8484) to the server you choose, from the app or a Quick Settings tile. No root.
- **Server test** — protocol and latency of each server; switch while connected.
- **Blocklists** — your own domains plus hosts/domain/AdBlock lists by URL (HaGeZi Pro, Pro++, TIF), auto-updated, Wi-Fi only if you like.
- **Live query log** — filters, one-tap blocking, 24-hour and 7-day charts. Stays on your device.
- **Per-app DNS** — chosen apps bypass the tunnel.
- **Private by design** — no analytics, no ads, no Google Play Services.

## How it works

SparkleDNS uses Android's VPN interface to create a **local** tunnel that captures DNS traffic only. It is not a VPN service: there is no remote server and your IP address does not change.

## Verify the APK

Every official APK is signed with the same key:

```sh
apksigner verify --print-certs SparkleDNS-v<version>.apk
```

```
42:F6:30:63:4B:D0:F8:7F:49:17:85:A8:84:87:70:E4:45:96:7B:36:F1:02:8E:FD:16:F8:F9:52:01:27:3A:29
```

On Android, use [AppVerifier](https://github.com/soupslurpr/AppVerifier) with app ID `cc.skysparkle.sparkledns`.

## Build

JDK 17+ and Android SDK 36:

```sh
./gradlew assembleRelease
```

<details>
<summary>Releasing</summary>

1. Bump `versionName` and `versionCode` in `app/build.gradle.kts` (`versionCode = major * 10000 + minor * 100 + patch`).
2. Add `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`.
3. Tag the commit as `v<versionName>` (e.g. `v1.0.9`) and upload the signed APK to the release right away — F-Droid picks up new tags.

</details>

## Contributing

Bug reports, server and blocklist suggestions, translations and pull requests are welcome in the [issue tracker](https://github.com/kalarina-bit/Sparkledns/issues). Please read [CONTRIBUTING.md](CONTRIBUTING.md) first.

## Sponsor

<a href="https://skysparkle.cc"><img src="assets/skysparkle.svg" width="20" alt=""></a> Sponsored by **[Skysparkle](https://skysparkle.cc)** — independent encrypted DNS from Lithuania and the app's default server. Help keep the servers running on [Ko-fi](https://ko-fi.com/skysparkle).

## License

[GPL-3.0-only](LICENSE)
