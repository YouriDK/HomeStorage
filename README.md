# Boxpix

A minimalist, native Android photo gallery for the disk plugged into a **Freebox Pop** (French ISP home server) — browsed through the **official Freebox API**, with no cloud, no third-party server, and no vendor lock-in. Your files stay ordinary files on your own disk; everything the app adds (thumbnails, metadata journals) lives *next to* them in plain formats.

## What it does

- **Explorer as the gallery** — folder grid with cover tiles and counters, breadcrumb navigation, per-folder sort (name/date/size), 2–4 columns by pinch.
- **Full-screen viewer** — progressive loading (blurred thumbnail → HD), swipe between media, EXIF/info panel; videos stream over HTTP range requests with bottom-anchored, thumb-friendly controls.
- **Organize** — create/rename/move folders, multi-select, and a one-handed **sort mode**: one photo at a time, pinned destination folders, quick tags, swipe to skip, undo.
- **Tags & favourites** — instant local tagging, combinable search filters (name, tags, date range, folder, type), optional deferred **XMP write-through** into JPEG files (off by default).
- **Batch metadata editing** — apply tags, fix the capture date, or set a location on a whole selection at once (made for stacks of scanned photos).
- **Trash, not `rm`** — deleting moves files to a `.trash` mirror on the disk, restorable for 30 days, with confirmation and per-folder protection locks.
- **Save to device** — resumable download queue into Android's Downloads via MediaStore, with a size guard on metered connections.
- **Offline mode** — when the box is unreachable, the explorer serves the local index read-only (tags still work).
- **Worker mode** — a spare phone left on a charger becomes a night worker: it grinds thumbnail/video-poster backlogs, drains queues and purges the trash, coordinating with your main phone **through the disk only**.

## How it works

The disk is the source of truth *and* the coordination bus — devices never talk to each other directly:

```
<disk>/.thumbs/<mirror>/<name>.webp    512 px WebP sidecar thumbnails
<disk>/.trash/<mirror>/                trash (path mirror), auto-purged after 30 days
<disk>/.meta/tags.json                 tags journal (last-write-wins + action log)
<disk>/.meta/folders.json              shared protected/excluded folder lists
<disk>/.meta/worker-status.json        worker heartbeat
```

The Freebox emits no filesystem events, so a **reconciler** periodically diffs the desired state against the real one and catches up the delta — thumbnails, EXIF date index, queues. Every job is idempotent; interrupting anything is safe. The local Room database is a reconstructible cache scoped to the disk you are browsing.

## Stack

Kotlin · Jetpack Compose (Material 3, true-black AMOLED theme) · Hilt · Ktor · Room · Coil · WorkManager · ExoPlayer (media3) · Lucide icons. All storage access goes through a single `StorageProvider` interface — the Freebox implementation handles pairing (`app_token` granted physically on the box), HMAC-SHA1 challenge sessions, automatic LAN ↔ remote switching, async filesystem tasks and WebSocket uploads.

## Building

```bash
./gradlew assembleDebug   # build
./gradlew test            # unit tests
```

- Requires JDK 17+, Android SDK 36. `minSdk 26`, `targetSdk 36`.
- The version lives in one place: `boxpix.version` (`MAJOR.MINOR.PATCH`) in `gradle.properties`. `versionCode` is derived from it as `major*10000 + minor*100 + patch`, so it can only grow; `versionName` stays plain semver. The short git SHA is exposed as `BuildConfig.GIT_SHA` and shown at the bottom of Settings — never folded into the version name.
- **No Freebox needed to develop**: debug builds ship a deterministic fake provider (~190 seeded media, simulated latency, disk-wakeup mode). It is the default in debug — switch to the real box in Settings → Debug.
- With a real Freebox Pop: launch the app on the same LAN, tap *Connect to my Freebox*, accept the pairing on the box's front panel, pick a disk, *Start scanning*.

## Security model, in one paragraph

All restrictions (protected folders, scan exclusions, trash-instead-of-delete) are **client-side discipline**, not access control: any client paired with the box has full filesystem access through the Freebox API. The app token is stored in the Android Keystore and never leaves the device; remote access goes over HTTPS; an optional app lock (PIN/biometrics) guards the phone itself. Truly sensitive content on a shared disk needs encryption — a client-side encrypted vault is on the roadmap.

## Status

Pre-release (v0.1.0). Core milestones are code-complete; the Freebox client, explorer and thumbnail pipeline are validated against a real Freebox Pop, the rest is stabilizing on-device. Roadmap: encrypted vault, family profiles/invitations, local web back-office.
