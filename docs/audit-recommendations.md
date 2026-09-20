# FileFlow — Full Audit & Recommendations

Audit dilakukan dengan membaca ulang seluruh kode, bukan dari ingatan. Setiap
temuan di bawah punya rujukan file:baris yang bisa dicek langsung.

Tanggal: 15 September 2026 · Versi: 1.0.0 (versionCode 1)

---

## Ringkasan penilaian

| Area | Nilai | Catatan |
| --- | --- | --- |
| **Fondasi arsitektur** | Baik | Satu jalur tulis, journal undo yang jujur, engine aturan berbasis data. Ini bagian terkuat. |
| **Keandalan operasi file** | **Perlu perbaikan** | Operasi panjang bisa mati di tengah jalan dan meninggalkan file parsial. Tidak ada notifikasi media scanner. |
| **Kejujuran preview** | **Ada cacat** | Preview aturan tidak menyelesaikan tabrakan nama, jadi bisa menampilkan rencana yang tidak sama dengan hasil eksekusi. Fitur unggulan yang justru tidak akurat. |
| **Performa baca** | Cukup | Pencarian masih walk penuh; belum ada index. |
| **Performa tulis** | Baik | Hash duplikat belum paralel. |
| **Kelengkapan fitur** | Cukup | Belum ada viewer, arsip, vault, tag. |
| **Visual** | Baik | Konsisten Material 3, tapi aksen tidak ikut dynamic color dan belum ada loading state. |
| **Kepatuhan Play/AdMob** | **Ada celah** | Consent tidak bisa dicabut; kebijakan privasi tidak bisa dibuka dari aplikasi. |

---

## A. Temuan terverifikasi

### A1. Operasi panjang mati saat pengguna keluar layar — RISIKO DATA

`BrowserViewModel.kt:368` — `operationJob = viewModelScope.launch { ... }`

ViewModel terikat pada `NavBackStackEntry`. Begitu pengguna menekan back (atau
sistem membuang entry karena memori), scope dibatalkan dan job ikut mati — di
tengah menyalin file 2 GB. Yang tersisa: file parsial di folder tujuan tanpa
satu pun pemberitahuan.

Tidak ada foreground service, jadi Android juga bebas membunuh prosesnya saat
aplikasi di latar belakang.

**Dampak:** kehilangan data / file korup yang tidak disadari pengguna.
**Perbaikan:** pindahkan operasi ke `applicationScope` + foreground service
bertipe `dataSync` dengan progres di notifikasi, atau minimal tulis penanda
`.incomplete` pada file tujuan dan bersihkan saat start berikutnya.

---

### A2. Preview aturan bisa berbohong — CACAT DI FITUR UNGGULAN

`RuleEngine.kt:115` — `conflict = target.exists()`

Tabrakan hanya dicek terhadap **file yang sudah ada di disk**. Tidak ada
pengecekan tabrakan **antar file yang cocok dalam satu run**.

Contoh nyata: dua file `IMG_20230101_120000.jpg` dan `20230101_120000.jpg`
dengan aksi "rename ke `{date} {time}.jpg`" menghasilkan tujuan yang sama.
Preview menampilkan keduanya menuju satu path. Eksekutor (`FileOperator.kt:213`)
diam-diam menamai yang kedua `2023-01-01 12-00-00 (2).jpg`.

Seluruh janji aplikasi ini adalah "lihat dulu, baru jalankan". Preview yang
tidak sama dengan hasil eksekusi merusak janji itu.

**Bukti pendukung:** `SmartRenameEngine.kt` sudah punya `resolveCollisions()`
yang menangani kasus ini dengan benar — logikanya tinggal dipakai ulang.
**Perbaikan:** jalankan resolusi tabrakan yang sama di `RulesRepository.preview`
sebelum menampilkan hasil.

---

### A3. Dialog hapus menyebut angka yang salah

Enam lokasi: `BrowserScreen.kt:569`, `SearchScreen.kt:360`,
`CalendarScreen.kt:560`, `LargeFilesScreen.kt:332`, `DuplicatesScreen.kt:342`,
`JunkScreen.kt:336` — semuanya `stringResource(R.string.delete_body, 30)`.

Padahal retensi sampah bisa diatur 1–90 hari di Setelan
(`SettingsRepository.kt`, `trashRetentionDays`). Pengguna yang menyetel 7 hari
tetap diberi tahu "Sampah dikosongkan setelah 30 hari".

**Perbaikan:** baca `settings.trashRetentionDays` di masing-masing ViewModel dan
teruskan ke dialog.

---

