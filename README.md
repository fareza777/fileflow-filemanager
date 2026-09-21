# FileFlow

A modern Android file manager with a rule engine that keeps storage tidy — built
offline-first, privacy-first, and ready for a Google Play release.

Kotlin · Jetpack Compose · Material 3 · Hilt · Room · WorkManager · AdMob

---

## What it does

**File management.** Browse internal storage and SD cards with breadcrumbs, list
and grid views, sorting, a per-folder type filter and image thumbnails. Tapping
a file opens it; tapping a folder goes into it; long-pressing starts a
multi-select for copy, move, rename, share or delete. One action sheet — open,
open with, show in folder, share, copy, move, rename, star, compress, extract,
select, delete — is shared by every list in the app.

**Viewers.** Photos open in place with swipe-between-siblings and pinch zoom,
and text files open in a monospaced reader that refuses to hang on a binary or
an enormous file. Neither hands the user off to another app and loses their
place in the folder.

**Archives.** Zip a selection, or extract one in place. Extraction resolves every
entry and refuses anything that would land outside the destination, so a
crafted archive cannot write into your app data.

**Search index.** The device's contents are remembered in a Room table, so a
search is a SQL query rather than a full filesystem walk. It is built on first
launch, refreshed by an idle-time worker, and updated inline whenever the app
moves or deletes something.

**Calendar.** Every month laid out as a grid, with each day shaded by how many
files arrived on it. Tap a day to see exactly what landed: photos render as a
thumbnail gallery, documents as a list, and the whole day can be multi-selected,
moved, or deleted.

**Auto Rules.** Conditions (name, extension, file kind, source folder, size, age)
combine with an action (move, copy, trash, delete, rename) and a schedule
(manual, hourly, daily, weekly). Every rule can be previewed before it runs,
enabled or disabled without losing its settings, and undone afterwards. Rule
cards show their conditions as chips, their next scheduled run, and a run button
that is one tap away instead of buried in a menu.

**Safety rail.** Nothing happens without a preview: the preview lists every file
and its destination, and the run writes to an undo journal as it goes. Undoing a
run restores every file. Deletions go to a `.fileflow/trash` folder on the same
volume so they stay instant, and are purged after the retention window.

**Smart rename.** Detects file names that are impossible to search —
`IMG_20230101_120000.jpg`, `a3f9c1b2.pdf`, `report (1) (1).pdf`, wrong
extensions. Recovers dates hidden inside names, strips download-manager noise,
repairs the extension from the file's magic bytes, and proposes a readable name
with the reason shown. Nothing is renamed without approval, and the whole batch
is one undo.

**Cleaners.** Duplicate finder (size → prefix hash → full hash), large-file
scanner, junk cleaner (empty files and folders, temp data, crash logs, stale
installers, thumbnail caches), storage analyzer, and a search that can be turned
into a rule in one tap.

**Storage sources.** Local storage is one of several, behind a single
`RemoteFileSystem` interface: WebDAV and S3 (both written by hand, S3 with its
own SigV4 signing), SFTP and SMB (JSch and jcifs-ng, both pure Java). A remote
folder browses like a local one — download, upload, new folder, rename, delete,
multi-select — and saved passwords are encrypted with a key that never leaves
the device's keystore.

**Dual pane.** Two folders at once, stacked in portrait and side by side in
landscape, with copy and move going from the pane holding the selection to the
other one. The destination is always visible, which is the whole point.

**Directory compare.** Two folders compared by relative path, differences
first: only left, only right, differs, or same. Contents are compared as well
when asked, under a size ceiling. Copying runs in whichever direction the user
points at — and every action here is a copy, never a delete.

**Archives as folders.** A zip, tar or tar.gz opens like a directory: walk into
it, read one entry (a text file opens in the reader, a photo in the viewer,
straight from inside the archive), or extract a single entry beside it. Tars are
indexed once and cached; a plain tar is opened by seeking to the entry's offset.

**Inspection.** A folder can be inspected three ways: the true type behind each
extension (magic-byte signatures, with a file renamed to a lie reported as one),
a fast duplicate pre-scan that samples the head, middle and tail of every file
so a hundred gigabytes of media is screened in seconds, and a fuzzy distance
between documents that finds near-duplicates rather than exact copies. The same
screen shows the folder's Merkle fingerprint, which changes when anything inside
it does.

