# FileFlow GUI and photo-search upgrade — verification

## Delivered

- Shared tonal cards and search fields across the app; default mineral/teal light and graphite dark palettes. Existing palette preferences are preserved.
- Home and Tools: prominent photo-search entry point. Existing tool destinations remain available.
- Settings: section summaries, animated expansion/collapse, full-row accessible switches, live storage-permission status, and retention changes saved after dragging finishes.
- Photo search: theme shortcuts, separate theme/meaning/text filters, whole-card photo opening, unique result count, scrollable setup/progress/results, model deletion confirmation, and explicit index coverage.
- Known themes open the theme filter, so photographs are not displaced by screenshots that merely contain the query text.
- Theme matching requires subject evidence; community work requires both group and outdoor/work evidence. Compound themes require each named subject. Label matching uses complete comma-delimited labels, not substrings. Text/name evidence and theme evidence have separate result types.
- Known Indonesian prompts use English captions without untranslated prompts diluting the ensemble.
- Text/theme results are published independently of the slower meaning-model search. Cancelled/stale searches cannot replace a newer query.
- Recent files exclude WebP by case-insensitive filename and MIME checks in MediaStore and the Room feed. WebP files remain accessible in ordinary browsing and photo search; none are deleted.

## Final artifact

[Signed release APK](../app/build/outputs/apk/release/app-release.apk) — 23,735,085 bytes. Built after the final source/test changes and installed with `adb install -r` on Redmi 2201117TY, serial `69f664ea`. Final install: `Success`; installed package update time: 2026-10-12 22:56:27. Existing data/model retained.

## Automated verification

Final command, after the device-driven repair:

```bash
./gradlew :app:testReleaseUnitTest :app:assembleRelease :app:lintDebug --console=plain
```

- `BUILD SUCCESSFUL`, captured exit status **0**.
- **272 tests; 0 failures; 0 errors; 0 skipped.**
- Lint completed: **0 errors, 176 warnings**. Warning-free lint is not claimed. No assertions weakened, tests skipped, or new suppressions added.
- Source-derived SQLite query checks passed: filename, compound OCR, complete-label matching and WebP exclusion before LIMIT.
- English/Indonesian resource XML valid, with unique resource names.
- `git diff --check` passed.
- New regression coverage includes conservative theme evidence, compound subjects, substring rejection, search ordering/deduplication, match kinds, empty queries, prompt language and feed-only WebP exclusion.

## Device verification on the final APK

- Home photo-search shortcut opens the photo-search screen.
- `kerja bakti`: known-theme filter opens automatically. Visible top photos show residents clearing a drain and outdoor community work, not unrelated game screenshots. Results are visible while the model is still loading.
- `makanan`: theme filter opens automatically. Visible photos show meals/food; thumbnails inspected visually.
- Meaning filter still works and is explicitly labelled as approximate visual suggestions, not confidence percentages.
- Tapping the thumbnail opens the matching image in the in-app viewer; Back returns to results.
- Model deletion opens a confirmation explaining that original photos are retained; cancellation leaves the model intact. Actual deletion was deliberately not executed.
- Clear query returns to recent photos.
- Settings expansion/collapse, light/dark switching and default/selected palettes verified visually. Original preference restored to light/Ocean.
- Full-row thumbnail switch toggled and restored; accessibility tree confirms checked state.
- Recent Files opens with video, image and document entries; no visible WebP entries. Feed-wide exclusion is additionally covered at policy/SQL level.
- No AndroidRuntime crash captured during final checks.
- Temporary UI-dump files created on the phone were removed. Local QA evidence remains under `.freebuff/qa/` and has not been published.

## Limitations

- Theme labels and semantic suggestions are approximate; this is not a measured precision/recall benchmark over the complete gallery.
- The meaning model remains slow on this device. The fix prevents it blocking useful theme/text results; no inference speedup is claimed.
- 8,490 photos are text/theme indexed and 8,445 have vectors on this phone. More vectors broaden coverage; they do not automatically raise a given photo's similarity score.
- Shared styling benefits other features, but every tool was not individually redesigned or end-to-end tested.
- Large-font/landscape, every Android version, every palette, and all destructive operations were not tested.
- No coverage percentage measured. Emulator startup failed and was not used as proof of UI correctness; verification used the connected physical Redmi instead.
- Changes are uncommitted; pre-existing workspace changes were preserved.
