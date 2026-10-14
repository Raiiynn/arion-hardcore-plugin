# Rancangan ARIONCore

## Sumber dan status keputusan

Acuan: pesan pengguna bertanggal 14 Oktober 2026 dan percakapan `Info Server Minecraft` (`6acd98c8-681c-83ec-a889-b57d1788260c`). Pembacaan percakapan hanya mengembalikan teks pesan pengguna dan penanda `chatgpt-content-reference` untuk jawaban lama, tanpa isi spesifikasi. Detail yang tidak tersedia **tidak diklaim sudah disepakati**.

Permintaan yang pasti: Java 21, Gradle Kotlin DSL, Paper 1.21.11, SaberFactions 1.6.9.5-4.1.9, SQLite, 3 faction masing-masing maksimal 10 anggota, `areus_s1`, DeathBan 30 menit, progression season, tiga territory berpoin 5/3/3, capture 60 detik, neutralize 30 detik, contested, war 60 menit pada hari ke-7 lalu setiap 7 hari, ownership persisten, virtual reward chest sekali per territory per war, anti-duplikasi, proteksi, dan command admin.

### Asumsi yang dipakai atau diusulkan

| Hal | Asumsi / status |
|---|---|
| Nama proyek | ARIONCore; nama world tetap `areus_s1` |
| DeathBan | Semua sebab kematian di world tepat tersebut, ban login server-wide, OP tidak otomatis bebas |
| Hari season | Hari 1 dimulai pada `seasonStart`; hari 7 = `seasonStart + 6 hari kalender` |
| Zona/jam | Default Asia/Jakarta; waktu mulai season dan jam war belum dipilih. Usulan: war memakai jam lokal seasonStart |
| Jadwal | Hari 7, 14, 21, dst.; durasi 60 menit; akhir interval eksklusif |
| Progression | Panjang season, nama fase, tanggal unlock, Nether/End/gear cap belum diketahui; tidak diisi dengan tebakan |
| Faction | Tiga ID tetap yang dipilih admin; rename tag tidak mengubah identitas; roster termasuk anggota offline |
| Territory | ID sementara territory_1/2/3; nama, koordinat, radius, tinggi, spawn dan area proteksi belum ditentukan |
| Poin | Usulan: 5/3/3 diberikan sekali pada akhir setiap war kepada pemilik saat penutupan; bukan poin setiap tick/capture |
| Capture | Usulan: tepat satu faction eligible hadir menggerakkan timer; jumlah pemain tidak mempercepatnya |
| Contested | Usulan: 2+ faction eligible menjeda timer tanpa decay; aliansi tetap dihitung sebagai faction berbeda |
| Kosong / penyerang berganti | Usulan: reset kemajuan parsial; defenders saja mereset usaha penyerang |
| Takeover | Usulan: neutralize 30s menghapus owner, lalu capture 60s memberi owner baru; total 90s tanpa gangguan |
| Reward | Usulan: pemilik pada akhir war menjadi beneficiary; satu klaim global untuk pasangan territory+war, bukan sekali per pemain/faction |
| Isi reward dan hak klaim | Belum dipilih; usulan hanya leader/officer pemilik snapshot akhir war |
| Territory tanpa pemilik | Usulan: tidak mendapat poin/reward pada akhir war |
| Restart saat war | Usulan: durasi wall-clock tidak diperpanjang; progress parsial reset; owner tetap; finalisasi idempotent setelah restart |
| War terlewat seluruhnya | Usulan: tandai SKIPPED jika server tidak pernah mengaktifkannya, tanpa hadiah pasif |
| Proteksi | Usulan: territory selalu dilindungi dari perubahan dunia; PvP hanya selama war; perlu diselaraskan dengan aturan survival/raid |

Tidak ada batas 3×10 atau jadwal season yang diam-diam dianggap sudah aktif di M1. ID faction dan semua keputusan di atas perlu ditetapkan sebelum milestone yang bersangkutan diaktifkan.

## Struktur dan batas modul

```text
id.arion.core
  ARIONCorePlugin          lifecycle, dependency detection, health gate
  config/CoreConfig       konfigurasi M1 dengan validasi
  storage/SqliteStore      migrasi schema v1, JDBC dan transaksi audit+ban
  deathban/               record, repository boundary, service, Paper listener
  command/AdminCommand    command admin dan pemeriksaan permission
  faction/                interface snapshot + adapter read-only Saber terverifikasi
  season/WarSchedule      kalkulasi window jadwal; belum ada scheduler runtime
```