**Similar photos.** Two perceptual hashes — a difference hash and an average
hash — reduce each photo to 64 bits of "how it looks", and shots of the same
moment group together regardless of name, size or re-compression. The best copy
of each group is identified and never pre-selected; everything else is ticked and
goes to the trash, which makes the decision reversible.

**Privacy.** Metadata is removed from JPEG and PNG *without re-encoding*: the
EXIF, IPTC, XMP and comment segments are cut out of the file's segment structure
and every byte of image data is copied through untouched, so the result is
pixel-identical. The ICC colour profile and the Adobe transform marker stay,
because removing those would change how the photo looks. The screen reports what
was found — including whether the file records where it was taken — before
anything is written.

**Recovery shield.** A file is erasure-coded into `n` data shards plus `m` parity
shards over GF(256), and any `n` of them rebuild it — insurance against the one
failure a copy does not protect against, a card or stick quietly going bad. The
same screen writes and reads back test data to catch a card that lies about its
capacity.

**App backup.** Installed apps are exported as APKs, base *and* every split, so
a backup of a modern app is a set that installs rather than a base APK that
crashes.

**Transfer to PC.** The phone serves its own storage over the local network:
start the server, open the address it shows in a browser on the computer, and
browse, download and upload from there. Every local address is listed — Wi-Fi,
hotspot, and USB tethering — because the reachable one depends on which network
the other device is on. It runs in a foreground service, so leaving the screen
or locking the phone keeps the connection up.

**Compress and convert.** ZIP, TAR and TAR.GZ, with the compression level chosen
rather than assumed; extraction reads each of them back, with the same
path-escape guard as before. Conversion covers what a phone can actually do
without a native library: JPEG, PNG, WEBP and PDF from images, PDF from text, and
the audio track of a video copied out as M4A without re-encoding. Only formats
that apply to the selection are ever offered.

**Storage analyzer.** One walk of the device feeds every number on the screen.
Folders are sized *recursively*, so opening one shows children that actually add
up to the figure you tapped — including the ones whose bulk sits several levels
down, like `Android/media`. From there you can keep drilling: the header carries
a breadcrumb, back steps up one level before it leaves the screen, category
chips filter the tree by kind, and every folder is judged by how much of the
filtered category it holds. A finished analysis is reused for a few minutes, so
re-opening the screen is instant; the refresh button always re-walks.

---

## Project layout

```
app/src/main/java/com/fileflow/app/
├── core/
│   ├── model/         FileItem, FileCategory, VolumeInfo, SortOrder
│   ├── storage/       Volumes, permissions, file operations, trash
│   └── util/          Formatters, path rules, MIME and magic-byte detection
├── data/
│   ├── db/            Room entities, DAOs, database
│   ├── prefs/         DataStore settings
│   └── repo/          Browsing, actions + journal, rules, trash
├── domain/
│   ├── rules/         Specs, matcher, planner, executor, undo, scheduler, worker
│   ├── rename/        Name heuristics, smart rename engine, name templates
│   ├── scan/          Analyzer, duplicates, junk, insights, trash janitor
│   ├── timeline/      Media-index month queries for the calendar
│   └── search/        Streaming search engine
├── di/                Hilt modules
└── ui/                Theme, components, screens, navigation, shared intents
```

### Design decisions worth knowing

**One write path.** Every mutation goes through `FileActionsRepository` →
`FileOperator`. That is what makes a single honest undo journal possible: if a
change happened, a record of it exists — and the repository hands the journal
group id back in the `OperationReport`, so an undo button can name the exact
group instead of guessing one.

**Work belongs to the process, not the screen.** File operations run on
`OperationRunner`'s application scope. Navigating away stops watching the work;
it does not cancel it. Instead of a half-written file and no explanation, a
destination path only ever exists once it is whole — copies are written to
`*.fileflow-part` and renamed into place.

**One shared resolver for names.** `CollisionResolver` is used by the rename
preview, the rename engine and the rules planner, so a preview cannot promise an
outcome the executor will not produce.

**Search reads an index, not the filesystem.** `FileIndexRepository` keeps a row
per file, refreshed in the background and updated inline after every mutation.
A live walk is still there as the fallback for the first launch before the index
exists.

