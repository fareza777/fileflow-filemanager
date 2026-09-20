# FileFlow — Audit Penuh #2

Diperiksa langsung dari kode, bukan dari ingatan. Setiap temuan punya bukti
`file:baris`. Empat penelusuran paralel: integritas data, engine, UI/UX, dan
kesiapan Play Store. Total **49 temuan**, 9 di antaranya kelas kehilangan data.

---

## P0 — Kehilangan data (terverifikasi baris per baris)

### 1. Hapus permanen dari menu long-press tidak minta konfirmasi
`BrowserScreen.kt:595-596`
```kotlin
onDelete = {
    viewModel.deletePaths(listOf(item.path), permanent = !settings.useTrash)
```
Bandingkan jalur toolbar di `BrowserScreen.kt:191-192` yang benar:
```kotlin
onDelete = {
    if (settings.confirmDelete) confirmDelete = true
    else viewModel.deleteSelection(permanent = !settings.useTrash)
```
Menu long-press **tidak memeriksa `settings.confirmDelete`**. Kalau `useTrash`
dimatikan, satu ketukan "Hapus" di menu langsung menghapus file secara
permanen. Hal yang sama di `SearchScreen.kt:361-362`.
**Akibat:** saklar "Tanya dulu sebelum menghapus" dilewati persis di jalur yang
paling mudah tersentuh jari.

### 2. Ekstrak ZIP menimpa file yang sudah ada, tanpa cek konflik, tanpa journal
`ArchiveEngine.kt:143`
```kotlin
FileOutputStream(target).use { output ->
```
`extract()` menulis langsung ke path akhir. `FileOutputStream` memotong file yang
sudah ada di situ. Tidak ada `ConflictPolicy`, tidak lewat `FileOperator`, dan
`UndoManager.reverse` memperlakukan `OperationKind.ARCHIVE` sebagai
`Skip("Archive created")`.
**Akibat:** ekstrak `foto.zip` ke folder yang sudah berisi `IMG_001.jpg`
menghancurkan file lama itu, permanen, dan tombol undo tidak bisa
mengembalikannya.

### 3. Pilihan "Timpa" menghapus file lama tanpa catatan journal
`FileOperator.kt:208`
```kotlin
ConflictPolicy.OVERWRITE -> if (!deleteRecursively(target)) {
```
Hanya transfer *baru* yang dicatat sebagai `AppliedChange` (baris 243). File
tujuan yang dihapus tidak masuk journal mana pun, padahal
`BrowserViewModel` tetap menawarkan "Undo" untuk grup itu.
**Akibat:** aplikasi bilang operasinya bisa dibatalkan. Ditekan undo, file
pengganti hilang — tapi yang ditimpa sudah tidak ada. Separuh perubahan tidak
bisa dikembalikan, sementara UI mengklaim berhasil.

### 4. File yang di-trash oleh ATURAN tidak pernah masuk tabel trash
`RuleExecutor.kt:35-38`
```kotlin
private val operator: FileOperator,
private val ruleDao: RuleDao,
private val logDao: RuleLogDao,
private val journalDao: JournalDao,
```
Tidak ada `TrashDao`. Bandingkan `FileActionsRepository.delete` yang memanggil
`indexTrash`. `RuleExecutor.kt:127` memindahkan file ke folder sampah dan
menulis journal, tapi **tidak menulis baris trash**.
**Akibat:** file yang dihapus oleh aturan otomatis tidak muncul di layar Sampah,
tidak bisa dipulihkan dari sana, dan tidak ikut terhitung di "Kosongkan sampah".
File menumpuk di disk secara tak terlihat.

### 5. Hapus permanen di Sampah selalu melaporkan sukses
`TrashRepository.kt:62,64,69,71`
```kotlin
operator.deleteRecursively(File(entry.trashPath))   // hasil Boolean dibuang
...
trashDao.deleteByIds(ids)                            // baris dihapus tanpa syarat
```
`TrashScreen.kt:128` lalu melaporkan `"${ids.size} items deleted"`.
**Akibat:** kalau file gagal dihapus (terkunci, mount read-only), barisnya tetap
dihapus. User diberi tahu ruang sudah kembali, padahal file masih di disk dan
**tidak akan pernah bisa dilihat atau dihapus lagi dari aplikasi**.

