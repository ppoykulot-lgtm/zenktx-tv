# ZENKTX TV STREAM

A remote-first live TV app for **Android TV, Android phones and Windows**.

Built for NVIDIA Shield, Chromecast with Google TV, Xiaomi Mi Stick / Mi Box, Sony,
TCL and any Android TV 7.0+ (API 24) device — plus a phone build and a Windows x86_64
desktop build from the same channel list.

Every Android screen is reachable with a D-pad: arrows move, OK selects, BACK returns.
No ads, no account, no subscription.

---

## What is in this repo

```
android/            Android app (Kotlin + Media3/ExoPlayer), tv + mobile flavors
windows/            Windows desktop app (Electron, x86_64 installer + portable)
docs/               Landing page for GitHub Pages (Overview → Downloads → Changelog)
data/               The extracted channel list (JSON + M3U)
logo/               ZENKTX mark, lockup and rendered brand assets
design/             Interactive design samples from the first iteration
tools/              Channel extraction, resource checks and brand asset generator
.github/workflows/  Release pipeline (APKs + EXE) and Pages deployment
```

---

## What the app does

**Guide (Design B) home screen** — category chips over a six-column channel grid with a
detail strip. Chips filter instantly: All, ★ Favourites, Pinoy, News, Sports, Movies,
Kids, Entertainment, Documentary, Music, International.

**Player**
- HLS (`.m3u8`) and DASH (`.mpd`) through Media3 / ExoPlayer.
- **CH+ / CH−** (or D-pad up/down) hops through the list you were browsing.
- A browser user-agent, cross-protocol redirects and two retries fix most
  `error_code_io_bad_http_status` failures.
- **Stall watchdog**: if no frame arrives within 18 s, the player replaces the black
  screen with the actual reason — HTTP status, geo-block, codec, timeout or a dead link —
  plus `Retry`, `Next channel` and `Back to guide`.

**Library tools**
- ★ Favourites with a dedicated chip and star badges.
- **Logo editor** per channel: set a URL for missing artwork, or remove the box entirely.
- **Hide channels** you never watch; restore them from Settings.
- **Playlists**: add any M3U/M3U8 or ZENKTX JSON URL, enable/disable and remove sources.
- **GitHub sync**: pull a channel file from a repo, or push your current list back through
  the Contents API (needs a fine-grained token with `contents:write`).
- **Automatic updates**: checks GitHub Releases on launch, downloads the newer APK and
  hands it to the system installer.

Windows has the same guide, favourites, logo edits, playlists, GitHub sync and update
check, driven by the keyboard (`Ctrl+F` search, `↑ ↓` channel hopping, `Esc` back).

---

## Downloads

Every tag publishes the assets of the platforms it actually changed:

| Platform | Asset | Notes |
|---|---|---|
| Android TV | `ZENKTX-TV-<version>.apk` | `ph.zenktx.tv`, leanback banner, landscape |
| Android Mobile | `ZENKTX-Mobile-<version>.apk` | `ph.zenktx.mobile`, portrait, 3-column grid |
| Windows x86_64 | `ZENKTX-Setup-<version>.exe` + `ZENKTX-Portable-<version>.exe` | Windows 10/11 64-bit |

Android TV and Android Mobile are separate apps with separate version lines, kept in
[`android/versions.properties`](android/versions.properties). A tag is only the release
train: each APK carries its own number, and a platform whose files did not change is not
rebuilt, so a TV fix never hands the phone a new version or a new download.

The landing page in [`docs/index.html`](docs/index.html) links to
`releases/latest/download/<asset>`; edit `REPO` and `VERSION` at the top of its script
block when you publish.

---

## Building

### Android

Requirements: **JDK 17** and the **Android SDK** (platform 34, build-tools 34.0.0).
Android Studio is not required.

