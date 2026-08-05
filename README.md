<p align="center">
  <img src="docs/screenshots/icon.png" width="96" alt="Reader app icon: the tablet drawn in black on white as a plain bezel, a serif capital R filling its screen, the pen docked at the right">
</p>

<h1 align="center">Reader</h1>

<p align="center">A reader for EPUB, MOBI and comics, built for Supernote e-ink tablets.</p>

---

Reader is built for e-ink and for low power use. It draws a page once, then does nothing until you ask for the next one. Nothing animates, nothing polls. Sitting on an open page, the app uses **no measurable CPU**.

<p align="center">
  <img src="docs/screenshots/02-reading.png" width="440" alt="A page of Project Hail Mary: justified text with real hyphenation, generous margins, a first-line indent on each paragraph, and a running foot naming the chapter and the page">
</p>

## Features

* **A library with covers**, scanned from a folder on the device; each book reopens where you left off, with progress shown as a badge on the cover
* **Justified text with real hyphenation**, adjustable margins, centered chapter openings, three bundled typefaces
* **Two columns in landscape**
* **Pen highlighting** with palm rejection
* **Bookmarks, a contents page, and search** by title or author
* **A chapter scrubber** with a tick per chapter, a preview of the destination page as you drag, and a **Return** control to jump back
* **Comics**: CBZ and zip-backed CBR, one page per screen, per-book reading direction for manga, in the same library as your books
* **A full refresh on every page turn**, or a faster mode with periodic clean-up flashes

Reader opens **EPUB**, **MOBI**, **CBZ**, and **CBR** files that are really zips (many are). Genuine RAR archives do not open. No PDF.

MOBI support covers un-DRMed mobi7 books, which is most sideloaded `.mobi` files; they read exactly as EPUBs do. Two kinds are refused with a message on the shelf: anything with DRM, and **AZW3/KF8**, which is what Amazon ships today.

## Getting it on your Supernote

