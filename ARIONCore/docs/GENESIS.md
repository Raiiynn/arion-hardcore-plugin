# Genesis — Divine Descent

Target: **Paper 1.21.11 / Java 21**, in the existing ARIONCore plugin. Genesis is opt-in and does not change DeathBan rules, faction membership, inventory, gamemode or flight settings. No music, BlueMap markers, automatic Chunky runs, season progression or Territory Conquest runtime is added.

## Installation and configuration

Build with Java 21 using `gradlew.bat build`. Deploy the shaded `build/libs/ARIONCore-0.2.0-SNAPSHOT.jar`, not the `-plain.jar`, on a staging server first. Do not install the old and new ARIONCore JARs together. Stop the server before replacing the plugin and back up the plugin data directory.

Existing config.yml files are not overwritten by `saveDefaultConfig()`. Copy the `genesis:` section from the bundled config into your existing config manually. Missing section means disabled. Restart fully after configuration changes; hot reload is unsupported.

Set:

- `enabled: true`, actual `opening-world`, destination `areus_s1` (or the actual survival world).
- Three **Saber faction IDs**, not tags, in `faction-ids`. `/arion faction <online-player>` displays them.
- Explicit `excluded-player-uuids` for the GM and staff. All other online players in the opening world must be eligible participants in the configured factions. Factionless players cause validation to fail.
- Exactly three curated `candidate-regions`. The example below is a shape, not a surveyed safe production location.
- `excluded-regions` for territories, capture zones, war areas, dangerous custom structures and protection providers without an adapter.
- `protection-review-confirmed: true` only after the operator reviews that inventory and exclusions. The plugin cannot discover arbitrary third-party protection systems.

```yaml
candidate-regions:
  - id: north-west
    min-x: -3500
    max-x: -3000
    min-z: -3500
    max-z: -3000
    # Optional; x/z are block coordinates, y is the player's feet Y.
    # This anchor still has to pass all safety checks.
    fallback-anchor: {x: -3250, y: 75, z: -3250}
  - id: north-east
    min-x: 3000
    max-x: 3500
    min-z: -3500
    max-z: -3000
  - id: south
    min-x: -250
    max-x: 250
    min-z: 3000
    max-z: 3500
excluded-regions:
  - world: areus_s1
    min-x: -300
    max-x: 300
    min-y: -64
    max-y: 319
    min-z: -300
    max-z: 300
```

Keep `allow-chunk-generation: false`. Pregenerate destination regions, including their neighborhood, separately. Genesis uses bounded async chunk requests and never starts Chunky. Enable generation only deliberately for preparation on a test server. Destination must be a loaded Overworld; Genesis never creates or loads a world by name.

Terralith/Terratonic geography must be surveyed. The validator rejects caves, canopy, overhangs, liquids, powder snow, hazardous blocks, unstable/non-full ground, cliffs, missing headroom and disconnected group surfaces. It uses a bounded surface flood-fill, with a two-block hazard halo and a maximum one-block step between adjacent accepted columns. It is deliberately conservative; not every playable terrain will qualify. Block inspection cannot identify every custom structure or certify equal resources between regions. Use curated regions and exclusions. Biome restrictions use namespaced registry keys, including datapack keys.

## Operator sequence

Permissions: both `arion.admin` and `arion.admin.genesis` (default OP); console is supported.

Use `/arion` or `/arion help` for general command help. `/arion help genesis` and `/arion genesis help` list the Genesis commands, including recovery. Help remains available when Genesis is disabled or the core is unhealthy, subject to the same permissions.

1. Gather participants in the opening world on safe ground. They must be alive, survival, unmounted, not gliding, burning or freezing. Exclude the GM explicitly.
2. `/arion genesis validate` checks config, loaded worlds, faction membership and participant eligibility. It does **not** certify terrain.
3. `/arion genesis prepare` captures the online participants and full membership ID sets (including Saber alt players). It shuffles faction-to-region assignment once, searches safe group slots, loads/pins destination chunks and commits the assignment. Full faction membership must be at most ten; offline members are reported by roster/online counts and are not event participants.
4. `/arion genesis status` and `preview` show the session ID, mapping, coordinates and outcomes. Wait for READY with busy=false.
5. READY freezes participant translation/interactions and blocks incoming/outgoing damage. Looking around remains possible. Do not change roster, worlds, target terrain or claims. READY expires after 15 minutes by default.
6. Deliver the monologue manually. At the cue, `/arion genesis start` revalidates only stored slots and commits start intent asynchronously. It does not search for new coordinates. Lightning occurs after that acknowledgment, followed by the configured delay (40 ticks by default).
7. Visual-only lightning uses `strikeLightningEffect`. Teleports use `teleportAsync`; destination validation and dispatch are bounded. There is one dispatch per tick at most, five in-flight at most, and a persisted intent before each dispatch. Database latency can spread arrivals over additional ticks. Title appears on successful arrival; controls release after all deliveries are committed. Five seconds of arrival protection remain after collective release.

Changing destination-world does not change `world` for DeathBan. Keep both configured intentionally. DeathBan remains server-wide for login enforcement and still applies to real deaths in its configured world. Genesis never grants its bypass permission and does not clear an active ban.

## State and persistence

Normal state: `IDLE → PREPARING → READY → THUNDER → TELEPORTING → COMPLETED`; failures become FAILED or ABORTED. IDLE means no unarchived session.

`plugins/ARIONCore/genesis.db` is separate from `arion.db`, using the same bundled SQLite driver, WAL, FULL synchronization and short-lived connections. Async repository work is serialized through the Paper scheduler. Session, roster, assignments, participant status and audit are transactional. One unarchived session is enforced by a unique index. Reset archives history; it does not delete it. Do not edit database rows manually.

