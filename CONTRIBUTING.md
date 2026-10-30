# Contributing to SparkleDNS

Thanks for wanting to help! This guide explains where to contribute, what fits the project and how to send changes.

## Where to contribute

Issues and pull requests go to the repository on [Gitea](https://git.skysparkle.cc/kalarina/Sparkledns).

## Ways to help

- **Report a bug** — use the *Bug report* template.
- **Add a DoH server** — open an issue with the *DoH server or blocklist* template, see [Adding a DoH server](#adding-a-doh-server).
- **Suggest a blocklist** — use the *DoH server or blocklist* template.
- **Suggest a feature** — use the *Feature request* template; check [Out of scope](#out-of-scope) first.
- **Fix a translation** — use the *Translation fix* template or send a pull request.
- **Write code** — see [Pull requests](#pull-requests).

## Before you start

- **Small changes** (typos, obvious bugs, translation fixes): send a pull request directly.
- **Anything bigger** (new features, UI changes, new dependencies): open an issue first and wait for a go-ahead, so your work isn't wasted.

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

## Adding a DoH server

Servers ship with the app in [`Providers.kt`](app/src/main/java/cc/skysparkle/sparkledns/Providers.kt), so a new server reaches users with the next release. The app never downloads a server list.

To suggest a server, open an issue with the *DoH server or blocklist* template, or send a pull request that adds one entry to `Providers.builtIn` with the server's name, DoH URL, privacy policy link, bootstrap IPs, whether it supports HTTP/3 and, optionally, the operator's own donation page.

The server must:

- be public and free, without an account;
- support RFC 8484;
- publish what it logs, and not sell or share query data;
- be run by a non-profit, a community or an individual rather than a large company.

Before sending, check that it works: in the app choose **Custom server**, enter the URL and tap **Test server**.

If you are unsure whether a server fits, open an issue with the *DoH server or blocklist* template first.

## Blocklists

Blocklist presets must be public, actively maintained and licensed for this kind of use. Use the *DoH server or blocklist* template and include a link to the list's homepage.

## Translations

SparkleDNS is available in exactly six languages: English, Russian, German, Japanese, Lithuanian and Chinese. Fixes to these are welcome; new languages are not accepted.

- App strings: `app/src/main/res/values-<lang>/strings.xml` (English is `values/`).
- Store listing: `fastlane/metadata/android/<locale>/`.

If your code adds new strings, add them to `values/strings.xml`. Translations for the other languages are welcome but not required — mention missing ones in the pull request.

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Keep one topic per pull request.
3. Follow the existing Kotlin and Jetpack Compose style; keep comments short and in English; don't reformat unrelated code.
4. Make sure `./gradlew assembleDebug testDebugUnitTest` passes, and test on a device or emulator. For changes that may affect speed or memory, `scripts/bench.sh` compares two APKs on a rooted device or emulator (`scripts/bench-build.sh` builds them from two revisions).
5. Write short, plain commit messages in English (for example `Fix tunnel restart after server switch`).
6. Add screenshots for UI changes.

## Security

Please don't post details of security issues publicly. Open a short issue titled "Security contact" without any details, and the maintainer will reach out privately.

## License

By contributing, you agree that your contribution is licensed under the [GNU General Public License v3.0 or later](LICENSE), the same as the rest of the project.