### A4. File hasil operasi tidak muncul di Galeri

Tidak ada satu pun pemanggilan `MediaScannerConnection` di seluruh proyek
(diverifikasi lewat pencarian menyeluruh — nol hasil).

Konsekuensinya: copy atau rename foto lewat FileFlow, lalu buka Galeri — foto itu
belum ada. WhatsApp dan aplikasi musik juga belum melihatnya. Media scanner
Android kebetulan akan menemukannya nanti, bisa jam-an kemudian, dan untuk file
di folder tertentu tidak akan pernah.

**Dampak:** ini keluhan nomor satu pada setiap file manager. Pengguna
menyimpulkan "aplikasinya rusak" padahal filenya ada.
**Perbaikan:** panggil `MediaScannerConnection.scanFile` untuk setiap
`AppliedChange` pada file media (images/videos/audio) setelah operasi selesai —
terpusat di `FileActionsRepository`, satu tempat.

---

### A5. Dialog konflik tidak pernah dibangun

String `conflict_title`, `conflict_body`, `conflict_apply_all` ada di
`strings.xml` tapi **nol pemakaian** di kode.

Semua operasi copy/move di luar aturan hard-coded ke `ConflictPolicy.RENAME`:
`BrowserViewModel.kt:271, 282, 307, 309`, `SearchViewModel.kt:263`,
`CalendarScreen.kt:279`, `LargeFilesScreen.kt:181`.

Artinya: pengguna tidak pernah bisa memilih **timpa** atau **lewati**. Yang lebih
buruk, `ConflictPolicy.ASK` bahkan tidak ada sebagai nilai enum — jadi tidak ada
mekanisme tanya sama sekali, dan `FileOperator.conflicts()` (yang sudah saya
tulis untuk pre-flight) tidak pernah dipanggil.

**Perbaikan:** sebelum operasi dimulai, panggil `FileOperator.conflicts()`; jika
ada, tampilkan dialog dengan pilihan Simpan keduanya / Timpa / Lewati +
"berlaku untuk semua".

---

### A6. Consent AdMob tidak bisa dicabut — CELAH KEPATUHAN

`AdBanner.kt:82` mendefinisikan `AdsConsent.showPrivacyOptions()`, tetapi
**tidak dipanggil dari layar mana pun** (diverifikasi: hanya definisi yang ada,
nol pemanggil).

Google mewajibkan aplikasi yang menampilkan iklan di EEA menyediakan jalan bagi
pengguna untuk mengubah atau mencabut persetujuan. Sekarang tidak ada.
Begitu juga `settings_privacy_policy` — string-nya ada, tapi tidak ada tombol
apa pun yang membukanya, jadi kebijakan privasi tidak bisa diakses dari dalam
aplikasi.

**Perbaikan:** tambahkan dua baris di Setelan → Privasi:
"Ubah pilihan iklan" → `AdsConsent.showPrivacyOptions(activity)` dan
"Kebijakan privasi" → buka URL (Custom Tab).

---

### A7. "Buat aturan dari pencarian ini" baru setengah jalan

`RuleEditorViewModel.kt:59` menyemai hanya **kondisi nama** dari query.
Filter yang sedang aktif di layar pencarian — kategori, ukuran, tanggal —
tidak ikut menjadi kondisi aturan.

Tombolnya menjanjikan "buat aturan dari pencarian ini", tapi yang terjadi hanya
"buat aturan dari kata kunci ini".

**Perbaikan:** `SearchFilters.asConditions()` sudah ada dan sudah menghasilkan
`List<MatchCondition>`. Tinggal dioper lewat nav argument dan digabung dengan
kondisi nama.

---

### A8. Fitur yang direncanakan lalu ditinggalkan (string mati)

Diverifikasi tanpa pemakaian di kode:

| String | Kemungkinan maksud |
| --- | --- |
| `settings_privacy_policy` | Tombol kebijakan privasi di Setelan |
| `browser_folder_info` | Ringkasan folder (n folder, n file, ukuran) |
| `home_last_run` / `home_rules_idle` | Status aturan terakhir di Beranda |
| `rules_matched_hint` | "Terakhir cocok N file" di kartu aturan |
| `search_stopped` | Pesan saat pencarian dihentikan |

Bukan bug, tapi menandakan fitur yang belum selesai. Dua di antaranya layak
diselesaikan: kebijakan privasi (kepatuhan) dan status aturan di Beranda
(pengguna tidak tahu aturannya jalan atau tidak).

---

## B. Engine & arsitektur

### B1. Pencarian tidak punya index — batas kemampuan terbesar