```bash
cd android
./build.sh                              # debug APK (tv flavor)
./build.sh assembleTvRelease            # signed, minified TV APK
./build.sh assembleMobileRelease        # signed, minified mobile APK
./build.sh installTvDebug               # build + push to a TV on adb
```

Outputs land in `app/build/outputs/apk/<flavor>/<buildType>/`.

`build.sh` finds JDK 17 and the SDK, writes `local.properties` on first run and calls the
committed Gradle 8.7 wrapper. Each flavour reads its own pair from
`android/versions.properties`, and either one can be overridden for a single build:

```bash
./gradlew assembleTvRelease -Ptv.versionName=1.6.0 -Ptv.versionCode=10600
./gradlew assembleMobileRelease -Pmobile.versionName=1.5.7 -Pmobile.versionCode=10507
```

### Release signing

`android/keystore.properties` + `android/zenktx-release.jks` (both git-ignored) hold the
release key. Without them the release build still runs but produces an unsigned APK.

```properties
storeFile=zenktx-release.jks
storePassword=…
keyAlias=zenktx
keyPassword=…
certSha256=71:A6:…
```

`certSha256` is the SHA-256 of the signing certificate. Gradle bakes it into
`BuildConfig.CERT_SHA256`, and `Integrity.check()` compares it at runtime: a repackaged
APK signed with another key shows an "Unofficial build" screen instead of the app.

Keep the `.jks` and its password safe — updates to an installed app must be signed with
the same key or users have to uninstall first.

### Windows

```bash
cd windows
npm install
npm start            # development window
npm run dist         # NSIS installer + portable exe in windows/dist
```

`npm run dist` vendors `hls.js` (from `node_modules`) and `dash.js` (the pinned
4.7.4 browser bundle from `cdn.dashjs.org` — the npm `dash.js` package no longer
ships a browser build) into `renderer/vendor/`, writes
`integrity.json` (sha256 of every shipped file) and runs electron-builder for x64.

The packaged app verifies `integrity.json` at startup and refuses to launch when a shipped
file was modified. That catches a repacked `app.asar`, but it is tamper *evidence*, not
encryption: someone with the source and the will can remove the check.

### Landing page

`docs/index.html` is self-contained (inline CSS/JS, three real screenshots) and works on
any static host. Publish it as-is or copy the `<style>`/sections into an existing site.

---

## GitHub setup

1. Create an empty repository, then:

   ```bash
   git add .
   git commit -m "ZENKTX TV Stream 1.1.0"
   git branch -M main
   git remote add origin https://github.com/<you>/<repo>.git
   git push -u origin main
   ```

2. Add the release secrets (**Settings → Secrets and variables → Actions**):

   | Secret | Value |
   |---|---|
   | `RELEASE_KEYSTORE_BASE64` | `base64 -w0 android/zenktx-release.jks` |
   | `RELEASE_KEYSTORE_PASSWORD` | keystore password |
   | `RELEASE_KEY_ALIAS` | `zenktx` |
   | `RELEASE_KEY_PASSWORD` | key password |
   | `RELEASE_CERT_SHA256` | SHA-256 of the certificate (from `keystore.properties`) |

   The workflow fails instead of shipping an unsigned APK when the keystore is missing.

3. Enable **Pages → Source: GitHub Actions** for the landing page, and set the download
   repo in the app if you fork (`Settings → GitHub Sync`, or the `GITHUB_REPO`
   buildConfig field).

4. Cut a release. Bump only the line you changed in `android/versions.properties`, then
   tag with a number that has never been published before (see the note in that file):

   ```bash
   # TV fix: tv.versionName=1.5.7, tv.versionCode=10507; mobile untouched
   git tag v1.5.7
   git push origin v1.5.7
   ```

   `release.yml` works out which platforms that tag touched — `src/main`, the catalogue and
   the Gradle files reach both apps, `src/tv` and `src/mobile` reach only their own — and
   builds just those. The release then carries only those assets, so the phone's updater
   stays quiet until the phone itself changes.

