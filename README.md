# 📚 My Books for Android

A free, Libby-style app for **public-domain audiobooks and ebooks**. Search,
tap a book, then listen or read. Everything can be downloaded to read or
listen offline. There are no accounts or ads, and every book comes from a
catalog that gives it away legally:

| | Where it comes from | Why it's free |
|---|---|---|
| 🎧 Audiobooks | [LibriVox](https://librivox.org), read by volunteers and stored on the [Internet Archive](https://archive.org/details/librivoxaudio) | The books are in the public domain, and LibriVox puts its recordings in the public domain too |
| 📖 Ebooks | [Project Gutenberg](https://www.gutenberg.org), searched through [Gutendex](https://gutendex.com) | The books are in the public domain in the USA |

Copyright rules differ between countries. A book that's free in the USA may
still be under copyright where you live, so check your own country's rules
if you're outside the USA.

## Installing it

1. On your Android phone, open the
   [latest build](https://github.com/Akrishna87/Akrishna87/releases/tag/books-latest)
   and tap **MyBooks.apk** to download it.
2. Open the downloaded file. Android will ask you to allow installing apps
   from your browser (or Files app). Allow it, then tap **Install**.
   Google Play Protect may warn that it doesn't recognise the app, because
   it isn't from the Play Store. Tap **More details → Install anyway**.
3. Open **My Books**.

To update, install a newer `MyBooks.apk` the same way. It installs over the
old one and keeps your shelf and your place in every book.

## Features

- **Home** has a **Continue** row for the books you've started, the most
  popular audiobooks and ebooks, and browse tiles by subject (Adventure,
  Mystery, Science fiction, Poetry and more).
- **Search** looks in both catalogs at once as you type. Filter to
  **Audiobooks** or **Ebooks**; scroll down to load more.
- **Book pages** show the cover, author, length, licence and description,
  plus **Play** / **Read** and **Download**. Tap the bookmark to save a book
  to your shelf for later, or the globe to open its LibriVox or Gutenberg page.
- **Audiobook player** with chapters ("parts"), 10 s back and 30 s forward,
  speeds from 0.75× to 2.5×, a **sleep timer** (minutes or end of part),
  lock-screen and notification controls, and headphone/Bluetooth buttons. It
  remembers where you stopped in every book and keeps playing in the
  background.
- **Downloads** run through Android's download manager, so they carry on
  with the app closed and show their progress in the notification shade.
  The download button shows the book's size first. Downloaded audiobooks play
  from the phone; the rest stream.
- **Built-in ebook reader** with pages you turn by tapping the right or left
  edge (or swiping), a tap in the middle for the menu, a table of contents,
  text size, **Light / Sepia / Dark** pages and a serif or sans-serif font.
  It opens where you left off. Ebooks are downloaded when you first read
  them (usually under 1 MB), so they work offline.
- **Your shelf** lists every book you've played, read, downloaded or saved,
  with your progress and how much space downloads use. Remove a download or
  a book from the ⋮ menu.

## How it's built

Kotlin and Jetpack Compose, with Media3 (ExoPlayer) for audio, Coil for
covers and a WebView for the reader.

- `catalog/` searches the two catalogs and reads their answers. The
  Archive's search covers the LibriVox collection only, so nothing that isn't
  in the public domain can turn up. If Gutendex is down, the app falls back
  to Project Gutenberg's own OPDS catalog.
- `epub/` reads EPUB files (contents, chapters, table of contents) and
  styles chapters for the reader. `assets/reader.js` lays each chapter out
  as screen-sized pages.
- `PlaybackService.kt` owns the player and the media session.
- `ci/smoke-test.sh` runs on an Android emulator for every build, against
  the real catalogs. It searches, reads the ebook and turns pages, streams
  the audiobook in the background, changes speed and checks the shelf.
  Screenshots from that run are attached to each release.

Builds are made by the
[Books app APK workflow](../.github/workflows/books-apk.yml) on every push
that touches `books-android/`.
