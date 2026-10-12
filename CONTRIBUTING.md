# Contributing to SparkleDNS

Thanks for wanting to help! This guide explains where to contribute, what fits the project and how to send changes.

## Where to contribute

Issues and pull requests go to the repository on [Gitea](https://git.skysparkle.cc/kalarina/Sparkledns).

## Ways to help

- **Report a bug** — use the *Bug report* template.
- **Suggest a DoH server or blocklist** — use the *DoH server or blocklist* template.
- **Suggest a feature** — use the *Feature request* template; check [Out of scope](#out-of-scope) first.
- **Fix a translation** — use the *Translation fix* template or send a pull request.
- **Write code** — see [Pull requests](#pull-requests).

## Before you start

- **Small changes** (typos, obvious bugs, translation fixes): send a pull request directly.
- **Anything bigger** (new features, UI changes, new dependencies, new languages): open an issue first and wait for a go-ahead, so your work isn't wasted.

## Ground rules

- No ads, analytics, telemetry, trackers or accounts.
- DNS queries may only go to the DoH server the user selected — never anywhere else.
- No Google Play Services, Firebase or other proprietary libraries — the app must stay buildable for F-Droid.
- New dependencies must be open source and justified in the pull request.
- Be respectful in issues and reviews.

## Out of scope

These will not be accepted:

- Changing the default DoH server.
- Logging, uploading or sharing users' DNS queries.
- Features that require Google Play Services or a proprietary SDK.

## DoH servers and blocklists

- **DoH servers** must be public, support RFC 8484 and publish a privacy policy.
- **Blocklists** must be public, actively maintained and licensed for this kind of use.

Include links to the server's documentation or the list's homepage in the request.

## Translations

SparkleDNS is currently available in English and Russian. Fixes are welcome; for a new language, open an issue first.

- App strings: `app/src/main/res/values-<lang>/strings.xml` (English is `values/`).
- Store listing: `fastlane/metadata/android/<locale>/`.

If your code adds new strings, add them to `values/strings.xml`. Translations are welcome but not required — mention missing ones in the pull request.

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Keep one topic per pull request.
3. Follow the existing Kotlin and Jetpack Compose style; keep comments short and in English; don't reformat unrelated code.
4. Make sure `./gradlew assembleDebug` builds, and test on a device or emulator.
5. Write short, plain commit messages in English (for example `Fix tunnel restart after server switch`).
6. Add screenshots for UI changes.

## Security

Please don't post details of security issues publicly. Open a short issue titled "Security contact" without any details, and the maintainer will reach out privately.

## License

By contributing, you agree that your contribution is licensed under the [GNU General Public License v3.0 or later](LICENSE), the same as the rest of the project.