Modul berikutnya: `season` orchestration/progression, `roster`, `war`, `territory`, `reward`, `protection`. Domain memakai UUID/ID faction dan waktu eksplisit. Panggilan Bukkit/Saber berada pada server thread; database/domain tidak memanggil API faction dari pre-login async. Integrasi hanya melalui `FactionGateway`; tidak ada refleksi atau fallback API rekaan.

SaberFactions tetap sumber kebenaran membership. ARIONCore menyimpan whitelist tiga ID, bukan menduplikasi seluruh membership. Sebelum roster enforcement dibuat, verifikasi event perubahan membership, cancelability, admin bypass, alts/roster Saber, disband, join/leave dan perubahan lewat plugin lain pada rilis tepat tersebut. Jangan mengandalkan command interception saja. `getFPlayers().size()` pada M1 hanya diagnostik anggota, bukan bukti cap roster/alt sudah ditegakkan.

## State machine yang akan dibangun

Season: `DRAFT → ACTIVE → ENDED`. War: `SCHEDULED → ACTIVE → FINALIZING → FINISHED`, atau `SKIPPED`. ID war deterministik dari season ID + nomor urut. Simpan waktu mulai/akhir UTC, konfigurasi versi, serta jadwal lokal asal. Jangan menetapkan awal season dari waktu plugin boot.

Territory menyimpan owner permanen terpisah dari state capture sementara. `IDLE`, `NEUTRALIZING`, `CAPTURING`, dan `CONTESTED` adalah state aktivitas. Owner tidak berubah karena sekadar contested/kosong. Netralisasi selesai menghapus owner secara atomik; capture selesai memasang owner. Penutupan war membuang progress parsial dan mempertahankan owner terakhir yang sudah committed, termasuk null bila netralisasi selesai tetapi capture belum selesai.

Eligibility capture: online, hidup, mode survival, di volume territory, anggota salah satu ID faction terdaftar, bukan spectator/admin bypass, dan tidak sedang terkena DeathBan. Timer memakai waktu monotonik; jangan mengejar seluruh lag sebagai kemajuan tanpa observasi occupancy. Evaluasi pada server thread. Batas volume, toleransi ketinggian, interval pengamatan, dan aturan disconnect ditetapkan sebelum M3.

`WarSchedule` M1 menguji konvensi hari saja. M2 perlu scheduler wall-clock, persistence, operasi admin start/end dan recovery. Saat downtime memotong war yang sudah ACTIVE, jangan mengulang skor/reward; owner terakhir yang committed menjadi snapshot penutupan. War yang seluruh waktunya telah lewat namun belum pernah ACTIVE mengikuti kebijakan SKIPPED di atas.

## Rancangan schema lanjutan (belum dimigrasikan di M1)

M1 hanya `death_bans`, `audit_log`, dan `PRAGMA user_version=1`. Tabel di bawah merupakan kontrak desain untuk migrasi berikutnya, bukan tabel yang sudah tersedia:

| Tabel | Kunci/invariant |
|---|---|
| seasons | season_id PK; starts_at, timezone, state, config_version |
| season_factions | PK(season_id, faction_id); tepat 3 sebelum start |
| wars | PK(season_id, ordinal); start/end/state; finalisasi hanya sekali |
| territories | PK(season_id, territory_id); point_weight, immutable configured volume |
| territory_ownership | PK(season_id, territory_id); owner nullable, version untuk compare-and-set |
| war_results | PK(war_id, territory_id); snapshot owner, weight; input scoring idempotent |
| reward_entitlements | UNIQUE(war_id, territory_id); beneficiary snapshot, payload immutable, claim state |
| reward_slots | PK(entitlement_id, slot_id); serialisasi item/version/jumlah otoritatif |
| reward_delivery | delivery_id PK; slot_id, actor_uuid, quantity, status, audit timestamps |

Perubahan owner dan audit dilakukan dalam satu transaksi. Penutupan war menulis hasil, poin, entitlement, dan state final secara atomik. Aggregate score dihitung dari hasil unik atau diperbarui dengan guard transaksi yang sama. `season_id` mencegah kepemilikan/reward season lama bocor ke season baru. Pembatas jumlah faction tidak cukup memakai UNIQUE; validasi start season + transaksi roster dibutuhkan.