### 6. Undo menandai entri "sudah dibatalkan" meski gagal
`FileActionsRepository.kt:272`
```kotlin
journalDao.markUndone(entry.id, null)
```
Baris ini berada **di luar** blok `when (kind)`, jadi ikut jalan walau cabangnya
menambah `failed++` atau `skipped++`, dan selalu menulis `undoError = null`.
Bandingkan implementasi kembarnya di `UndoManager.kt:70-81` yang meneruskan
alasan sebenarnya.
**Akibat:** kegagalan sesaat ("file sedang dipakai") tercatat sebagai berhasil
dibatalkan. Entri tidak bisa dicoba ulang, dan daftar riwayat menampilkan batch
itu sebagai sudah beres.

### 7. Undo bertingkat memulihkan file ke tempat yang salah
`FileActionsRepository.kt:234-249` (diperparah temuan 6)
Skenario: pindah `A → dest/A` (grup 1), lalu `dest/A → dest2/A` (grup 2). Undo
grup 1 gagal karena `dest/A` sudah tidak ada — tapi tetap ditandai berhasil
(temuan 6). Undo grup 2 memindahkan `dest2/A → dest/A` dan melaporkan sukses.
**Akibat:** setelah "membatalkan semuanya", file ada di `dest/A` — lokasi
perantara — bukan di `A`. User diberi tahu kedua operasi sudah dibalik.

### 8. Journal dan baris trash ditulis tanpa transaksi
`FileActionsRepository.kt:132-135`
```kotlin
onApplied = { change ->
    record(group, change)      // journalDao.insert
    indexTrash(change)         // trashDao.insert
},
```
Perpindahan file di filesystem sudah terjadi sebelum callback ini jalan, lalu
dua penulisan database terpisah tanpa `@Transaction`.
**Akibat:** proses mati di antara keduanya → file ada di folder sampah tapi
journal atau baris trash-nya hilang. File terdampar, tidak bisa di-undo, dan
tidak muncul di layar Sampah.

---

## P1 — Correctness

### 9. Izin runtime tidak pernah diminta — nol pemanggilan di seluruh aplikasi
Hasil grep untuk `requestPermissions|registerForActivityResult|RequestPermission`
di seluruh `app/src`: **tidak ada satu pun**.
- `POST_NOTIFICATIONS` dideklarasikan (`AndroidManifest.xml:17`) dan diperiksa
  (`RuleNotifier.kt:89-94`). Di Android 13+ izin ini **ditolak secara default**,
  jadi `canNotify()` selalu `false` dan notifikasi "aturan sudah jalan" tidak
  pernah muncul — padahal itu yang dijanjikan saklarnya.
- `READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE` dengan `maxSdkVersion`
  (`AndroidManifest.xml:11-15`) diwajibkan di bawah API 30
  (`StorageAccess.kt:29-33`), tapi satu-satunya jalur yang disediakan
  `PermissionGate.kt:74-78` adalah melempar user ke Setelan aplikasi.

**Akibat:** di Android 8, 9, dan 10 (semua di dalam `minSdk 26`) dialog izin
sistem tidak pernah muncul, dan aplikasi mentok di gerbang izin. Ini
bertentangan dengan teks deklarasi Play di `docs/play-listing.md:175-177`.

### 10. Aturan `recursive = false` tetap mencocokkan file di subfolder
`RuleEngine.kt:46`
```kotlin
val recursive = rules.any { it.spec.recursive }
```
Satu aturan rekursif membuat seluruh walk jadi dalam, dan **tidak ada** yang
memeriksa ulang flag `recursive` per aturan (baris 58-70).
**Akibat:** aturan yang disetel "hanya folder ini, jangan subfolder" tetap
memindahkan file dari dalam subfolder.