**Sekarang:** tiap pencarian menjelajah seluruh filesystem dari nol
(`FileSearchEngine` → `FileWalker`). Di HP dengan 60.000 file, pengguna menunggu
10–60 detik sambil melihat penghitung naik. Setiap kali.

**Rekomendasi:** index file di Room, diisi WorkManager saat perangkat menganggur
dan diperbarui inkremental dari `ContentObserver` MediaStore. Setelah itu:
- pencarian jadi instan (query SQL, bukan walk)
- bisa memunculkan pencarian lanjutan yang mustahil sekarang: "file terbesar
  bulan lalu", "semua foto dari aplikasi X", koleksi pintar
- `Recent files` tidak lagi bergantung pada MediaStore saja

Ini satu perubahan dengan dampak terbesar yang tersisa. Perkiraan: 1 tabel
Room + 1 worker + ganti `FileSearchEngine` untuk membaca index dengan fallback
ke walk. Menengah, tapi mengubah kelas aplikasi.

### B2. Aturan dijalankan boros — satu walk per aturan

`RuleWorker` memanggil `engine.plan(rule.spec, roots)` untuk satu aturan. Lima
aturan harian = lima kali menjelajah seluruh penyimpanan, masing-masing
membaca metadata ratusan ribu file.

**Rekomendasi:** satu walk, evaluasi semua aturan yang jatuh tempo sekaligus.
Untuk 5 aturan, ini memotong I/O menjadi seperlima — perbedaan antara "HP panas
dan baterai terkuras" dan "tidak terasa".

### B3. Hash duplikat berurutan

`DuplicateFinder` meng-hash satu file pada satu waktu di satu thread. HP modern
punya 6–8 core yang menganggur.

**Rekomendasi:** hash paralel dengan `Dispatchers.Default` + semaphore (batasi
2–3 task bersamaan agar tidak berebut I/O). Perkiraan 3–5× lebih cepat untuk
folder berisi banyak file besar.

### B4. Tidak ada cache listing folder

`BrowseRepository.listItems` membaca disk setiap kali. Masuk folder → keluar →
masuk lagi = dua pembacaan penuh. Tombol back terasa lebih lambat dari
seharusnya.

**Rekomendasi:** LRU cache kecil (mis. 16 folder) dengan invalidasi saat folder
itu sendiri dimodifikasi. Navigasi bolak-balik jadi instan.

### B5. Tidak ada paging untuk folder raksasa

`listItems` mengembalikan `List<FileItem>` penuh. Folder dengan 50.000 file
(umum di folder rekaman atau cache) membangun 50.000 objek sekaligus, plus
`selectedItems` menyalinnya lagi saat ada seleksi.

**Rekomendasi:** Paging 3, atau minimal pemotongan bertahap (muat 500, tambah
saat mendekati bawah). Untuk 99% folder tidak ada bedanya; untuk 1% yang
ekstrem, ini perbedaan antara jalan dan ANR.

### B6. Dua scanner melakukan walk terpisah

`InsightsScanner` (Beranda) dan `StorageAnalyzer` (Analisis) masing-masing
menjelajah penuh, dengan agregasi berbeda. Pengguna yang membuka Beranda lalu
Analisis menjelajah HP-nya dua kali.

**Rekomendasi:** satu `StorageSnapshot` yang menyimpan hasil walk terakhir
(kategori, folder terbesar, file besar, sampah) dengan TTL, dan kedua layar
membacanya.

### B7. Walker kurang hemat

`FileWalker` memakai `File.listFiles()` yang mengalokasikan array `File[]` untuk
setiap direktori — pada direktori dengan puluhan ribu entri itu alokasi besar
yang tidak perlu.

**Rekomendasi:** `android.system.Os.listdir` atau `Files.newDirectoryStream`
(API 26+, sudah di minSdk). Keuntungan terbesar di direktori besar.

---

## C. Fitur yang belum ada

Diurutkan berdasarkan (nilai bagi pengguna ÷ usaha):

