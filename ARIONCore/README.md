# ARIONCore — Milestone 1

Plugin Java 21 untuk **Paper 1.21.11**, Gradle Kotlin DSL, SQLite, dan adapter baca SaberFactions **1.6.9.5-4.1.9**. Nama plugin, command, artifact, serta database memakai ARION; world tetap **`areus_s1`** sesuai permintaan.

## Yang sudah berjalan

- Lifecycle plugin, konfigurasi tervalidasi, Gradle Wrapper, JAR dengan SQLite JDBC di dalamnya.
- DeathBan 30 menit untuk semua penyebab kematian di world yang dikonfigurasi.
- Ban berdasarkan UUID, waktu absolut UTC, tersimpan lintas restart, pemeriksaan pre-login async dan pemeriksaan ulang saat join/respawn.
- Commit database sebelum kick. Ban yang lebih pendek tidak memotong ban aktif yang lebih panjang.
- Perintah admin untuk status, pemeriksaan, pemberian, dan pencabutan DeathBan; audit transaksi.
- Adapter SaberFactions untuk membaca faction pemain online. Nama plugin `Factions`, bukan `SaberFactions`.
- Perhitungan jadwal hari 7/14/21 yang diuji; **belum ada scheduler war aktif**.
- Pengujian otomatis terhadap SQLite sungguhan, termasuk reopen, konkurensi, dan rollback.

**Belum aktif:** pembatasan 3 faction × 10 anggota, progression/unlock, capture/neutralize/contested, war, scoring, ownership territory, reward GUI, dan proteksi territory. Bagian tersebut ada dalam [rancangan](docs/DESIGN.md), bukan fitur yang sudah selesai. Jangan gunakan M1 sebagai pengganti sistem conquest produksi.

## Build

Pasang JDK 21 dan arahkan `JAVA_HOME` ke JDK tersebut. Wrapper disertakan; tidak perlu memasang Gradle global.

```powershell
.\gradlew.bat clean build --no-daemon
```

Linux/macOS: `sh ./gradlew clean build --no-daemon`.

Build mengunduh Paper API dari repo Paper, SQLite/JUnit dari Maven Central, serta JAR SaberFactions dari rilis GitHub resmi dengan SHA-256 yang dikunci. SaberFactions hanya dipakai saat kompilasi dan **tidak dibundel** dalam ARIONCore. Detail provenance: [DEPENDENCIES.md](docs/DEPENDENCIES.md).

Hasil yang dipasang: `build/libs/ARIONCore-0.1.0-SNAPSHOT.jar`. Jangan memasang JAR berakhiran `-plain.jar`.

## Instalasi uji

1. Siapkan server uji Paper **1.21.11** dengan Java **21**. Tinjau dan setujui EULA Minecraft sendiri sebelum menjalankannya.
2. Salin JAR ARIONCore ke `plugins/`. Untuk menguji adapter faction, pasang Vault dan JAR SaberFactions resmi versi tepat di atas.
3. Jalankan server. Pastikan world `areus_s1` tersedia; ARIONCore **tidak membuat atau memuat world**. Jika survival utama harus bernama itu, atur `level-name=areus_s1` sebelum membuat dunia baru, atau gunakan pengelola world Anda.
4. Periksa console atau `/arion status`: harus `READY`. Edit `plugins/ARIONCore/config.yml` lalu restart penuh untuk menerapkan konfigurasi.
5. Ikuti [checklist pengujian di server](docs/TESTING.md).

SaberFactions opsional untuk uji DeathBan terpisah (`integrations.saberfactions.required: false`). Jika `true`, plugin hilang/versi berbeda menggagalkan inisialisasi dan menutup login. Ini bukan klaim bahwa conquest dapat berjalan tanpa SaberFactions.

## Commands dan izin

Semua command memerlukan `arion.admin` (default OP); console boleh menjalankannya.

| Command | Perilaku |
|---|---|
| `/arion status` | Status kesehatan dan ketersediaan adapter faction |
| `/arion faction <nama-online>` | ID, tag, jumlah anggota normal faction |
| `/arion deathban check <nama-online\|uuid>` | Sisa waktu ban aktif |
| `/arion deathban apply <nama-online\|uuid> <detik>` | Beri/perpanjang ban 1–31.536.000 detik, commit lalu kick |
| `/arion deathban clear <nama-online\|uuid>` | Cabut record ban secara persisten |

Gunakan UUID lengkap untuk pemain offline; plugin tidak melakukan lookup nama jaringan atau menebak UUID. Permission `arion.deathban.bypass` default **false**, termasuk untuk OP: hanya mengecualikan kematian berikutnya. Ban yang sudah aktif tetap berlaku sampai kedaluwarsa/dicabut. Command admin `apply` tetap berlaku pada pemegang bypass.

## Operasional

Ban login berlaku untuk **seluruh server**, meskipun pemicunya hanya kematian di `areus_s1`. Nether/End/world lain tidak ikut kecuali rancangan diperluas. Waktu terus berjalan ketika server offline. M1 tidak mengubah vanilla ban list, inventory, drops, XP, keepInventory, atau data faction.

SQLite berada di `plugins/ARIONCore/arion.db`, dengan WAL dan `synchronous=FULL`. Hentikan server dengan bersih sebelum menyalin backup; jangan menyalin file `.db` saja saat WAL masih aktif. Record ban kedaluwarsa tetap tersimpan, tetapi tidak menolak login. Retensi audit/cleanup adalah pekerjaan selanjutnya.

Kegagalan database menyebabkan status `FAILED`, menolak login, dan mengeluarkan pemain yang sedang online. Guard tetap aktif agar ban tidak dapat dilewati ketika database bermasalah. Kegagalan I/O runtime mencoba menulis `STORAGE-FAILURE.txt`; restart dengan marker itu tetap terkunci. Perbaiki storage, baca konteks/audit, rekonsiliasi ban yang mungkin gagal tersimpan, kemudian hapus marker ketika server berhenti. Jangan menghapus marker hanya untuk memaksa server terbuka. Jika disk sama sekali tidak dapat ditulis, marker juga mungkin gagal; console harus ditinjau sebelum restart.

Tidak ada hot reload. Jangan disable/reload ARIONCore ketika server dibuka untuk pemain: plugin yang dimatikan tidak dapat menjaga login. Jika plugin gagal dimuat oleh Paper sebelum `onEnable` (misalnya JAR hilang/rusak), guard juga belum tersedia. M1 bukan pengganti whitelist/maintenance operasional.

Untuk beban 30 pemain, write SQLite dilakukan sinkron supaya commit selesai sebelum kick; koneksi diserialkan dengan pembacaan async. Disk lambat/lock dapat menghentikan tick hingga timeout 3 detik. Pantau dan ukur di host target sebelum produksi; jangan menaruh database di network drive.

## Dokumentasi

- [DESIGN.md](docs/DESIGN.md): spesifikasi, asumsi, batas modul, milestone dan invariant anti-duplikasi.
- [DEPENDENCIES.md](docs/DEPENDENCIES.md): versi, sumber API, checksum dan batas validasi.
- [TESTING.md](docs/TESTING.md): pengujian otomatis dan skenario server.
- [BUILD-RESULT.md](docs/BUILD-RESULT.md): hasil build aktual dan keterbatasannya.