### 11. Batas umur tumpang tindih di titik yang sama
`RuleMatcher.kt:127-131`
```kotlin
condition.olderThanDays?.let { days -> if (ageMillis < days * DAY_MILLIS) return false }
condition.newerThanDays?.let { days -> if (ageMillis > days * DAY_MILLIS) return false }
```
File yang umurnya **tepat** N hari memenuhi keduanya.
**Akibat:** aturan "lebih dari 30 hari" dan "kurang dari 30 hari" tidak sepakat
di detik yang sama, dan aturan rentang menghitung ganda di batasnya.

### 12. Aturan tanpa root mencocokkan seluruh gabungan root aturan lain
`RuleEngine.kt:76-79`
```kotlin
if (rule.spec.roots.isEmpty()) return true
```
`walkRoots` (baris 52) adalah gabungan semua root aturan. Aturan yang tidak
menentukan root mengembalikan `true` untuk **semua** kandidat — termasuk file
yang hanya ada di bawah root eksplisit aturan lain.

### 13. groupId bisa tabrakan dalam milidetik yang sama
`FileActionsRepository.kt:399`
```kotlin
private fun newGroupId(): String = "op-${System.currentTimeMillis()}"
```
**Akibat:** dua operasi yang dimulai di milidetik yang sama bergabung jadi satu
batch undo. Sekali tekan "Undo" membatalkan dua aksi sekaligus.

### 14. `_active` di-null-kan oleh operasi yang sudah digantikan
`OperationRunner.kt:58`
```kotlin
_active.value = null
```
`current` adalah `var Job?` biasa, dan blok `finally` operasi lama yang
dibatalkan tetap menulis `null` ke `_active` — bisa setelah operasi penggantinya
mempublikasikan progresnya sendiri.
**Akibat:** mulai operasi baru saat operasi lama masih berakhir → indikator
progres yang sedang berjalan hilang, user tidak tahu aplikasi sedang bekerja.

### 15. `freeSpaceAt` mengubah kegagalan stat menjadi "ruang tak terbatas"
`StorageAccess.kt:142`
```kotlin
runCatching { StatFs(path).availableBytes }.getOrDefault(Long.MAX_VALUE)
```
Nilai ini dipakai penjaga di `FileOperator.kt:152` (`if (free < totalBytes)`).
**Akibat:** setiap `StatFs` yang melempar mematikan pengecekan ruang untuk
operasi itu. Salin ke volume yang hampir penuh jalan tanpa cek, disk penuh,
gagal di tengah, dan errornya muncul sebagai pesan generik tanpa petunjuk
"penyimpanan penuh".

### 16. Aturan yang gagal di-deserialize jadi tidak berfungsi tanpa pesan
`RuleJson.kt:23-24`
```kotlin
return runCatching { json.decodeFromString(RuleSpec.serializer(), raw) }
    .getOrDefault(RuleSpec())
```
`RuleSpec()` kosong → `isRunnable == false` dan jadwal `OFF`.
**Akibat:** setelah perubahan kosakata aturan atau satu baris korup, aturan diam-
diam berhenti bekerja. Tetap tampil di daftar, tidak pernah jalan, tanpa satu
pun pesan error.

---

## P2 — Engine & performa

### 17. Beranda menjelajah seluruh storage dua kali berturut-turut
`HomeViewModel.kt:140` dan `:151`
```kotlin
insightsScanner.scan(roots = insightsScanner.hotRoots(), ...)   // walk 1
...
insightsScanner.scan(largeThreshold = ..., ...)                  // walk 2, semua root
```
Sapuan kedua membaca ulang setiap file di `hotRoots()` yang baru saja dibaca
sapuan pertama. Setiap kali Beranda dibuka, biayanya ~1,5× satu sapuan penuh,
menggandakan seluruh syscall metadata.

### 18. Build index berjalan bersamaan dengan sapuan Beranda
`FileFlowApp.kt:44,54` — `delay(3_000)` lalu `indexRepository.rebuild()`, sapuan
penuh perangkat. Sementara `HomeViewModel` menembakkan dua sapuannya 0,9 detik
setelah aplikasi dibuka. **Dua sapuan penuh bersamaan** di storage controller
yang sama saat pertama kali dijalankan.

