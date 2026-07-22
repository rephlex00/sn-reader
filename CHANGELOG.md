# Changelog

Notable changes, newest first. Versions are `YYYY.MM.build` — the year and month a release was cut,
plus a counter within that month. Each release's APK is on the
[Releases page](https://github.com/rephlex00/sn-reader/releases).

## Unreleased

### Reading progress moves onto the cover

In the library, a book's reading percentage now sits as a small badge in the corner of its cover,
instead of a line of text under the title. That line is freed up for readable books; only a book
that cannot be opened still uses it, to say why.

### The progress bar marks where the chapter ends

The thin progress bar at the foot of a page now carries a short tick at the point the current
chapter ends, alongside the mark for how far you are through the whole book. Glance at it and you
can tell how much of the chapter is left, not just how far into the book you are.

### A contents page, set like a printed one

Contents now reads like the contents page of a printed book rather than a plain list: the book's
own serif face (Literata), a letterspaced "Contents" heading over a rule, and leader dots running
from each entry across to its place in the book. The current chapter is bold; nested entries are
italic.

That place is shown as a percentage rather than a page number, on purpose — a real page number
would mean paginating the whole book every time you open Contents, which Reader avoids.

### Menus repaint faster

Menus and panels — Contents, Bookmarks, Highlights, the Aa sheet — now open in a fast e-ink refresh
mode, so scrolling and tapping in them repaints quickly. The book page itself still gets the crisp,
full-quality refresh, and it is cleaned up the moment you close a panel.

### A chapter scrubber

A slider now runs along the bottom of the reading chrome, raised clear of the very edge so it is
easier to catch with a thumb, with a tick for every chapter. Drag it to move through the whole
book.

* Dragging near a chapter boundary snaps to it, like a detent on the slider, so landing exactly on
  a chapter start doesn't mean hunting for the pixel. Releasing on a snapped chapter now opens that
  chapter's first page — it used to occasionally land on the tail end of the chapter before it.
* Wherever you've placed a bookmark, a small glyph marks its spot on the track.
* A floating window above your finger shows a preview of the page you'd land on. The first time you
  open a book, Reader renders a strip of sampled page thumbnails once in the background — a few
  seconds, invisible while you keep reading, a few megabytes on disk — and every drag after that
  shows the preview instantly, with no wait. The track itself is visible now, and while that strip
  is being built each chapter's stretch draws dashed until its thumbnails are ready, then turns
  solid — open the scrubber early in a fresh book and you can watch it fill in.
* The page itself still never repaints while you drag — only the preview window and a readout
  update. The full page is drawn once, when you lift your finger, keeping a long scrub to a single
  clean refresh instead of a flurry of them.
* **↩** appears after a jump — a scrub, a Contents tap, a bookmark or highlight jump — to return to
  exactly where you were, and it's a bit bigger now, easier to catch with a thumb.
* Page previews can be turned off from the Aa sheet, for anyone who'd rather skip the background
  generation entirely — the track goes solid and the window stops appearing. A live count while a
  strip is being built shows how many chapters are done, and a book's previews can be deleted to
  reclaim the disk space without touching the toggle.

## 2026.07.2

### Landscape reading, in two columns

Turning the tablet sideways now splits the page into two columns with a gutter between them,
instead of running one column the full width of the screen. A single column across a landscape
panel reaches roughly a hundred characters a line, well past what is comfortable to read, and the
shortened screen leaves few lines on it. Two columns put the measure back where it belongs and give
you a spread, like an open book.

* A tap turns the whole spread — both pages at once.
* The running foot names both pages, `pages 3–4 of 12`.
* A spread never runs across a chapter boundary, so a chapter with an odd number of pages ends with
  a blank right-hand column, as a printed book does.
* Rotating keeps your place: the text you were reading stays on screen, which is not the same as
  staying on the same page number, since the page numbers themselves change when the text reflows.
* Rotating does not reopen the book. It costs one clean refresh, not a reload.
* **Lock rotation** in the **Aa** panel pins the reader to whichever way it is currently facing, for
  reading on your side. The shelf and settings screens stay upright either way.

### Fixed

* **The running foot was drawing at raw pixels**, so the chapter name and page count sat at roughly a
  third of their intended size on a Nomad. They are now legible. The same fault was silently
  shrinking the highlight caret and the switch outlines in the **Aa** panel, and those are fixed too.
* **Scene breaks were invisible.** A break between scenes now prints a centered `* * *` rather than
  blank space, which was indistinguishable from an ordinary paragraph gap — and vanished entirely
  when it happened to fall at the foot of a page. Every publisher's way of writing one (`***`,
  `---`, a row of dashes, and others) is normalised to the same mark.
* Text no longer runs underneath the progress bar at the narrow margin setting. The space the bar and
  running foot need is now reserved out of the page, so how large that text is and how wide your
  margins are no longer fight each other.

## 2026.07.1

The first release.

* Your books, scanned from a folder on the device, with covers, authors, and how far into each you
  are. Search by title or author, filter and sort, covers or list.
* Reading that remembers your place, per book, surviving a reboot or a battery pull.
* Typography that behaves like a book: justified text with real hyphenation, generous margins,
  centered chapter openings, and three bundled typefaces (Literata, Bitter, Atkinson Hyperlegible).
* **Publisher styling** to choose between a book's own formatting and one consistent look.
* Pen highlighting that tells your stylus from your palm, with a panel collecting everything marked.
* Bookmarks, a table of contents, and a progress bar.
* A full clean refresh on every page turn by default, with **Faster page turns** to trade a little
  ghosting for speed.
* No measurable CPU while a page sits on screen. Nothing animates, nothing polls, and the app never
  touches the network.

EPUB only. No PDF, CBR, or CBZ.