| # | Fitur | Kenapa penting | Perkiraan usaha |
| --- | --- | --- | --- |
| 1 | **Media scanner setelah operasi** | Tanpa ini, aplikasi terasa rusak (lihat A4) | Kecil |
| 2 | **Dialog konflik** | Fitur dasar file manager (lihat A5) | Kecil |
| 3 | **Viewer gambar bawaan** | Ketuk foto sekarang keluar ke Galeri dan kehilangan konteks folder. Swipe antar foto dalam satu folder adalah pengalaman yang diharapkan dari file manager modern. | Menengah |
| 4 | **Kompres / ekstrak ZIP** | `java.util.zip` sudah di JDK, tidak perlu dependensi. "Pilih 20 file → Zip" sangat sering dipakai. | Menengah |
| 5 | **File berbintang** | Sekarang hanya folder yang bisa di-bookmark. Bintang pada file + layar "Berbintang" itu murah dan sering dipakai. | Kecil |
| 6 | **Ekspor/impor aturan (JSON)** | Data lokal semua, tapi aturan hilang saat reinstall. Ekspor ke file + impor = perlindungan yang nyata. | Kecil |
| 7 | **Viewer teks/kode** | Melihat .txt/.json/.log tanpa keluar aplikasi. Sangat murah dengan Compose. | Kecil |
| 8 | **Thumbnail video** | `coil-video` sudah tersedia di ekosistem Coil; sekarang video hanya dapat ikon. | Kecil |
| 9 | **Tag / label warna** | Melampaui struktur folder. Butuh tabel + filter. | Menengah |
| 10 | **Vault terenkripsi** | Fitur "premium" yang khas. Butuh enkripsi file (AES-GCM) + autentikasi biometrik (biometric sudah dipakai di proyek lain, belum di sini). | Besar |
| 11 | **Transfer via Wi-Fi** | HP ke PC lewat browser. Menambah izin jaringan dan server HTTP kecil. | Besar |
| 12 | **Widget / Quick Settings tile** | "Buka Download", "Jalankan aturan sekarang". | Kecil |

**Yang sebaiknya TIDAK dilakukan:** FTP/SMB client, root explorer, cloud sync,
AI/LLM apa pun. Semuanya menambah permukaan serangan, izin, dan ukuran
aplikasi, dan bertentangan dengan posisi "offline, privacy-first, ringan" yang
sudah jadi identitas aplikasi ini.

---

## D. Visual & frontend

### D1. Warna aksen tidak ikut dynamic color

`Color.kt` mendefinisikan delapan aksen kategori sebagai hex tetap
(`AccentImages = #3B82F6`, dst.). Saat pengguna menyalakan "warna ikut
wallpaper", skema utama berubah mengikuti wallpaper sementara ikon file tetap
biru/pink/amber yang sama — hasilnya terlihat tidak sengaja, bukan pilihan.

**Rekomendasi:** turunkan aksen dari `MaterialTheme.colorScheme` dengan rotasi
hue, atau definisikan dua palet aksen (light/dark) yang diuji terhadap kedua
skema. Minimal: validasi kontras aksen di dark mode — beberapa warna sekarang
gagal WCAG AA untuk teks.

### D2. Tidak ada loading state — layar kosong lalu isi muncul mendadak

`BrowserState.loading` benar-benar tidak dirender sebagai apa pun: saat folder
dibuka, area daftar kosong sekejap lalu isinya muncul. Untuk folder besar
(>1000 file) jeda itu terasa.

**Rekomendasi:** skeleton shimmer 6–8 baris placeholder saat `loading == true`.
Persepsi kecepatan naik tanpa perubahan performa nyata.

### D3. Tidak ada viewer gambar

Ketuk foto → intent ke aplikasi lain → pengguna keluar dari FileFlow dan
kehilangan posisinya di folder. Untuk file manager yang menjual "lihat dan
atur", ini celah pengalaman yang paling terasa.

**Rekomendasi:** layar viewer dengan `HorizontalPager` antar file gambar di
folder yang sama, pinch-to-zoom, dan swipe-down untuk kembali.

### D4. Grid tidak edge-to-edge & tanpa thumbnail video

`GridTile` menempatkan `FileThumb` 56dp di tengah kotak beraspek 1.4 — terlihat
seperti ikon yang diperbesar, bukan galeri. Video hanya dapat ikon, tidak frame.

**Rekomendasi:** thumbnail memenuhi tile (`ContentScale.Crop`, `fillMaxSize`),
tambahkan `coil-video` untuk frame video, dan overlay durasi untuk video.

### D5. Heat kalender tenggelam di dark mode

`DayCell` memakai satu hue dengan alpha 0.10–0.40. Di atas `surfaceVariant`
gelap, tanggal dengan 1–2 file praktis tidak terlihat; hanya tanggal ramai yang
kelihatan.

**Rekomendasi:** di dark mode naikkan rentang alpha (0.18–0.55) atau tambahkan
indikator titik di bawah angka, bukan hanya warna latar.

### D6. Slot iklan menyisakan ruang kosong