### 19. JunkFinder menjelajah seluruh pohon dua kali
`JunkFinder.kt:67`
```kotlin
walker.walkDirectories(roots).collect { directory ->
```
Sapuan file di baris 50 sudah melewati setiap direktori. Ini mengulang seluruh
traversal hanya untuk menemukan folder kosong — menambah 30-50% waktu di
perangkat penuh.

### 20. Regex dikompilasi ulang per file
`RuleMatcher.kt:62-63`
```kotlin
NameMode.WILDCARD -> wildcardToRegex(target).containsMatchIn(name)
NameMode.REGEX -> runCatching { Pattern.compile(target, Pattern.CASE_INSENSITIVE).matcher(name).find() }
```
Dipanggil di dalam walk, per kandidat, per aturan (`RuleEngine.kt:63`).
Untuk 100.000 file dengan satu aturan wildcard: 100.000 kompilasi regex.

### 21. `SimpleDateFormat` dibuat baru per file
`RuleEngine.kt:201` dan `NameTemplate.kt:38`
```kotlin
SimpleDateFormat("yyyy-MM", Locale.US).format(Date(candidate.lastModified))
```
Konstruksi `SimpleDateFormat` itu mahal (mengkloning `DateFormatSymbols` dan
`Calendar`). Pola `{year}-{month}-{day}` membuat **tiga** formatter per file.

### 22. Index pencarian di-scan penuh — indeksnya tidak terpakai
`Daos.kt:272-273`
```sql
WHERE (:pattern = '' OR name LIKE :pattern ESCAPE '\')
```
Pola dibangun di `FileIndexRepository.kt:113` sebagai `"%${escapeLike(it)}%"`.
Awalan `%` membuat `Index("name")` tidak terpakai, dan pembungkusan
`(:pattern = '' OR …)` mencegah optimizer memakai indeks apa pun bahkan saat
polanya kosong.
**Akibat:** setiap pencarian memindai seluruh tabel — satu baris per file di
perangkat. Ini justru membatalkan sebagian alasan index itu dibuat.

### 23. Tidak ada `ensureActive()` di loop hashing duplikat
`DuplicateFinder.kt:62,76` — `forEachBounded` menunggu semua `async` tanpa
memeriksa pembatalan di dalam loop. Menutup layar Duplikat meninggalkan
pembacaan seluruh file kandidat berjalan sampai selesai — bisa menit-menitan.

### 24. Journal menulis satu transaksi per file
`RuleExecutor.kt:218` — `journalDao.insert(...)` per file yang diproses. Run
5.000 file = 5.000 transaksi Room terpisah (masing-masing WAL write + fsync).
DAO-nya sudah punya `insertAll` yang tidak dipakai.

### 25. Rencana aturan di-materialisasi penuh sebelum satu file pun disentuh
`RuleWorker.kt:72`
```kotlin
val matches = engine.planAll(rules, roots, now).toList()
```
`planAll` streaming, tapi `.toList()` menahan **semua** kecocokan semua aturan
di memori: hingga `maxFilesPerRun` × jumlah aturan jatuh tempo.

### 26. Akumulasi memori tanpa batas di empat scanner
`DuplicateFinder.kt:45`, `JunkFinder.kt:47`, `InsightsScanner.kt:76`,
`SmartRenameEngine.kt:71` — masing-masing menampung satu `Candidate` (3 String)
per file di perangkat. 100.000 file ≈ puluhan MB, di perangkat yang memorinya
terbatas.

---

## P3 — UI, UX, visual

### 27. Layar Sampah selalu melaporkan sukses
`TrashScreen.kt:128` `"${ids.size} items deleted"`, `:136` `"Trash emptied"` —
`deleteForever` mengembalikan `Unit`, jadi tidak ada yang bisa dilaporkan selain
sukses. (Akar masalahnya temuan 5.)

### 28. Riwayat berkedip "belum ada riwayat" setiap dibuka
`HistoryScreen.kt:146` — `HistoryState` tidak punya field `loading`, frame
pertama selalu `logs == emptyList()`, jadi `EmptyState` muncul sekilas lalu
diganti daftar.