## Reward chest virtual dan batas anti-duplikasi

Chest fisik hanya membuka GUI; bukan inventory vanilla berisi reward. Beneficiary dikunci ke snapshot akhir war. Payload reward ditentukan sekali, tidak di-roll ulang ketika GUI dibuka. Satu entitlement untuk territory+war berarti seluruh anggota melihat saldo virtual yang sama, bukan masing-masing mendapat satu chest.

GUI harus memakai custom InventoryHolder/session token, cancel seluruh manipulasi vanilla, dan menghitung transaksi dari DB. Lindungi click/shift-click, drag, number key, double click, swap offhand, drop, creative, close/disconnect, dua pengguna sekaligus, inventory penuh, hopper dan interaksi chest luar. Jangan mempercayai nama atau lore item sebagai otorisasi.

Klaim virtual memakai update kondisional atomik, cek faction/role hidup pada server thread, dan constraint unik. Pemindahan barang fisik dari SQLite ke inventory Minecraft **tidak memiliki transaksi atomik bersama**. Klaim exactly-once end-to-end ketika crash tidak boleh dijanjikan hanya dengan flag `claimed`.

Usulan payout aman terhadap duplikasi: reserve/debit saldo + buat delivery `PENDING` dalam transaksi DB sebelum memberikan item. Setelah pemberian berhasil, tandai `DELIVERED`. Delivery ambigu setelah crash dikarantina `NEEDS_REVIEW`; **jangan otomatis mengulangnya**. Ini menghindari replay duplikat, tetapi bisa menyebabkan reward tertunda/hilang yang perlu rekonsiliasi admin. Jangan mengubah saldo kembali secara otomatis pada crash. Uji fault injection pada setiap batas commit/grant sebelum reward dirilis.

Jangan memberi reward/poin saat capture berulang. Ownership transfer pada war berikutnya tidak boleh membuat entitlement baru untuk war lama. Reset season dan force-end juga harus memakai jalur idempotent yang sama, bukan command SQL bebas.

## Proteksi yang direncanakan

Area territory perlu satu resolver volume untuk capture, GUI, dan protection. Tolak break/place, bucket/fluid masuk-keluar area, piston lintas batas, explosion/TNT, fire/spread, entity change block, hanging/armor stand, hopper transfer dan container interactions yang relevan. Uji juga operasi admin/plugin seperti WorldEdit secara terpisah; event Bukkit saja tidak menjamin semua perubahan oleh plugin lain tertangkap.

PvP war harus bersesuaian dengan aturan SaberFactions (ally, friendly fire, safe/war zone). Jangan meng-uncancel proteksi plugin lain secara membabi buta. DeathBan tetap berlaku selama war. Ender pearl/teleport masuk, spawn camping, lokasi respawn, logout combat, dan batas raid luar territory belum ditentukan.

## Milestone dan penerimaan

1. **M1 foundation + DeathBan (implementasi sekarang):** build Java 21, SQLite, lifecycle, fail-closed login, admin commands, adapter read-only, tests restart/expiry/revoke/rollback/concurrency. Uji server manual masih diperlukan.
2. **M2 season + roster:** pilih tiga faction ID, enforce 10 anggota termasuk perubahan dari jalur lain, simpan start season dan progression eksplisit, jadwal hari 7/14/21, admin `/arion season ...` dan `/arion roster ...`, startup recovery.
3. **M3 conquest + protection:** tiga volume valid tak tumpang tindih, timer 60/30, contested, ownership persisten, war 60m, skor unik 5/3/3, admin `/arion territory ...` dan `/arion war ...`. Uji batas region, disconnect, lag dan restart.
4. **M4 reward:** entitlement tunggal, virtual chest, slot ledger, payout quarantine, admin audit/reconcile. Uji spam, concurrent claims, full inventory, crash sebelum/sesudah pemberian item.
5. **M5 staging:** Paper + Saber + Vault yang tepat, 30 pemain simulasi/nyata, profiling tick/storage, interaksi permission/protection, backup/restore dan dokumentasi operasional final.

Commands M2–M4 di atas **belum didaftarkan**. M1 tidak mengeluarkan pesan sukses palsu untuk fitur yang belum dibuat.