`AdBanner` mengembalikan `Box` setinggi 52dp hanya jika `adsAllowed` true — itu
sudah benar. Tapi saat consent ditolak setelah banner pernah dirender (atau
sebelum consent selesai), ruangnya sudah terlanjur disediakan di dalam
`LazyColumn`, menghasilkan celah kosong yang bergeser.

**Rekomendasi:** bungkus dengan `AnimatedVisibility` + `shrinkVertically` supaya
perubahan tinggi dianimasikan, bukan melompat.

### D7. Tipografi masih Roboto default

`Type.kt` sudah punya skala yang dipikirkan, tapi jenis hurufnya default sistem.
Untuk aplikasi yang menyebut dirinya "premium" di store listing, huruf kustom
adalah peningkatan persepsi termurah yang tersedia (satu berkas TTF, ~100 KB).

**Rekomendasi:** Plus Jakarta Sans atau Inter, dibundel di `res/font`. Perlu
diingat: huruf kustom harus punya berat Regular/Medium/SemiBold agar tidak
kehilangan hierarki yang sudah dibangun di `Type.kt`.

### D8. Belum ada haptic saat operasi selesai

Long-press sudah bergetar (`HapticFeedbackType.LongPress`), tapi selesainya
operasi tidak. Getaran halus pendek saat copy/hapus selesai adalah konfirmasi
yang terasa tanpa perlu melihat layar.

### D9. Empty state masih polos

Ikon dalam lingkaran + judul + satu baris teks. Berfungsi, tapi tidak
meninggalkan kesan. Ilustrasi vektor sederhana (satu berkas SVG per state
utama) akan menaikkan kesan kualitas secara nyata.

---

## E. Rekomendasi prioritas

### Gelombang 1 — keandalan & kepatuhan (kerjakan lebih dulu)

Ini yang mencegah kehilangan data dan masalah dengan Play/AdMob.

1. **A1** Operasi tahan navigasi (app scope + foreground service atau penanda
   `.incomplete`)
2. **A2** Resolusi tabrakan di preview aturan — pakai ulang logika SmartRename
3. **A4** Media scanner setelah setiap operasi
4. **A3** Angka retensi sampah dibaca dari setelan
5. **A6** Tombol "Ubah pilihan iklan" + "Kebijakan privasi" di Setelan

### Gelombang 2 — melengkapi yang setengah jadi

6. **A5** Dialog konflik dengan "berlaku untuk semua"
7. **A7** Pencarian → aturan menyertakan filter
8. **A8** Status aturan terakhir di Beranda
9. **C1–C2, C5, C6, C7, C8** Fitur kecil berdampak besar: viewer teks, bintang
   file, ekspor aturan, thumbnail video

### Gelombang 3 — engine

10. **B1** Index file di Room (pencarian instan)
11. **B2** Satu walk untuk semua aturan
12. **B3** Hash duplikat paralel
13. **B4** Cache listing folder

### Gelombang 4 — pengalaman

14. **D3** Viewer gambar bawaan
15. **C4** Kompres/ekstrak ZIP
16. **D1, D2, D4, D5** Dynamic color, skeleton, galeri edge-to-edge, kontras kalender

---

## F. Catatan proses

Beberapa hal yang perlu diketahui tentang kondisi proyek saat ini, bukan sebagai
pujian melainkan sebagai konteks:

- **88 unit test** menutupi matcher aturan, heuristik rename, jadwal, MIME/magic
  bytes, path, dan state turunan. Yang **tidak** tertutup: `FileOperator` itu
  sendiri (copy/move/trash/conflict), `TimelineRepository`, dan seluruh alur
  repository. Justru di situlah A1 dan A5 berada. Menambah test untuk
  `FileOperator` dengan filesystem sementara (JUnit `TemporaryFolder`) adalah
  investasi yang paling menguntungkan berikutnya.
- **Tidak ada test instrumentasi.** Semua verifikasi UI saya lakukan manual lewat
  `uiautomator dump` di emulator. Regression pada UI tidak akan tertangkap.
- **`lint` dimatikan untuk release** (`checkReleaseBuilds = false`) karena izin
  all-files-access. Beberapa pemeriksaan lint lain ikut hilang; pertimbangkan
  menyalakannya kembali dengan `ScopedStorage` saja yang di-disable.
- **Skema Room versi 1, tanpa migrasi.** Selama pengembangan itu nyaman
  (`fallbackToDestructiveMigration`), tapi begitu ada pengguna nyata, migrasi
  wajib ada — kalau tidak, update aplikasi akan menghapus semua aturan dan
  riwayat mereka.