### 29. Starred & Perubahan terakhir menampilkan badan kosong saat memuat
`StarredScreen.kt:166`, `RecentChangesScreen.kt:148` — saat `loading == true`
dan item kosong, alurnya jatuh ke `else` dan `LazyColumn` dirender tanpa anak:
layar benar-benar kosong, tanpa spinner.

### 30. Analyzer tidak punya empty state sama sekali
`AnalyzerScreen.kt:92` — semua isi ada di dalam `state.result?.let`. Sebelum
pemindaian pertama, layarnya hanya app bar di atas badan kosong, tanpa ajakan
menekan refresh.

### 31. Pesan tidak pernah menyebut jumlah yang gagal
`BrowserViewModel.kt:297` `"${report.succeeded} moved to trash"` — dan pola sama
di `CalendarScreen.kt:268`, `StarredScreen.kt:105`, Duplicates, LargeFiles.
Kegagalan sebagian tampil seperti sukses penuh. (VM SmartRename sudah benar.)

### 32. String hardcoded yang tidak bisa diterjemahkan
`FolderPicker.kt:181` `error = "Invalid name"`, `:194` `"Could not create the folder"`,
`RuleEditorScreen.kt:580` `1 -> "Mo"; 2 -> "Tu"; …`,
`AboutScreen.kt:93-97` `"Build"`, `"Package"`, `"Test units"`,
`RulesScreen.kt:132,149,161,187,215` `"Rule deleted"`, `"${n} files organised"`,
`"That rule already exists"`, `"in under a minute"`, `"any size"`.
**Akibat:** `values-in/strings.xml` ada tapi tidak bisa menjangkau teks ini.

### 33. Tombol ikon tanpa nama untuk pembaca layar
`BrowserScreen.kt:265` (ganti mode tampilan), `CalendarScreen.kt:393` (hapus
pilihan), `:607`/`:623` (bulan sebelumnya/berikutnya), `ImageViewerScreen.kt:164`
(ikon Info untuk "buka dengan" — ikon sekaligus salah), `TextFileScreen.kt:207`
(bagikan), `TrashScreen.kt:181` (**hapus permanen tanpa nama**),
`RulesScreen.kt:258` (riwayat), `RulePreviewScreen.kt:194`,
`LargeFilesScreen.kt:254`.
**Khusus:** `LargeFilesScreen.kt:254-255` mengumumkan "Select all" padahal
aksinya **menghapus** pilihan. Salah label lebih buruk daripada tanpa label.

### 34. Target sentuh di bawah 48dp
`BrowserScreen.kt:715-723` — rel A–Z hanya ~14-16dp tingginya, padahal itu
satu-satunya cara cepat menelusuri folder besar. Juga `JunkScreen.kt:307`
(~28dp), `HomeScreen.kt:246`, `StarredScreen.kt:193`.

### 35. Input pengguna hilang saat rotasi
`BrowserScreen.kt:953` (`NameDialog`, dipakai "Folder baru" dan "Ganti nama"),
`FolderPicker.kt:71-74` (navigasi beberapa level lalu rotasi → kembali ke
`startPath`), `TextFileScreen.kt:145`. Semuanya `remember`, bukan
`rememberSaveable`.

### 36. Komponen duplikat
`labelForCategory` identik di tiga layar (`BrowserScreen.kt:988`,
`SearchScreen.kt:396`, `AnalyzerScreen.kt:247`); `SwitchRow` di
`SettingsScreen.kt:474` dan `RuleEditorScreen.kt:458`; `labelForConvention` di
`SmartRenameScreen.kt:449` dan `SettingsScreen.kt:527`; `toFileItem` di tiga
tempat.

### 37. Baris "file terbaru" di Beranda tidak lazy
`HomeScreen.kt:259` `state.recent.forEach { item ->` — seluruh daftar disusun
dalam satu frame di dalam satu `item {}` tanpa key. Juga `HomeScreen.kt:222`
dan `AnalyzerScreen.kt:150`.

