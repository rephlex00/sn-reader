# Reader

A calm, fast EPUB reader built specifically for Supernote e-ink devices.

Most Android reading apps are designed for phones, where constant redraw and animation are free.
On e-ink they are not free: they drain the battery and leave ghost images smeared across the page.
Reader is built the other way around. It draws a page once and then does nothing at all until you
turn to the next one. At rest it uses no CPU, because there is no background service, no timer,
and nothing polling for work.

<p align="center">
  <img src="docs/screenshots/02-reading.png" width="420" alt="A page of Frankenstein with a centered chapter title and justified text">
</p>

## What it does

* Reads EPUB books from your device, with covers, in a library that remembers where you left off
* Real book typography: justification with hyphenation, generous margins, and centered
  chapter openings
* Highlight passages with the pen, and keep them in a per-book list
* Bookmark pages, jump by chapter, and search your library by title or author
* Full e-ink refresh on every page turn by default, so pages stay clean, with a faster mode if you
  prefer speed

Reader handles EPUB files only. It does not open PDF, CBR, or CBZ.

## Installing

### 1. Download the APK

Grab the latest `sn-reader-*.apk` from the
[Releases page](https://github.com/rephlex00/sn-reader/releases).

### 2. Copy it to your Supernote

Connect the device by USB and install it with ADB:

```
adb install -r sn-reader-2026.07.1.apk
```

You need debug mode turned on first. It lives in the Supernote's own Settings, under the security
and privacy section, though the exact wording moves around between firmware versions. Run
`adb devices` and confirm your device is listed before installing.

Installing a newer version over an older one keeps everything: your library, reading positions,
bookmarks, and highlights all live in the app's own storage, not in the APK.

### 3. Allow it to read your files

The first launch will ask for file access. If you miss the prompt, you can grant it by hand:

> Settings > Apps > Special access > All files access > Reader

This step is required. Your books live in shared storage, and Android will not let the app open
them without it.

### 4. Add books

Put `.epub` files in the **Document** folder on your device, the same place Supernote's own
software keeps them. Reader picks up new books the next time you open it, and you can point it at
a different folder from the library settings if you keep books elsewhere.

Scanning is incremental, so books it has already seen are never reopened. A large library does not
mean a slow launch.

## Using Reader

### Your library

<p align="center">
  <img src="docs/screenshots/01-library.png" width="560" alt="The library grid showing book covers with titles, authors, and reading progress">
</p>

The library is the first thing you see. Tap any book to open it exactly where you stopped reading.
Books you have started show their progress underneath.

The toolbar lets you search by title or author, filter by whether you have finished a book, sort
your shelf, and switch between a cover grid and a list. Searching looks across your whole library
at once, ignoring which folder you happen to be browsing.

### Turning pages

There are no swipes, because a sliding animation is exactly the thing that ghosts on e-ink.
Instead the page is divided into three areas you tap:

| Where you tap | What happens |
| --- | --- |
| Along the left edge | Go back a page |
| Anywhere on the right | Go forward a page |
| The middle strip | Show or hide the toolbar |

Most of the screen is a page turn, so reading is one relaxed tap after another.

### The toolbar

<p align="center">
  <img src="docs/screenshots/03-toolbar.png" width="560" alt="The reading toolbar with Back, Marks, Highlights, Contents, and Aa">
</p>

Tapping the middle of the page brings up the toolbar: **Back** to your library, **Marks** for
bookmarks, **Highlights** for passages you have marked, **Contents** to jump to a chapter, and
**Aa** for how the page looks. Every panel closes with the X in its corner, since the Supernote has
no physical back button.

### Jumping around

<p align="center">
  <img src="docs/screenshots/05-contents.png" width="420" alt="The contents panel listing chapters, with the current chapter in bold">
</p>

**Contents** lists the book's chapters, with the one you are reading shown in bold. Tap any entry
to go straight there.

### Highlighting

Highlights are made with the pen. Reader can tell the pen apart from your finger, so you can rest
a hand on the screen without leaving stray marks.

Drag the pen across a passage and the highlight appears as you draw it. Tap a highlight you have
already made and a small delete button appears. Everything you have marked in a book is collected
in the **Highlights** panel.

### Making the page yours

<p align="center">
  <img src="docs/screenshots/04-settings.png" width="560" alt="The Display panel with font, text size, line spacing, margins, and toggles">
</p>

**Aa** opens the display settings. You can choose between three bundled typefaces (Literata,
Bitter, and Atkinson Hyperlegible, each with proper italic and bold), set the text size, line
spacing, and margins, and turn justification and hyphenation on or off.

**Publisher styling** decides whether a book keeps its own formatting or gets tidied into Reader's
consistent look. Changing any of these reflows the text but keeps your place in the book.

### Page turn refresh

By default, every page turn does a full e-ink refresh. That is the brief black flash you may know
from other e-readers, and it leaves the new page completely clean with no trace of the last one.

If you would rather have quicker turns, switch on **Faster page turns**. Pages then update with a
light, fast refresh, and Reader does a full clean-up flash every few pages instead. You can choose
whether that happens every 3, 6, or 10 pages. The trade is a little ghosting between flashes in
exchange for speed.

## Good to know

* Reader has been tested on the Supernote Nomad. It should work on the Manta, but that has not been
  confirmed yet.
* The app is sideloaded, not distributed through an app store.
* Your reading data stays on your device. Reader has no accounts, no sync, and no network access.

## License

Reader is released under the [Apache License 2.0](LICENSE). You are free to use, modify, and
redistribute it, including commercially. If you plan to package or redistribute it, read
[`NOTICE`](NOTICE) first for the details on third party components.

---

## Building from source

The app is Kotlin, built with Gradle, and deliberately uses plain Android Views rather than Compose
or a WebView. Both redraw far more than a static page needs, which works against the whole point of
the project.

It is split into four modules: `:engine` is pure Kotlin with no Android dependency, holding the
pagination logic so it can be tested on a normal JVM; `:formats` parses EPUB files and measures
text; `:data` is the Room-backed book index; and `:app` is the user interface.

You need JDK 21 and the Android SDK, including a package that is easy to miss:

```
sdkmanager "platform-tools" "platforms;android-36" "platforms;android-37.1" "build-tools;36.0.0"
```

`platforms;android-37.1` is not a typo. The build sets `compileSdk = 37` because a library
dependency requires it. Without that package the build fails with an error that names an AAR file
rather than the missing platform, which is a confusing hour if you have not seen it before.

Point Gradle at your SDK by creating `local.properties` in the repo root:

```
sdk.dir=/path/to/android-sdk
```

Then:

```
./gradlew test                   # engine, formats, data, and app
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Two things to leave alone unless you are ready for a bad afternoon: the Android Gradle Plugin ships
its own Kotlin compiler, so do not apply the Kotlin Android plugin separately, and do not move
Kotlin past 2.2.10. A mismatch there crashes compiler plugin startup instead of failing with
anything readable.