Persisted coordinates, seed, destination UUID and configuration fingerprint prevent silent rerandomization. World UUID or configuration changes block resume. Database/Genesis configuration failure affects Genesis only; the existing DeathBan health policy is unchanged. A Genesis storage error requires repair/restart before more commands mutate the ledger.

The database and Minecraft player position cannot commit atomically. Crash after teleport and before its receipt leaves an ambiguous delivery. Restart **never** automatically emits lightning, searches again, teleports, or assumes a successful arrival.

## Abort, reset and recovery

- `/arion genesis abort`: stop new dispatches, release movement guards/tickets, retain assignments and ledger. Already completed teleports are not rolled back. An in-flight engine operation may still finish; it remains NEEDS_REVIEW. Its temporary safety lease expires even if its future never completes.
- `/arion genesis reset`: print an actor/session/revision-bound token valid for 30 seconds. Repeat with that token to archive a stopped session. Reset is blocked while engine teleport futures remain unresolved. It is not a rollback of player positions.
- `/arion genesis recover inspect`: inspect individual delivery state and exact saved spawn.
- `/arion genesis recover resolve <uuid> <arrived|pending>`: after reviewing the actual player state, obtain a confirmation token and repeat with the token appended. `arrived` acknowledges an arrival; `pending` explicitly authorizes another attempt. The decision and actor are audited.
- `/arion genesis recover resume`: obtain a confirmation token, then repeat with it. It loads/revalidates the old mapping and resumes unfinished deliveries **without lightning**. Pending participants must be online in the opening world with unchanged faction membership. Already acknowledged arrivals are skipped. Maximum two dispatch attempts per participant across resumes.

Failed preparation with incomplete assignments cannot resume: inspect, reset/archive explicitly, then prepare. If the original configuration changed, restore it to resume, or archive and deliberately prepare a new session. A session already COMPLETED before restart should simply be inspected/archived; do not mark its participants pending.

If a teleport future never settles, inspect after a full restart rather than force-resetting while it may still move the player. Position alone is not proof of delivery: players may have moved after arrival. Preserve logs and involve the operator in ambiguous decisions.

Backup both databases with the server stopped (including associated files if a clean shutdown did not finish). Do not copy only a live WAL database's .db file.

## Compatibility boundaries

- Saber: exact existing version gate `Factions`, main `com.massivecraft.factions.FactionsPlugin`, version `1.6.9.5-4.1.9`; membership and claim lookup use verified public APIs. All claimed land, safezone and warzone are rejected; Genesis does not bypass protection. Membership changes are checked periodically and immediately before start/dispatch. Rename alone does not change the faction ID.
- WorldGuard: optional public-API bridge for the 7.x API shape, isolated from class loading when absent. All applicable regions are rejected. A `__global__` region rejects the whole world conservatively, regardless of flags. Unavailable/disabled/incompatible region manager fails preparation. Reflection/source inspection does not substitute for testing the exact installed version.
- Territory Conquest does not exist in this repository; configured exclusions supply its future protected volumes. There is no fabricated territory service.
- Geyser/Floodgate: server UUID identity and standard Adventure/Paper APIs; no Java-only packet dependency. Both client types need a staging rehearsal.
- BlueMap optional, no integration. Folia is not supported.

## Threading, performance and shutdown

All entity/world/block/claim/protection calls and session mutations run on the server thread. Worker tasks access detached records and JDBC only; callbacks check a session epoch before advancing. No `Thread.sleep`, future join/get, or custom thread pool is used. Shutdown stops intake and cancels the ticker, invalidates callbacks, removes tickets/guards, and lets any already-running short JDBC operation close its connection; there is no main-thread wait for worker I/O.

Preparation defaults: 200 candidate centers plus one optional fallback per faction, 120-second deadline, 2 concurrent chunk loads, a 15-second chunk-job timeout, 128 active ticket cap, and a cooperative 2 ms validation budget per tick. One API call cannot be preempted by that budget. Connected-column search is also bounded by the group radius. No world/block access is performed on unloaded chunks. Tickets are added only after a loaded check; aborted/stale callbacks cannot add them.

The cinematic has a 120-second overall watchdog; individual teleport futures time out after 15 seconds and are quarantined, not retried automatically. Ordinary cancelled/failed teleports require explicit recovery as well. Debug candidate logging is opt-in. Monitor host disk latency, MSPT and concurrent BlueMap/worldgen activity on the actual 4-vCPU host.

## Required staging acceptance (not claimed by unit tests)

Use the exact Paper/Saber/Vault/protection plugins and worldgen datapacks intended for production. Rehearse with Java and Bedrock clients:

1. Thirty participants / three factions: all final positions safe, groups close, every cross-faction slot pair ≥ 2000 blocks, title and release correct.
2. Confirm no health loss, fire, death event or DeathBan record from visual lightning. Check lightning rods, copper and nearby entities for unwanted changes from other plugins.
3. Run no-safe-spawn, missing world, missing Saber, factionless, over-cap roster, disband/member-change, unavailable chunk, moving border and protection-change cases. No sequence should begin on failure.
4. Trigger start twice; cancel a teleport with another plugin; disconnect before READY/start/during delivery; unload a world; stop/restart during each state. No replay or coordinate change.
5. Inject process termination before/after start intent and before/after delivery receipt. Resolve ambiguous participants manually and resume only the original mapping.
6. Profile preparation and cinematic with 30 actual clients; test disk slowdown and async exceptions. Unit tests do not establish TPS, client effects or third-party listener ordering.

Do not run the production opening until this checklist passes on the target server.
