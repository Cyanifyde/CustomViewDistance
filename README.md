# PlayerViewDistance

PlayerViewDistance is a dedicated-server extension for Fabric, Forge, NeoForge,
Paper, and Folia. It treats each player's requested distance as a ceiling while
allowing the platform and other governors to lower the final distance further:

```text
actual distance = min(PVD ceiling, every lower platform or provider limit)
```

PVD never changes simulation distance, entity ticking, or tickets owned by
Minecraft or another mod.

## Supported releases

| Platform | Artifact | Minecraft | Java |
| --- | --- | --- | --- |
| Fabric | `playerviewdistance-2.1.0+mc1.21.11.jar` | 1.21.11 | 21 |
| Fabric | `playerviewdistance-2.1.0+mc26.1.jar` | 26.1, 26.1.1, 26.1.2 | 25 |
| Fabric | `playerviewdistance-2.1.0+mc26.2.jar` | 26.2 | 25 |
| Forge | `playerviewdistance-forge-2.1.0+mc1.21.11.jar` | 1.21.11 | 21 |
| Forge | `playerviewdistance-forge-2.1.0+mc26.1.jar` | 26.1, 26.1.1, 26.1.2 | 25 |
| Forge | `playerviewdistance-forge-2.1.0+mc26.2.jar` | 26.2 | 25 |
| NeoForge | `playerviewdistance-neoforge-2.1.0+mc1.21.11.jar` | 1.21.11 | 21 |
| NeoForge | `playerviewdistance-neoforge-2.1.0+mc26.1.jar` | 26.1, 26.1.1, 26.1.2 | 25 |
| NeoForge | `playerviewdistance-neoforge-2.1.0+mc26.2.jar` | 26.2 | 25 |
| Paper/Folia | `playerviewdistance-paper-2.1.0+mc1.21.11.jar` | 1.21.11 | 21 |
| Paper/Folia | `playerviewdistance-paper-2.1.0+mc26.x.jar` | available 26.1.x and 26.2 builds | 25 |

Fabric requires Loader 0.19.3 and the matching Fabric API. Install mod-loader
artifacts only on dedicated servers. Install the Paper/Folia artifact as a
plugin, not as a mod.

## Behavior

PVD calculates its own ceiling as:

```text
min(platform or world hard cap,
    config maximum,
    max(config minimum, persistent override ?? client request))
```

External limits compose by taking the minimum and may go below PVD's configured
minimum. PVD clamps both loading and sending, so another component cannot raise
either path past the PVD ceiling.

On Fabric, Forge, and NeoForge, vanilla player-loading coverage follows the
configured simulation distance. PVD adds only private loading-only coverage
beyond that floor. Nearby players share an exact, irredundant union of square
sources, including Minecraft's loading margin. Movement and disconnects retire
stale sources immediately. Forced, spawn, portal, simulation, and mod-owned
tickets remain independent.

Moonrise uses its native per-player loader through PVD's dedicated adapter, so
PVD creates no private ticket sources in that mode. Paper and Folia use native
per-player loading and sending APIs and never create plugin chunk tickets.

Geometry, overlap accounting, source matching, diffing, prioritization, and the
adaptive governor run on one coalescing planner thread. Only thread-affine
Minecraft mutations run on the owning server or entity scheduler. Routine
startup output is a single line: `PVD is running.`

## Configuration

Mod-loader configuration is stored under `config/`. Paper and Folia use
`plugins/PlayerViewDistance/`. Schema 2 defaults to:

```json
{
  "schemaVersion": 2,
  "minViewDistance": 2,
  "maxViewDistance": 32,
  "governorProfile": "BALANCED",
  "telemetryIntervalSeconds": 60
}
```

Reloads are transactional. Invalid files remain untouched and the last-known-good
configuration stays active. Persistent UUID overrides are written atomically to
`playerviewdistance-overrides.json`. A valid legacy `customviewdistance.json`
is migrated once.

## Commands

Administration requires game-master permission on mod loaders or
`playerviewdistance.admin` on Paper/Folia.

- `/pvd set <player> <2..32>` replies `Set to X.`
- `/pvd reset <player>` replies `Reset.`
- `/pvd reload` replies `Reloaded.` after a valid reload.
- `/pvd status` replies `Currently at X.` for a player; console uses the list view.
- `/pvd list` reports the average and up to the ten players with the highest applied distances as `Player Y has X.`

## Compatibility

PVD exposes the thread-safe `PlayerViewDistanceService` for cooperative loading
and sending limits. Multiple providers compose by minimum; the service cannot
change simulation distance. Bukkit exposes it through `ServicesManager`, while
mod loaders expose the same API through `PlayerViewDistanceApi`.

The release has dedicated integration for C2ME, VMP, and Moonrise and composes
with adaptive-distance providers such as ServerCore. Compatibility coverage also
includes Lithium, FerriteCore, ModernFix, Alternate Current, ScalableLux,
Krypton, Let Me Despawn, Chunky, common chunk loaders, and representative
server modpacks on their applicable loaders.

## Build

The wrapper is Gradle 9.5.1. Fabric Loom is pinned to 1.17.19. Install Java 21
and Java 25, then run:

```powershell
.\gradlew.bat clean build releaseArtifacts
```

Linux and macOS:

```bash
./gradlew clean build releaseArtifacts
```

Eleven production binaries and eleven source JARs are synchronized into
`build/release/`. Local verification tools, worlds, probes, reports, downloaded
mods, and publishing utilities are not part of the repository or release.

## Migration and license

Read [the 2.0 migration guide](docs/MIGRATION-2.0.0.md) before replacing 1.x.
PlayerViewDistance is licensed under [Apache License 2.0](LICENSE).
