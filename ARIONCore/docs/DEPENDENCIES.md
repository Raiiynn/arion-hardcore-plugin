# Dependensi dan API yang divalidasi

Diperiksa 14 Oktober 2026. Validasi build/API tidak sama dengan pengujian kompatibilitas seluruh plugin pada server hidup.

| Komponen | Versi / resolusi | Penggunaan |
|---|---|---|
| Java | Target/release 21; build lokal dengan Temurin 21.0.12.1+1 portable | Runtime server dan kompilasi |
| Gradle | Wrapper 8.13 | Build Kotlin DSL |
| Shadow | `com.gradleup.shadow:8.3.6` | Membundel JDBC tanpa Saber/Paper |
| Paper API | `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT` | compileOnly; resolve dan compile berhasil |
| SaberFactions | Official release `4.1.9-STABLE`, JAR version `1.6.9.5-4.1.9` | compileOnly dari aset release terverifikasi |
| SQLite JDBC | `org.xerial:sqlite-jdbc:3.49.1.0` | Dibundel, tanpa relokasi paket native |
| JUnit | BOM 5.12.2, Jupiter + platform launcher | Tes domain dan SQLite |
| SLF4J | 2.0.17 simple hanya runtime test | Server Paper menyediakan SLF4J saat runtime |
| Vault | Diperlukan SaberFactions menurut `plugin.yml` resmi | Tidak dibundel; belum diuji pada server hidup |

## Sumber primer

- [Paper project setup](https://docs.papermc.io/paper/dev/project-setup/) menjelaskan repo Maven dan format versi sebelum 26.1 (`1.21.11-R0.1-SNAPSHOT`).
- [Paper getting started](https://docs.papermc.io/paper/getting-started/) mencantumkan Java 21 untuk Paper 1.20 sampai 1.21.11. Jangan memakai persyaratan Java 25 milik Paper 26.1+ untuk target ini.
- [Javadocs Paper 1.21.11](https://javadocs.papermc.io/paper/1.21.11/index.html).
- [SaberFactions release 4.1.9-STABLE](https://github.com/SaberLLC/Saber-Factions/releases/tag/4.1.9-STABLE).
- [Metadata release GitHub](https://api.github.com/repos/SaberLLC/Saber-Factions/releases/tags/4.1.9-STABLE), asset ID `383787441`.
- [Source plugin.yml pada tag](https://github.com/SaberLLC/Saber-Factions/blob/4.1.9-STABLE/src/main/resources/plugin.yml).
- [FPlayers.java](https://github.com/SaberLLC/Saber-Factions/blob/4.1.9-STABLE/src/main/java/com/massivecraft/factions/FPlayers.java), [FPlayer.java](https://github.com/SaberLLC/Saber-Factions/blob/4.1.9-STABLE/src/main/java/com/massivecraft/factions/FPlayer.java), [Faction.java](https://github.com/SaberLLC/Saber-Factions/blob/4.1.9-STABLE/src/main/java/com/massivecraft/factions/Faction.java).
- [SQLite JDBC project](https://github.com/xerial/sqlite-jdbc); artifact di Maven Central berhasil di-resolve dan dipakai tes SQLite nyata.
- [Gradle distribution checksum](https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256) dan [wrapper checksum](https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256).

## Saber: hasil inspeksi binary

`plugin.yml` dalam JAR release (bukan placeholder source Maven) berisi:

```text
name: Factions
version: 1.6.9.5-4.1.9
main: com.massivecraft.factions.FactionsPlugin
depend: [Vault]
```

`javap` terhadap JAR tersebut serta source tag memverifikasi seluruh metode yang dipakai adapter:

```java
FPlayers.getInstance();
FPlayers.getByPlayer(org.bukkit.entity.Player); // returns FPlayer
FPlayer.getFaction();                         // returns Faction
Faction.isNormal();
Faction.getId();
Faction.getTag();
Faction.getFPlayers();                        // Set<FPlayer>
```

Tidak ada API roster mutation, event join faction, claim/unclaim, atau role permission yang diasumsikan di implementasi M1. Tidak ada koordinat Maven Saber yang ditebak. JAR release digunakan langsung sebagai file dependency yang diunduh dan diperiksa saat build. Plugin mendeteksi nama, main class, enabled state, serta versi sebelum menginstansiasi adapter.

## Checksum

SHA-256 SaberFactions release, sesuai metadata GitHub dan hasil hash lokal:

```text
155084547ed93a53e071f13a0a30b251c66f5de13127cfd473b5a877a6f85750
```

SHA-256 Paper API JAR yang dipakai build ini:

```text
c577b181c11a8674310e56c92a91e31c010b7f04c9bd10b91c3be18374401070
```

Paper 1.21.11 memakai versi SNAPSHOT, sehingga koordinatnya sendiri tidak mengunci isi JAR selamanya. Task `verifyPaper` memeriksa checksum JAR API di atas sebelum kompilasi. Jika repository mengganti snapshot, build sengaja gagal; tinjau perubahan sebelum memperbarui checksum. Ini mengunci bytes API, bukan seluruh transitive dependency graph. Generasi metadata verifikasi Gradle 8.13 dicoba tetapi mengalami duplikasi key pada snapshot Paper; metadata tersebut tidak dipakai dalam proyek akhir.

SHA-256 distribusi Gradle 8.13:

```text
20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78
```

SHA-256 wrapper JAR yang disertakan (cocok dengan checksum resmi):

```text
81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f
```

## Batas kompatibilitas

Build berhasil membuktikan pemanggilan Java cocok dengan API yang digunakan. Rilis Saber menyebut dukungan 1.21.x, tetapi sesi ini tidak menjalankan kombinasi Paper 1.21.11 + Saber + Vault atau klien Minecraft. Kompatibilitas internal Saber, permission plugin, protection plugin, dan listener order wajib diperiksa pada server uji. Paper/Folia bukan target yang sama: plugin ini memakai scheduler Paper standar dan tidak menyatakan dukungan Folia.
