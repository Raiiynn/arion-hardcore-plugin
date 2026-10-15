# Genesis build verification ? 15 Oktober 2026

ARIONCore `0.2.0-SNAPSHOT`, Paper `1.21.11`, Java 21. **BUILD SUCCESSFUL**.

Final command: `gradlew.bat build --offline --no-daemon --console=plain`, after resolving dependencies online. Portable Temurin 21.0.12.1+1 and Gradle 8.13 were used; the system Java installation was not changed.

| Suite | Tests | Failures | Errors |
|---|---:|---:|---:|
| DeathBanIntegrationTest | 14 | 0 | 0 |
| GenesisConfigTest | 7 | 0 | 0 |
| GenesisFlightsTest | 4 | 0 | 0 |
| GenesisRepositoryTest | 6 | 0 | 0 |
| GenesisSessionTest | 9 | 0 | 0 |
| GenesisSpawnSearchTest | 6 | 0 | 0 |
| WarScheduleTest | 3 | 0 | 0 |

Total: **49 tests**, zero failures/errors. Includes all 17 existing M1 tests.

Artifact: `build/libs/ARIONCore-0.2.0-SNAPSHOT.jar` (shaded). Inspected archive metadata confirms version 0.2.0-SNAPSHOT, api-version 1.21.11, Genesis permission and optional WorldGuard load order. Main class bytecode major version is 65 (Java 21). SQLite JDBC is bundled; Bukkit/Paper and Saber classes are not bundled. Both existing pinned dependency checksum checks passed. `git diff --check` passed.

SHA-256:

```text
0503c61b342626751acd7bd4b73832f6b0b1163920ac1195ff68561c7b9862bf
```

An initial sandboxed test run could not write Windows' default temporary directory. Tests now use workspace-local `build/test-tmp`; subsequent full tests passed. An offline resolution attempt also required fetching Paper test-runtime dependencies once; the final offline build passed.

## Not verified on a live server

No actual server/world/clients were supplied. These results do not establish live scheduler ordering, worldgen compatibility, WorldGuard runtime compatibility, lightning's interactions with installed plugins, Bedrock rendering, cross-world behavior, TPS or 30-player performance. The staging acceptance checklist in [GENESIS.md](GENESIS.md) remains required. Genesis is disabled by default and does not modify DeathBan storage/rules. There was no deployment to a server.
