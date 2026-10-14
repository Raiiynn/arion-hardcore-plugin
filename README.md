<div align="center">

# ARIONCore

**Fondasi season ARION · Persistent DeathBan · Integrasi faction**

Plugin Minecraft untuk Paper, dibangun dengan Java 21 dan SQLite.

![Java 21](https://img.shields.io/badge/Java-21-F59E0B?style=for-the-badge&logo=openjdk&logoColor=white)
![Paper 1.21.11](https://img.shields.io/badge/Paper-1.21.11-38BDF8?style=for-the-badge)
![SQLite](https://img.shields.io/badge/Storage-SQLite-003B57?style=for-the-badge&logo=sqlite&logoColor=white)
![Milestone 1](https://img.shields.io/badge/Status-Milestone_1-A78BFA?style=for-the-badge)

[Fitur](#fitur) · [Quick start](#quick-start) · [Commands](#commands-dan-izin) · [Operasional](#operasional) · [Dokumentasi](#dokumentasi)

</div>

---

> **Milestone 1 — untuk server uji.** DeathBan dan administrasi sudah tersedia. Sistem conquest masih dalam tahap rancangan; validasi runtime pada server Paper hidup belum selesai. Lihat [hasil validasi](docs/BUILD-RESULT.md).

## Sekilas

| Komponen | Detail |
| :--- | :--- |
| Versi plugin | `0.1.0-SNAPSHOT` |
| Platform | Paper `1.21.11` · Java `21` |
| Build | Gradle Kotlin DSL + Gradle Wrapper |
| Penyimpanan | SQLite · UUID pemain · waktu absolut UTC |
| Integrasi | Adapter baca SaberFactions `1.6.9.5-4.1.9` |
| World default | `areus_s1` |
| DeathBan default | 30 menit · pembatasan login seluruh server |

## Fitur

### Tersedia di M1

- Lifecycle plugin, konfigurasi tervalidasi, Gradle Wrapper, JAR dengan SQLite JDBC di dalamnya.
- DeathBan 30 menit untuk semua penyebab kematian di world yang dikonfigurasi.
- Ban berdasarkan UUID, waktu absolut UTC, tersimpan lintas restart, pemeriksaan pre-login async dan pemeriksaan ulang saat join/respawn.
- Commit database sebelum kick. Ban yang lebih pendek tidak memotong ban aktif yang lebih panjang.
- Perintah admin untuk status, pemeriksaan, pemberian, dan pencabutan DeathBan; audit transaksi.
- Adapter SaberFactions untuk membaca faction pemain online. Nama plugin `Factions`, bukan `SaberFactions`.
- Perhitungan jadwal hari 7/14/21 yang diuji; **belum ada scheduler war aktif**.
- Pengujian otomatis terhadap SQLite sungguhan, termasuk reopen, konkurensi, dan rollback.

### Rencana pengembangan

| Area | Status |
| :--- | :--- |
| Batas 3 faction × 10 anggota | Dirancang · belum aktif |
| Progression dan unlock | Dirancang · belum aktif |
| Capture, neutralize, dan contested | Dirancang · belum aktif |
| War, scoring, dan ownership territory | Dirancang · belum aktif |
| Reward GUI dan proteksi territory | Dirancang · belum aktif |

Detail tersedia dalam [rancangan sistem](docs/DESIGN.md). M1 belum menggantikan sistem conquest produksi.

## Quick start

### 1. Build plugin

Jalankan perintah berikut dari direktori `ARIONCore/`.

Pasang JDK 21 dan arahkan `JAVA_HOME` ke JDK tersebut. Wrapper disertakan; tidak perlu memasang Gradle global.

```powershell
.\gradlew.bat clean build --no-daemon
```

<details>
<summary>Linux / macOS</summary>

```sh
sh ./gradlew clean build --no-daemon
```

</details>

Build mengunduh Paper API dari repo Paper, SQLite/JUnit dari Maven Central, serta JAR SaberFactions dari rilis GitHub resmi dengan SHA-256 yang dikunci. SaberFactions hanya dipakai saat kompilasi dan **tidak dibundel** dalam ARIONCore. Detail provenance: [DEPENDENCIES.md](docs/DEPENDENCIES.md).

Hasil yang dipasang: `build/libs/ARIONCore-0.1.0-SNAPSHOT.jar`. Jangan memasang JAR berakhiran `-plain.jar`.

### 2. Pasang di server uji

1. Siapkan server uji Paper **1.21.11** dengan Java **21**. Tinjau dan setujui EULA Minecraft sendiri sebelum menjalankannya.
2. Salin JAR ARIONCore ke `plugins/`. Untuk menguji adapter faction, pasang Vault dan JAR SaberFactions resmi versi tepat di atas.
3. Jalankan server. Pastikan world `areus_s1` tersedia; ARIONCore **tidak membuat atau memuat world**. Jika survival utama harus bernama itu, atur `level-name=areus_s1` sebelum membuat dunia baru, atau gunakan pengelola world Anda.
4. Periksa console atau `/arion status`: harus `READY`. Edit `plugins/ARIONCore/config.yml` lalu restart penuh untuk menerapkan konfigurasi.
5. Ikuti [checklist pengujian di server](docs/TESTING.md).

SaberFactions opsional untuk uji DeathBan terpisah (`integrations.saberfactions.required: false`). Jika `true`, plugin hilang/versi berbeda menggagalkan inisialisasi dan menutup login. Ini bukan klaim bahwa conquest dapat berjalan tanpa SaberFactions.

### 3. Atur konfigurasi

Bagian konfigurasi yang aktif pada M1 di `plugins/ARIONCore/config.yml`:

```yaml
world: areus_s1
deathban:
  duration-seconds: 1800
integrations:
  saberfactions:
    required: false
```

Restart penuh setelah mengubah konfigurasi. Bagian `season`, `war`, `territories`, dan `rewards` dalam file konfigurasi disiapkan untuk milestone berikutnya dan **belum diberlakukan pada M1**.

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

### Cakupan DeathBan

Ban login berlaku untuk **seluruh server**, meskipun pemicunya hanya kematian di `areus_s1`. Nether/End/world lain tidak ikut kecuali rancangan diperluas. Waktu terus berjalan ketika server offline. M1 tidak mengubah vanilla ban list, inventory, drops, XP, keepInventory, atau data faction.

### Penyimpanan dan backup

SQLite berada di `plugins/ARIONCore/arion.db`, dengan WAL dan `synchronous=FULL`. Hentikan server dengan bersih sebelum menyalin backup; jangan menyalin file `.db` saja saat WAL masih aktif. Record ban kedaluwarsa tetap tersimpan, tetapi tidak menolak login. Retensi audit/cleanup adalah pekerjaan selanjutnya.

### Penanganan kegagalan

Kegagalan database menyebabkan status `FAILED`, menolak login, dan mengeluarkan pemain yang sedang online. Guard tetap aktif agar ban tidak dapat dilewati ketika database bermasalah. Kegagalan I/O runtime mencoba menulis `STORAGE-FAILURE.txt`; restart dengan marker itu tetap terkunci. Perbaiki storage, baca konteks/audit, rekonsiliasi ban yang mungkin gagal tersimpan, kemudian hapus marker ketika server berhenti. Jangan menghapus marker hanya untuk memaksa server terbuka. Jika disk sama sekali tidak dapat ditulis, marker juga mungkin gagal; console harus ditinjau sebelum restart.

### Restart dan performa

Tidak ada hot reload. Jangan disable/reload ARIONCore ketika server dibuka untuk pemain: plugin yang dimatikan tidak dapat menjaga login. Jika plugin gagal dimuat oleh Paper sebelum `onEnable` (misalnya JAR hilang/rusak), guard juga belum tersedia. M1 bukan pengganti whitelist/maintenance operasional.

Untuk beban 30 pemain, write SQLite dilakukan sinkron supaya commit selesai sebelum kick; koneksi diserialkan dengan pembacaan async. Disk lambat/lock dapat menghentikan tick hingga timeout 3 detik. Pantau dan ukur di host target sebelum produksi; jangan menaruh database di network drive.

## Dokumentasi

| Dokumen | Isi |
| :--- | :--- |
| [Desain sistem](docs/DESIGN.md) | Spesifikasi, asumsi, batas modul, milestone, dan invariant anti-duplikasi |
| [Dependensi](docs/DEPENDENCIES.md) | Versi, sumber API, checksum, dan batas validasi |
| [Panduan pengujian](docs/TESTING.md) | Pengujian otomatis dan checklist skenario server |
| [Hasil build](docs/BUILD-RESULT.md) | Catatan validasi M1 dan hal yang belum diuji |

---

<div align="center">

**ARIONCore** · Milestone 1<br>
<sub>Java 21 · Paper · SQLite · SaberFactions</sub>

</div>