### 38. Banner iklan dipaksa 52dp
`AdBanner.kt:99` `.height(52.dp)` — tinggi banner AdMob 50dp di ponsel tapi
**90dp di tablet/layar besar**, jadi SDK dipotong.

### 39. Viewer gambar hardcode hitam/putih
`ImageViewerScreen.kt:130,134-137,157,177` — `Color.Black`, `Color.White`
literal. Pilihan sengaja untuk imersi, tapi bar atasnya jadi tidak ikut tema sama
sekali.

---

## P4 — Kesiapan Play Store

### 40. Build rilis memakai ID iklan TEST
`local.properties:2-3`
```
admob.appId=ca-app-pub-3940256099942544~3347511713
admob.bannerUnitId=ca-app-pub-3940256099942544/6300978111
```
Terkonfirmasi di manifest hasil merge rilis. `AboutScreen.kt:97` akan
menampilkan "Test units".
**Akibat:** aplikasi terbit menghasilkan nol pendapatan, dan ini pelanggaran
kebijakan AdMob (unit test hanya untuk pengembangan). Checklist di
`docs/play-listing.md:239-240` belum dikerjakan.

### 41. Password keystore tersimpan sebagai teks biasa
`key.properties:1-4` — `storePassword` dan `keyPassword` dalam bentuk polos, dan
`fileflow-upload.jks` ada di root proyek. `.gitignore` sudah menutupinya dari
Git, tapi keduanya ada di working tree.
**Akibat:** siapa pun yang memegang folder proyek bisa membuat build yang
ditandatangani dengan upload key. Rotasi sekarang murah; setelah terbit jadi
tiket dukungan Play.

### 42. Build rilis diam-diam jatuh ke debug signing key
`app/build.gradle.kts:81-85`
```kotlin
signingConfig = if (keyProps.isNotEmpty()) signingConfigs.getByName("upload")
                else signingConfigs.getByName("debug")
```
**Akibat:** build dari checkout bersih menghasilkan AAB yang ditandatangani
`androiddebugkey`. Kalau Play App Signing tidak aktif, kunci debug jadi kunci
aplikasi dan semua unggahan berikutnya ditolak karena tanda tangan tidak cocok.
Seharusnya `error(...)`, bukan diam-diam mengganti.

### 43. UMP gagal → iklan tetap dijalankan
`AdBanner.kt:44-49`
```kotlin
}.getOrNull() ?: run {
    initialized = true
    adsAllowed.value = true
    onReady()
    return
}
```
KDoc di atasnya (baris 19-24) mengklaim "kegagalan berarti tanpa iklan", tapi
cabangnya justru sebaliknya. **Akibat:** di EEA/UK, iklan personal disajikan
tanpa consent tercatat — pelanggaran kebijakan UMP/GDPR.

### 44. Tautan kebijakan privasi masih placeholder
`AppLinks.kt:17` `"https://example.com/fileflow/privacy"` — bisa dijangkau dari
Setelan (bagus), tapi halamannya tidak ada. Play menolak URL `example.com`.

