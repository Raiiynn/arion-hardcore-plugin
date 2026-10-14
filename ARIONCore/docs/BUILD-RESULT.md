# Hasil validasi Milestone 1

Tanggal: **14 Oktober 2026**, zona pengguna Asia/Jakarta. Build final selesai sekitar **20:14 WIB**.

## Hasil

**BUILD SUCCESSFUL** melalui Gradle Wrapper 8.13, target Java 21. Command proyek:

```powershell
.\gradlew.bat clean build --no-daemon --console=plain --warning-mode all
```

JDK lokal yang digunakan: Temurin `21.0.12.1+1` portable di direktori kerja terpisah; tidak memerlukan perubahan instalasi Java global pengguna. Build final selesai dalam 25 detik, 9 task dieksekusi, tanpa peringatan. Build awal juga berhasil; percobaan tambahan menghasilkan metadata verifikasi gagal pada duplikasi key snapshot Paper, lalu diganti dengan task pemeriksaan hash `verifyPaper`. Hasil final di atas berasal dari build bersih setelah perbaikan.

| Pemeriksaan | Hasil |
|---|---|
| Resolve Paper API 1.21.11 + compile Java 21 | Lulus |
| Download/checksum Saber release + compile adapter | Lulus |
| Gradle Wrapper checksum resmi | Cocok |
| `verifyPaper` hash API snapshot | Cocok |
| DeathBan + SQLite integration tests | **14 lulus**, 0 gagal/error/skip |
| WarSchedule unit tests | **3 lulus**, 0 gagal/error/skip |
| Driver/native SQLite dari JAR hasil build | Lulus smoke test save → close → reopen → check → revoke |
| Pemeriksaan isi JAR | plugin.yml/config.yml ada; SQLite JDBC + DLL Windows x64 ada; Paper/Saber tidak dibundel |
| Versi plugin dalam JAR | `ARIONCore`, `0.1.0-SNAPSHOT`, API `1.21.11` |

Smoke test memakai hanya JAR ARIONCore hasil build dan SLF4J sebagai library yang pada server disediakan Paper. Tidak memakai SQLite JDBC terpisah dari dependency cache. Output: `PACKAGED SQLITE SMOKE PASS: bundled driver + native library + save/reopen/revoke`.

Artifact siap dipasang ke **server uji**:

```text
ARIONCore-0.1.0-SNAPSHOT.jar
SHA-256: 35be03c8979e7a4e6d2f18e867b4748a9863dabf4e7b96107d5eb547d046d9ac
```

Laporan tes aktual berada di `build/reports/tests/test/index.html` dan `build/test-results/test/` pada workspace. Source ZIP tidak menyertakan cache Gradle, JDK portable, database uji, build directories atau JAR SaberFactions. Menjalankan build akan membuat laporan tes baru.

## Belum divalidasi

- Boot dan lifecycle pada server Paper 1.21.11 hidup dengan klien Minecraft.
- Runtime SaberFactions + Vault, permission/protection plugins, cancel death oleh plugin lain.
- Perilaku kick/pre-login/join/respawn melalui sesi pemain nyata.
- Performa storage pada host produksi dan 30 pemain.
- Fitur M2–M5 yang baru dirancang, termasuk roster cap, progression, war, conquest, ownership dan reward.

Tidak ada server produksi yang diubah, tidak ada EULA yang disetujui otomatis, dan tidak ada klaim bahwa milestone selanjutnya sudah terimplementasi. Gunakan [TESTING.md](TESTING.md) sebelum mengaktifkan M1 pada server utama.
