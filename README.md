# PlayerViewDistance

PlayerViewDistance is a dedicated-server-only Fabric mod that honors each player's requested render distance while keeping `server.properties` `view-distance` as the hard cap. Version 2 replaces per-chunk ticket spam with exact, shared Chebyshev loading sources and a single coalescing planner thread.

## Supported releases

| Artifact | Minecraft | Java | Mappings |
| --- | --- | --- | --- |
| `playerviewdistance-2.0.1+mc1.21.11.jar` | 1.21.11 | 21 | Mojang mappings, remapped for Fabric |
| `playerviewdistance-2.0.1+mc26.1.jar` | 26.1, 26.1.1, 26.1.2 | 25 | Loom unobfuscated |
| `playerviewdistance-2.0.1+mc26.2.jar` | 26.2 | 25 | Loom unobfuscated |

Fabric Loader 0.19.3 and the matching Fabric API are required. Do not install PVD on clients.

## Behavior

For each player, the effective distance is:

```text
clamp(persistent override ?? client request,
      config min,
      config max,
      server view-distance)
```

That value is a ceiling, not a forced distance. A dimension-specific setting
or compatible load governor may lower the active distance further. PVD clamps
both chunk tracking and outgoing client-cache-radius packets, so another mod
cannot raise either path past the ceiling.

Client chunk sending and server loading are intentionally separate:

- The client receives chunks only to that player's effective distance.
- Vanilla player-loading coverage is based on `simulation-distance`, preserving the expected mob, item, redstone, block-entity, and random-tick behavior.
- PVD adds only its own loading-only ticket type beyond that simulation floor. It never creates simulation tickets or removes another mod's tickets.
- Nearby players share an exact irredundant union of square loading sources. PVD does not replace clusters with oversized bounding boxes.
- Movement and disconnects synchronously retire stale PVD sources. Under pressure, distant coverage may temporarily be incomplete; stale coverage is never deliberately retained behind a player.

Geometry, overlap indexing, exact refcounts, source matching, diffing, percentile work, prioritization, and governor calculations run on one off-thread planner. Minecraft's thread-affine ticket changes remain on the server thread and are budgeted by predicted graph-cell cost and measured nanoseconds.

## Configuration

The schema 2 file is `config/playerviewdistance.json`:

```json
{
  "schemaVersion": 2,
  "minViewDistance": 2,
  "maxViewDistance": 32,
  "governorProfile": "BALANCED",
  "telemetryIntervalSeconds": 60
}
```

Valid reloads are applied transactionally with `/pvd reload`. An invalid file is left untouched and the last-known-good configuration remains active. A valid legacy `customviewdistance.json` is migrated once and archived as `customviewdistance.json.migrated`.

Persistent UUID overrides are stored atomically in `config/playerviewdistance-overrides.json`.

## Commands

All commands require game-master permission:

- `/pvd set <player> <2..32>` — persist an override; normal config/server caps still apply.
- `/pvd reset <player>` — remove the persistent override.
- `/pvd list` — show requested, desired, and applied player distances.
- `/pvd reload` — validate and transactionally reload schema 2.
- `/pvd status` — show source/union convergence, planner generation, MSPT, chunk backlog, loaded chunks, entity counters, ticket mutations, and governor state.

## Compatibility

PVD 2 has explicit integration for C2ME's no-tick view distance and VMP's per-player area watcher. Missing required hooks fail at startup with an actionable error instead of silently changing semantics. Runtime changes to a world's view or simulation distance trigger an immediate, dimension-local re-plan without touching tickets owned by other mods.

Current compatibility targets include:

- Lithium `0.25.3+mc26.2`
- FerriteCore `9.0.0`
- C2ME `0.4.2-alpha.0.43+26.2`
- VMP `0.2.0+beta.7.236+26.2`
- ServerCore `1.5.19+26.2`
- Adaptive View `2.4.4+26.2`
- Dynamic Performance `0.2.0+26.1`
- View Distance Fix `1.0.2+26.2`
- Entity View Distance `1.9.0+26.2`
- World Specific View Distance `0.2.1+1.21.11`
- Chunk Loaders `1.2.9` with its pinned library dependencies
- Custom Dimensions `1.2.1` and Dimension Daddy `2.0.0+26.2`

The dedicated-server stacks from Adrenaline `26.4.2+mc26.2.fabric` and
Optimize My Server `26.2.0.1` are also exercised as release compatibility
targets.

## Build

The wrapper is Gradle 9.5.1 and Loom is pinned to 1.17.19. Install Java 21 and Java 25, then run:

```powershell
.\gradlew.bat clean build releaseArtifacts
```

Linux/macOS:

```bash
./gradlew clean build releaseArtifacts
```

Exactly three release binaries and three source JARs are synchronized into `build/release/`. Test verifier JARs are deliberately excluded.

## Migration and license

Read [the 2.0 migration guide](docs/MIGRATION-2.0.0.md) before replacing 1.x. PlayerViewDistance is licensed under [Apache License 2.0](LICENSE).