### 45. Form Data Safety bilang "tidak ada data" padahal AdMob ada
`docs/privacy-policy.md:33` ("No analytics, crash reporting, or telemetry of any
kind") dan `docs/play-listing.md:184-199` ("No data collected"), sementara
`play-services-ads` menarik `play-services-measurement-sdk-api` dan aplikasi
mendeklarasikan `AD_ID` sendiri (`AndroidManifest.xml:21`).
**Akibat:** mis-deklarasi yang rutin ditolak Play. Kebijakan privasinya juga
bertentangan dengan bagian Advertising-nya sendiri (`privacy-policy.md:48-63`).

### 46. Email dukungan placeholder
`privacy-policy.md:88` `fajar@example.com`, `play-listing.md:134`
`*(your support address)*`.

### 47. Izin yang tidak dipakai aplikasi
`WRITE_EXTERNAL_STORAGE` (`AndroidManifest.xml:14`) — nol referensi di kode.
`WAKE_LOCK` (`:18`) — nol penggunaan `PowerManager`/`WakeLock`.

### 48. FileProvider mengekspos root filesystem
`res/xml/file_paths.xml:7` `<root-path name="root" path="/" />` dengan
`grantUriPermissions="true"`. Hari ini hanya share per-URI, jadi belum bisa
dieksploitasi — tapi ini jebakan untuk perubahan di masa depan.

### 49. `MediaStore.Files.FileColumns.DATA` diakses dengan `getColumnIndexOrThrow`
`BrowseRepository.kt:132,147` dan `TimelineRepository.kt:67,86`. Kolom `_data`
usang sejak API 29. Provider yang tidak menyertakannya akan melempar
`IllegalArgumentException` dan **meng-crash** daftar file terbaru / Kalender.
`getColumnIndex` + fallback null menghilangkan kelas crash ini.

### Catatan tambahan: ~63 string mati
Terbesar: klaster Search (8), Common/format (14), Browser (5), Home (5),
Rules (5), Trash (5), Rename (5). Dua di antaranya —
`browser_permission_lost` dan `rename_new` — menandakan UX yang memang belum
ada. `shrinkResources = true` membuangnya dari APK, jadi biayanya hanya
pemeliharaan.

---

## Yang saya periksa dan TERNYATA TIDAK bermasalah

Supaya tidak ada alarm palsu yang membuang waktumu:

- **Klaim "migrasi Room gagal karena `DEFAULT 0` tidak cocok dengan entity"** —
  saya bantah. Sudah saya uji upgrade nyata v1→v2 di emulator: `PRAGMA
  user_version` = 2, tabel `indexed_files` dan `starred_files` terbentuk, index
  terisi 84 baris, tanpa error. Room tidak menolaknya. Tetap layak menjaga DDL
  dan entity tetap sinkron, tapi ini bukan bug.
- **Key `LazyColumn`** sudah benar di semua daftar (hanya Beranda yang belum
  benar-benar lazy, temuan 37).
- **ProGuard/R8** lengkap untuk Room, Hilt, WorkManager, kotlinx.serialization,
  GMS Ads. Tidak ada keep rule yang hilang.
- **Penempatan iklan** sudah aman: banner hanya di Beranda, Alat, dan Setelan —
  tidak ada di browser, pencarian, penampil, atau saat operasi berjalan.
- **Pemeriksaan versi API** lengkap: `isExternalStorageManager` (30),
  `getDirectory` (30), dynamic color (31), `NotificationChannel` (26),
  `POST_NOTIFICATIONS` (33) semuanya dijaga `Build.VERSION.SDK_INT`.
- **`CollisionResolver`**, **`ListingCache`**, dan pengaman `visited` di
  `FileWalker` sudah benar.

---

## Urutan pengerjaan yang saya sarankan

**Gelombang A — kehilangan data (temuan 1-8).** Tidak ada alasan menunda;
nomor 1, 2, dan 4 bisa menghancurkan file pengguna tanpa jalan kembali.
Perkiraan: satu sesi kerja.

**Gelombang B — yang membuat fitur berbohong (9-16).** Nomor 9 (izin runtime)
membuat aplikasi tidak berfungsi sama sekali di Android 8-10 dan mematikan
notifikasi di Android 13+. Nomor 10 dan 12 mengubah *file mana yang
dipindahkan*, jadi ini soal kebenaran, bukan kenyamanan.

**Gelombang C — Play Store (40-46).** Tidak bisa dilewati kalau memang mau
terbit. ID iklan asli, rotasi keystore, URL kebijakan privasi, dan perbaikan
form Data Safety adalah empat hal yang memblokir review.

**Gelombang D — engine (17-26).** Nomor 17, 18, 19 menghapus tiga sapuan penuh
yang tumpang tindih; nomor 22 justru mengembalikan manfaat index pencarian yang
sudah dibangun.

**Gelombang E — UI (27-39).** Semuanya nyata tapi tidak ada yang menghancurkan
data. Nomor 33 dan 34 layak didahulukan karena menyangkut aksesibilitas.

Saya belum mengerjakan satu pun dari 49 temuan ini — dokumen ini murni hasil
pemeriksaan. Kalau mau, saya mulai dari Gelombang A.
