# Archive Fetcher

A free Android app to browse and download files from the [Internet Archive](https://archive.org).
It is built for handhelds such as the RG Rotate (square screen, D-pad) and works just as well on phones and tablets,
upright or sideways, with touch or a controller. Android 7.0 or newer.

The app **ships no catalog**: it contains no titles, links or identifiers. What you can browse comes from **addons**
that you add yourself, small JSON files that map consoles to public Internet Archive items.
You are responsible for the addons you add and for what you download.

## Features

- Categories → consoles → files, with search, filters (region, language, format, collection) and select all
- Search across every console, offline, from a local index
- Large zips that hold a whole console are listed game by game, so you download only what you pick
- "Download later" list that can start by itself on Wi-Fi while charging, or at night
- One library folder with a sub-folder per console, named for ES-DE / ArkOS / ROCKNIX, Batocera / Knulli, or in full
- Files already in the folder are marked, and can be hidden
- Parallel downloads that continue with the screen off, pause and resume, optional ZIP extraction, MD5 check
- Internet Archive login (email and password, or S3 keys) for items that require it

## Getting started

1. Download the APK from the [Releases](https://github.com/DanieleKun/Rom-Downloader/releases) page and install it
   (Android asks to allow installs from your browser or file manager the first time).
2. On first start choose a library folder and the folder layout of your frontend.
3. Add an addon under **Addons**: paste its address, or pick a downloaded `.addon.json` with **Add from file**.
   The reference addon, the specification and the guide to write your own are in
   [Addon-Archive-Fetcher](https://github.com/DanieleKun/Addon-Archive-Fetcher).
4. Pick a category and a console, select files and press **Download**, or **Later** to download them another time.

## Building

Requirements: JDK 17 and the Android SDK (compile SDK 36).

```
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Debug builds also accept `http://` addon addresses for `localhost`, `127.0.0.1` and `10.0.2.2` (emulator), which is
handy while writing an addon. Release builds accept `https://` only.

## Releasing

See [docs/RELEASING.md](docs/RELEASING.md).

## License

MIT, see [LICENSE](LICENSE).
