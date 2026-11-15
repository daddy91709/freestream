# FreeStream — Android TV Edition

Lightweight WebView-based streaming client designed for **Android TV**, **Google TV**, **Fire TV Stick**, and Android mobile/tablet devices (`minSdk 24`, `targetSdk 34`).

---

## Features

- **Resilient Automatic Domain Resolution**:
  - Races cached domains with all candidates from [`domains.json`](domains.json) (`full_url`, `domain`, and `old_domain` redirect chains) and verifies the authentic app signature.
  - Falls back to **Certificate Transparency (`crt.sh`)** discovery if both cached and JSON candidates fail.
  - Uses **DNS-over-HTTPS (Cloudflare `1.1.1.1`)** via `okhttp-dnsoverhttps` when system DNS fails or returns ISP sinkholes, and displays an actionable DNS hint when WebView cannot resolve the domain.
- **TV Remote Spatial Navigation (D-Pad)**:
  - Smooth spatial focus navigation with a cyan highlight ring (`#fs-focus-ring`), carousel paging, and modal/overlay focus trapping.
  - Smart `/watch/` controls: `←`/`→` seek, `OK` toggles play/pause, `↑`/`↓` reveal player controls and focus page UI buttons (episodes, audio/subtitles, server), and `BACK` returns focus to direct player control.
- **Enhanced Video Player Controls, OSD & Auto-Resume**:
  - Hardware media keys (`PLAY`, `PAUSE`, `PLAY_PAUSE`, `FF`, `REW`, `NEXT`, `PREV`) and cross-origin iframe relaying via `player_agent.js`.
  - Progressive seek (`±10s` → `±20s` → `±30s` on held/rapid presses) with an integrated fullscreen-compatible OSD overlay (`#fs-player-osd`).
  - Automatic playback position saving & resume (`localStorage`) per title/episode.
- **Virtual Mouse Mode**:
  - Press **Menu** / **Info** (or long-press **OK**) to toggle a 60fps accelerated virtual cursor with hover events and Silk-style edge scrolling.
- **Multi-Layer Ad-Block & Anti-Popup**:
  - Network-level request blocking via [`AdBlocker`](app/src/main/java/com/freestream/tv/AdBlocker.kt) across main frame and player iframes.
  - Native popup/window blocking (`onCreateWindow`, `javaScriptCanOpenWindowsAutomatically = false`) and `window.open` / `target="_blank"` sanitization via `adblock.js`, while still allowing legitimate HTTPS player iframe embeds.
- **Smart Back & Android Gesture Support**:
  - Uses `OnBackPressedDispatcher` alongside `dispatchKeyEvent` to dismiss virtual mouse, fullscreen, dropdowns, modals, page control focus, WebView history, or recoverable error overlays before prompting to exit.
- **In-App Auto-Updater**:
  - Checks GitHub Releases (`releases/latest`) on startup and lets you download and install newer signed APKs directly from your TV remote via `FileProvider`.
- **Android TV Launcher Assets**:
  - Includes native 16:9 Leanback TV banner (`tv_banner`) and adaptive launcher icons.

---

## Project Structure

- `app/src/main/java/com/freestream/tv/`
  - `MainActivity.kt` — Activity lifecycle, network observer, fullscreen/orientation setup, error diagnostics, and update prompt.
  - `DomainResolver.kt` — Domain racing, `domains.json` + `crt.sh` discovery, DoH fallback, and ISP DNS block detection.
  - `WebViewConfigurator.kt` — WebView settings, document-start JS/CSS injection, WebMessage bridge, error filtering, and popup prevention.
  - `AdBlocker.kt` — Ad/tracker host blocklist, frame-aware navigation policy, and HTTP error filtering.
  - `RemoteKeyHandler.kt` & `VirtualCursorController.kt` — D-Pad spatial controls, media keys, long-press toggle, and 60fps virtual cursor.
  - `SoftKeyboardBridge.kt` & `UserAgentProvider.kt` — IME focus handling and desktop UA selection for TV devices.
  - `AppUpdater.kt` — GitHub Releases checker, trusted asset validation, APK downloader, and `FileProvider` installer trigger.
- `app/src/main/assets/`
  - `tv_navigation.js` & `tv_styles.css` — Spatial navigation engine and cyan focus ring styling.
  - `player_agent.js` — Cross-origin iframe player relay, progressive seek, OSD, and auto-resume.
  - `adblock.js` — `window.open` and `target="_blank"` sanitizer.

---

## CI/CD & Automation

- **APK Build & Release** ([`.github/workflows/build.yml`](.github/workflows/build.yml)):
  - Runs `./gradlew testDebugUnitTest lintDebug assembleRelease` on pushes to `main` (ignoring domain-bot commits) and publishes versioned APKs (`v<versionName>`) to both GitHub Artifacts and GitHub Releases.
- **Domain Updater** ([`.github/workflows/update-domain.yml`](.github/workflows/update-domain.yml)):
  - Runs [`.github/scripts/update_domain.py`](.github/scripts/update_domain.py) every 6 hours to follow redirects / query `crt.sh`, verify valid SSL certificates, and update [`domains.json`](domains.json).

---

## Local Build & Tests

```bash
./gradlew testDebugUnitTest lintDebug assembleRelease
```