---

## The channel list

`data/channels.json` was extracted from the user's own
`IPTVSource-windows-1.0.0-setup.exe`: the packaged Electron `app.asar` fetches its list from
a public Firebase endpoint, and that payload was normalised into this shape.

```json
{
  "id": 1,
  "name": "3RSTV",
  "slug": "3rstv",
  "category": "Documentary",
  "logo": "https://…/3rstv.svg",
  "url": "https://…/mono.ts.m3u8",
  "streamType": "hls",
  "quality": "HD",
  "isFilipino": true
}
```

Replace `android/app/src/main/assets/channels.json` (and `windows/assets/channels.json`)
with your own list, or add it as a playlist at runtime — no rebuild needed. The app merges
sources in order, removes duplicate URLs and applies your local edits on top.

> **You are responsible for what you ship.** This is a development sample taken from a
> third-party app; redistributing television channels requires the rights to do so. Swap in
> a licensed list before publishing, and read the disclaimer on the landing page.

---

## Verified on the emulator (2 November 2026)

Android TV AVD (`ZenktxTV`, API 36, 1920×1080), debug build of 1.1.0:

| Check | Result |
|---|---|
| Cold start, no crash, stays foreground | pass |
| Chip row renders without clipping when focused (the reported bug) | pass |
| Favourites + Pinoy chips, detail strip, 485 channels | pass |
| Long-press channel actions (favourite / logo / hide) | wired, not exercised on device |
| Live HLS playback (Kids channel) | pass — video + LIVE + CH ▲/CH ▼ |
| HTTP 410 stream → readable error panel with Retry / Next / Back | pass |
| `Next channel` hops to the following channel | pass |
| Settings screen: playlists, GitHub sync, updates, logos, hidden | pass |
| Open/close playlists, add-source dialog | pass (dialog renders, D-pad reachable) |
| Signed **release** build (R8 + resource shrinking, 1.6 MB) | pass — signature matches the pinned cert, installs over the previous build and renders 485 channels |
| Mobile flavor build | pass (not yet run on a phone) |
| Windows installer | see below |

Windows x64, 1.1.0 unpacked build (`--smoke`, `--play=hls`), 2 November 2026:

| Check | Result |
|---|---|
| Guide renders 485 channels, 11 chips, detail strip | pass |
| No renderer errors, no blocked resources | pass |
| HLS playback, three channels, video time advancing | pass |
| DASH playback through dash.js 4.7.4 (reference manifest) | pass |
| Packaged `app.asar` build passes its integrity check | pass |
| `npm run dist` → `ZENKTX-Setup-1.1.0.exe` + `ZENKTX-Portable-1.1.0.exe` | pass, unsigned |

Screenshots: [guide](screenshots/tv-110-home.png) ·
[chips](screenshots/tv-110-chips.png) ·
[player](screenshots/tv-110-play-hls.png) ·
[error panel](screenshots/tv-110-player.png) ·
[settings](screenshots/tv-110-settings.png)

### Known gaps

- **DASH (`.mpd`) still renders black on the emulator.** Media3 creates the decoder and
  the position advances, but no frame reaches the surface — most likely the emulator's
  software GPU. Unverified on real hardware; check a Shield before relying on DASH.
- Dead links are common in the bundled list (HTTP 410 from the source). The player now says
  so instead of showing black, but it cannot revive them.
- No EPG, no search screen on Android, no picture-in-picture.
- Cleartext HTTP is enabled because many IPTV endpoints are plain `http://`.
- The Windows build has not been installed on a clean machine here; it is built and
  smoke-started locally. The installers are unsigned, so SmartScreen warns on first run.
- Most bundled DASH channels point at hosts that refused or timed out from this machine
  (and one asks for Widevine DRM), so the Windows DASH check used a reference manifest.

---

## Licence

MIT for the code in this repository. Channel logos and streams belong to their respective
owners.
