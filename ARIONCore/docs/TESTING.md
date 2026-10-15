# Pengujian ARIONCore M1

## Genesis automated checks and staging

The Genesis suite adds tests for configuration types and safe defaults, faction/region assignment, connected group placement, separation, hazard/border filtering, legal transitions, duplicate start intent, bounded in-flight dispatch, timeout/retry behavior, stale callbacks, SQLite snapshot/reopen, unique active session, archive retention, stale write rejection, rollback and ambiguous crash outcomes. Existing M1 tests remain unchanged.

Run `gradlew.bat build` with JDK 21. Test temporary files now use `build/test-tmp` for reproducible workspace-local SQLite tests. These are domain/configuration/SQLite tests, not a simulated Paper runtime. Follow [GENESIS.md](GENESIS.md#required-staging-acceptance-not-claimed-by-unit-tests) for live scheduler, Saber, worldgen, lightning, cross-world teleport, Bedrock and thirty-player verification.

The original M1 results/checklist below remain historical context.

## Otomatis

Dengan JDK 21: `./gradlew clean build` atau `gradlew.bat clean build`.

17 tes: 14 tes service dengan SQLite nyata dan 3 tes jadwal. Tidak memakai server palsu untuk mengklaim integrasi Paper/Saber telah berjalan.

- DeathBan tepat 1800 detik, aktif 1ms sebelum akhir dan tidak aktif tepat pada akhir.
- Pembulatan sisa detik, world lain/Nether, bypass, dan isolasi UUID.
- Bypass tidak menghapus ban yang sudah ada.
- Reopen SQLite mempertahankan waktu akhir; clear bertahan pada reopen berikutnya.
- Apply yang lebih pendek tidak memotong ban; kematian berulang pada waktu sama tidak menumpuk penalti.
- Perpanjangan ban, validasi durasi, audit actor dalam transaksi.
- Fault injection dengan trigger SQLite: kegagalan audit membatalkan ban baru maupun pencabutan ban.
- 60 operasi bersamaan dengan 6 thread menjaga expiry terpanjang.
- Schema masa depan ditolak; database tertutup melempar error alih-alih menganggap pemain tidak diban.
- Hari 7/14/21, durasi 60 menit, batas window dan input jadwal invalid.

Laporan HTML: `build/reports/tests/test/index.html`. XML: `build/test-results/test/`.

## Checklist server manual — belum dijalankan

Gunakan server uji dan akun non-OP; simpan UUID tester sebelum kick agar command console tetap mudah dipakai. Jangan menyingkat konfigurasi produksi untuk pengujian.

| Langkah | Hasil yang diharapkan |
|---|---|
| Boot Paper 1.21.11 Java 21, ARION saja, Saber required=false | READY; adapter unavailable dijelaskan |
| Tambahkan Vault + SaberFactions tepat 1.6.9.5-4.1.9 | READY; `/arion faction Tester` menampilkan snapshot yang benar |
| Tanpa Saber / versi lain dan required=true | FAILED; login ditolak; console status tetap bisa digunakan |
| Hapus/ubah tipe world atau durasi config menjadi invalid | FAILED; tidak diam-diam memakai nilai salah |
| World `areus_s1` tidak dimuat | Warning jelas; tidak ada world otomatis dibuat |
| Tester mati di `areus_s1`, sebab fall/lava/mob/PvP | Ban disimpan, kick, reconnect ditolak dengan waktu tersisa |
| Restart server 5 menit setelah ban dimulai | Sisa kira-kira 25 menit, bukan 30 menit baru |
| Tester mati di world lain atau `areus_s1_nether` | Tidak ada DeathBan baru |
| OP tanpa explicit bypass mati di `areus_s1` | Tetap terkena DeathBan |
| Beri permission bypass lalu mati tanpa ban sebelumnya | Tidak diban |
| Beri bypass pada pemain yang sudah diban | Login tetap ditolak; clear melalui console diperlukan |
| `arion deathban apply <uuid> 10`, reconnect sebelum/sesudah 10s | Ditolak sebelum expiry, diterima setelahnya |
| `arion deathban apply <uuid> 1800` lalu apply 10 | Expiry tidak dipersingkat |
| `arion deathban clear <uuid>` kemudian restart | Ban tetap hilang |
| Pemain biasa memakai admin command | Ditolak permission; tab completion tidak membocorkan opsi |
| UUID malformed, nama offline, angka negatif/overflow | Pesan input invalid; tidak menulis DB |
| Plugin lain menolak login, pemain tanpa DeathBan | ARION tidak memanggil allow()/membatalkan penolakan tersebut |
| Ban dibuat saat koneksi sudah melewati pre-login | Join guard masih mengeluarkan pemain |
| Immediate respawn aktif dan mati | Ban tetap berlaku; tidak ada celah reconnect/respawn |
| Error DB pada salinan server uji | FAILED, login ditolak, pemain online dikeluarkan; marker ditulis bila storage masih bisa ditulis |
| Restart dengan STORAGE-FAILURE.txt | Tetap FAILED sampai rekonsiliasi operator |

Periksa juga perilaku server ketika PlayerDeathEvent dibatalkan oleh plugin lain: handler M1 `MONITOR, ignoreCancelled=true` semestinya tidak menambah ban untuk kematian yang batal. Urutan listener, perilaku kick/respawn, dan plugin yang mengubah data di luar event hanya dapat divalidasi pada server.

## Recovery uji

Stop server, backup seluruh direktori data dengan bersih, perbaiki penyebab SQLite gagal. Tinjau marker, log console dan audit, khususnya UUID pada operasi death/admin yang gagal. Rekonsiliasi penalti sebelum membuka server lagi; gunakan maintenance/whitelist dan console. Hapus marker hanya setelah kondisi diperbaiki. M1 sengaja tidak menyediakan command bypass untuk membuka gate ketika database masih gagal.

Jika error init berasal dari konfigurasi atau required dependency, perbaiki dan restart. Jika schema lebih baru, gunakan plugin versi yang kompatibel atau backup yang cocok; jangan menurunkan `user_version` secara manual. Hot reload/disable bukan alur pengujian yang didukung.

## Uji milestone berikutnya

M2–M4 memerlukan pengujian tersendiri: cap roster dari seluruh jalur perubahan, save/load season, downtime schedule, border region, contested multi-faction, reset timer, ownership transfer, force-end idempotent, double claim, inventory penuh, spam drag/shift-click/hopper, serta crash di setiap tahap reward delivery. Kelulusan tes M1 tidak membuktikan fitur tersebut sudah aman/tersedia.