**1. Download the APK.** Take the latest `sn-reader-*.apk` from the
[Releases page](https://github.com/rephlex00/sn-reader/releases).

**2. Install it over USB.**

```
adb install -r sn-reader-2026.07.6.apk
```

Turn on Debug mode first, in the Supernote's own Settings under security and privacy (the exact wording moves between firmware versions), and check `adb devices` lists your tablet. Updating later keeps everything: your library, positions, bookmarks, and highlights live in the app's own storage, not the APK.

**3. Let it read your files.** Reader asks on first launch. If you miss the prompt:

> Settings > Apps > Special access > All files access > Reader

This is required. Your books sit in shared storage and Android will not hand them over without it.

**4. Add books.** Drop `.epub`, `.mobi`, `.cbz` or `.cbr` files into the **Document** folder, where Supernote keeps its own. Reader finds them next time you open it, and you can point it at another folder in its settings. Scanning is incremental, so a big library does not mean a slow start.

## Reading

### The library

<p align="center">
  <img src="docs/screenshots/01-library.png" width="580" alt="The shelf: a half-title reading BOOKS with the book count, search and settings marks at the right, and three rows of four covers, each carrying its progress as a small badge inside the artwork">
</p>

Tap a book to open it where you stopped. Search filters by title or author as you type, across the whole library regardless of folder. A row of controls covers the rest: covers or a list, all books or just the new / part-read / finished ones, folders or flat, and the sort order.

### Turning pages

There is no swiping. The screen is three tap zones:

| Where you tap | What happens |
| --- | --- |
| The left edge, roughly a quarter of the width | Back a page |
| The right side, roughly the last 40% | Forward a page |
| The strip between them | Show or hide the chrome |

### Landscape

<p align="center">
  <img src="docs/screenshots/07-landscape.png" width="580" alt="Landscape: the chapter as two side-by-side columns with a gutter, the chrome folded to a single row with the chapter beside the controls, and the foot reading page 19 of 44">
</p>

Rotate the tablet and the page becomes two columns. A tap turns both pages at once, and the foot reads `pages 3–4 of 12`. A spread never crosses a chapter boundary, so a chapter with an odd number of pages ends with a blank right-hand column.

Rotating keeps your place: the page numbering changes because the text reflows, but the words you were reading stay on screen. **Lock rotation**, under **Aa › Screen**, pins the reader to its current orientation. The library and settings screens stay upright either way.

### The toolbar

<p align="center">
  <img src="docs/screenshots/03-toolbar.png" width="580" alt="The reading chrome: the way out, the book's title, then a mark ribbon, a drawn contents mark and Aa on the first row; the chapter centred as a running head on the second; below the page, a readout and the chapter timeline. The page between the two bars sits a shade darker than they do">
</p>

The toolbar is two rows: a **‹** back to the library, the book's title, a **bookmark ribbon**, **Contents**, and **Aa** on the first; the current chapter on the second. While the toolbar is up, the page dims by one grey level.

The ribbon is outlined when the current page is unbookmarked, filled when it is. Tap it to bookmark the page in place.

**Contents** holds three lists behind a **Chapters · Marks · Notes** header: the chapter list, your bookmarks, and your highlights. It opens on whichever list you last used, per book. **Aa** holds the display settings. Both close with the **‹** at the top-left; its tap target extends to the edge of the glass.

### Highlighting

Highlights are made with the **pen**; palm rejection keeps a resting hand from leaving marks. Drag the pen across a passage and the highlight follows the nib. Tap an existing highlight for a delete button. The **Highlights** panel lists them all, each with its chapter and position in the book.

### Contents

<p align="center">
  <img src="docs/screenshots/05-contents.png" width="440" alt="The contents page, set like a printed one: a CHAPTERS · MARKS · NOTES header, then leader dots running from each chapter across to its percentage">
</p>

**Chapters** lists each chapter with its percentage, the current one bold and marked in the margin; tap to go there. **Marks** and **Notes** list your bookmarks and highlights, grouped by chapter. A bookmark shows the opening words of the page it saved.

### The chapter scrubber

<p align="center">
  <img src="docs/screenshots/06-scrubbing.png" width="580" alt="Mid-scrub: a floating window previews the destination page under a reversed caption bar reading CHAPTER 19 · 64%, while the timeline's thumb sits under the finger">
</p>

The timeline along the bottom of the toolbar has a tick for every chapter. Drag it and a floating window shows the destination page, from thumbnails prepared once in the background, while a readout names the chapter and percentage. The page itself is drawn once, when you let go. The thumb snaps to chapter starts, bookmarks appear on the track as glyphs, and after any jump **Return** appears beside the readout to take you back to where you were.

### Display settings

<p align="center">
  <img src="docs/screenshots/04-settings.png" width="580" alt="Aa: a sheet across the foot of the screen with the book still showing, a shade darker, above it; three tabs reading TEXT, PAGE and SCREEN, and the face, size and spacing each set as a row of cells">
</p>

**Aa** opens a sheet across the bottom of the screen, with three tabs: **Text** (typeface, size, spacing), **Page** (margins, justification, hyphenation, publisher styling), and **Screen** (page turns, the progress bar, rotation, previews). The book stays visible above the sheet, and **Text** controls reflow it live as you change them.

**Publisher styling** decides whether a book keeps its own formatting or is normalized to Reader's defaults. Changing any setting reflows the text and keeps your place.

### Page refresh

By default every page turn does a full e-ink refresh, which leaves the next page clean of ghosting. **Faster page turns** switches to a light, fast refresh with a full clean-up flash every 3, 6, or 10 pages as you prefer.

### Comics

Reader opens **CBZ** archives, and **CBR** files that are really zips (many are). Pages show one at a time, fit to the screen, turned with the same taps as a book. Reading direction follows the archive's `ComicInfo.xml`, and you can flip it per book for manga.

Comics sit in the same library, with the first page as the cover and the same progress badge; your place is kept per comic, and bookmarks work by page. The toolbar is the same, with two differences: a comic has no chapters, so the timeline has no ticks, and scrub previews are decoded as you drag instead of prepared ahead of time.

File type is detected by content, not extension, so a zip named `.cbr` opens without complaint. A genuine RAR archive does not open; Reader shows a message rather than failing silently.

Two limits: comics are **portrait only** (no two-page spread in landscape), and pages are **fit to the screen with no zoom**.

### Settings

<p align="center">
  <img src="docs/screenshots/09-colophon.png" width="580" alt="Settings as a colophon: the book folder with a Change folder cell, a folders-or-flat choice, and the version set with leader dots above the no accounts, no sync, no network statement">
</p>

Settings holds app-level options: which folder your books live in, and the version you are running. All display options live in **Aa** inside the reader.

## Worth knowing

* Tested on a Supernote Nomad. It should work on a Manta, but that has not been confirmed.
* Sideloaded, not from any app store.
* Everything stays on your device. No accounts, no sync, and the app never touches the network.
* What changed between releases is in [`CHANGELOG.md`](CHANGELOG.md).

## License

[Apache 2.0](LICENSE). Use it, change it, redistribute it, including commercially. If you plan to package or redistribute, read [`NOTICE`](NOTICE) first for the third-party details.

---

## Building it yourself

Kotlin, Gradle, and plain Android Views (no Compose, no WebView). Four modules: `:engine` is pure Kotlin pagination logic with no Android dependency, testable on an ordinary JVM; `:formats` parses EPUB and MOBI and measures text; `:data` is the Room-backed book index; `:app` is the interface.

You need JDK 21 and the Android SDK, including one package that is easy to miss:

```
sdkmanager "platform-tools" "platforms;android-36" "platforms;android-37.1" "build-tools;36.0.0"
```

`platforms;android-37.1` is not a typo. A dependency forces `compileSdk = 37`, and without that package the build fails with an error naming an AAR file rather than the missing platform.

Point Gradle at your SDK with a `local.properties` in the repo root:

```
sdk.dir=/path/to/android-sdk
```

Then:

```
./gradlew test
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Two things to leave alone: the Android Gradle Plugin brings its own Kotlin compiler, so do not apply the Kotlin Android plugin separately, and do not move Kotlin past 2.2.10. A mismatch there crashes compiler-plugin startup rather than failing with anything you can read.