**Recent files merge two sources.** The index knows every file, including the
formats the media scanner never reports; MediaStore is always current for the
media it does cover. Merged by path and sorted by modification time, so "what
changed" is answered by whichever source knows — and the browser-generated noise
(thumbnail caches, `Android/data`, the app's own trash) is filtered out of both.

**Nothing expensive happens while a frame is being drawn.** Directory child
counts are computed in the listing call, thumbnails decode at display size,
search-result conversions are memoised per path, and derived values (selected
items, selected bytes, calendar day counts, junk totals) are computed when the
underlying selection changes rather than read during composition.

**One interaction model.** Tap opens, long-press selects, the overflow button
opens `FileActionSheet`. Home recents, the browser, search, the calendar and the
scanners all behave the same way, so nothing has to be learned twice. Opening
and sharing live in `FileIntents` so no screen can drift.

**Startup protects the first frame.** Background maintenance (rule
rescheduling, trash retention) waits out a short quiet period, and the home
scan runs in two passes — the folders people actually use first, then the full
sweep — so a real number appears in seconds instead of after a full walk.

**The rule engine is data, not code.** A rule is a `RuleSpec` serialised into one
JSON column, so the condition and action vocabulary can grow without a database
migration. Decoding is total — an unreadable rule falls back to a safe default
instead of crashing the list.

**Scanners work on `Candidate`, not `File`.** The matcher, the rename heuristics
and the schedulers are pure functions over a value type, which is why they are
exhaustively unit-tested without a device.

**The calendar reads the media index first.** Grouping a month is instant when it
comes from a table that is already sorted by date; a filesystem walk is offered
separately for the files the index does not know about, because that walk is the
expensive path and should be a deliberate choice.

**Walk once, answer three questions.** The home dashboard's large-file count,
junk estimate and scanned-file total come from a single traversal; walking the
tree is the most expensive thing the app does.

**Previews are read-only by construction.** `RuleEngine.plan` produces
`PlannedOperation` values and cannot touch the filesystem.

---

## Building

Requirements: JDK 17 or newer, Android SDK with platform 36.

```bash
./gradlew assembleDebug        # APK for a device
./gradlew bundleRelease        # AAB for Play
./gradlew testDebugUnitTest    # 109 unit tests
./gradlew lintDebug            # lint, run deliberately — a release pass takes a long time
```

Outputs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/bundle/release/app-release.aab`

### Signing

Release builds are signed with the upload key described in `key.properties`
(gitignored). Without that file the build falls back to the debug key so a clean
clone still produces something installable.

**Back up `fileflow-upload.jks` and `key.properties`.** Losing the upload key is
recoverable if Play App Signing is enabled, but it is an avoidable support
ticket.

### AdMob

Ad unit IDs live in `local.properties` (gitignored):

```properties
admob.appId=ca-app-pub-XXXXXXXXXXXXXXXX~XXXXXXXXXX
admob.bannerUnitId=ca-app-pub-XXXXXXXXXXXXXXXX/XXXXXXXXXX
```

The fallbacks are Google's official **test** IDs, on purpose: a malformed AdMob
application ID crashes the app at launch, and impressions from your own device
are a policy violation. Debug builds always use the test units regardless of what
is configured.

Ads render only on Home, Tools and Settings, and only after Google's consent
flow says a request is allowed.

---

## Permissions

| Permission | Purpose |
| --- | --- |
| `MANAGE_EXTERNAL_STORAGE` | Browsing and organising files outside the app folder — the core function of a file manager. Declared in Play Console as *File management*. |
| `READ`/`WRITE_EXTERNAL_STORAGE` (maxSdk 29) | The same access on Android 9 and older. |
| `POST_NOTIFICATIONS` | Optional summary after a scheduled rule runs. |
| `RECEIVE_BOOT_COMPLETED` | Re-arms scheduled rules after a restart. |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `AD_ID` | Google AdMob only. |

No account, no backend, no analytics. Nothing is uploaded.

---

## Release assets

- `docs/privacy-policy.md` — host this at a public URL for the store listing
- `docs/play-listing.md` — full submission pack (listing copy in English and
  Indonesian, the all-files-access declaration, data safety answers, rating
  questionnaire, release checklist)
- `docs/play/play-icon-512.png` — store icon
- `docs/play/feature-graphic-1024x500.png` — feature graphic
- `tools/make-play-assets.ps1` — regenerates the store graphics
