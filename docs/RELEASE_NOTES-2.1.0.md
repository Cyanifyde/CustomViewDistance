# PlayerViewDistance 2.1.0

PlayerViewDistance 2.1.0 expands the exact per-player loading design to Fabric,
Forge, NeoForge, Paper, and Folia.

## Highlights

- PVD is a composable ceiling: lower platform, world, or third-party limits are
  respected, while higher values are clamped.
- Fabric, Forge, and NeoForge use exact merged loading-only sources beyond the
  vanilla simulation floor. PVD never removes foreign tickets.
- Paper and Folia use native per-player loading and sending APIs on the owning
  entity scheduler and do not create plugin chunk tickets.
- Moonrise uses its native player loader, C2ME retains no-tick semantics, and
  VMP consumes the final tracking distance.
- Heavy planning remains off-thread with a bounded latest-state mailbox,
  generation rejection, learned work budgets, and backlog feedback.
- Synthetic and ordinary player disconnects retire PVD coverage through the
  canonical player-list removal lifecycle.
- NeoForge configuration completion is returned to the server thread before it
  can touch the initial chunk graph.
- Commands are concise, and routine startup output is only `PVD is running.`

## Artifacts

- Fabric: `playerviewdistance-2.1.0+mc1.21.11.jar`
- Fabric: `playerviewdistance-2.1.0+mc26.1.jar`
- Fabric: `playerviewdistance-2.1.0+mc26.2.jar`
- Forge: `playerviewdistance-forge-2.1.0+mc1.21.11.jar`
- Forge: `playerviewdistance-forge-2.1.0+mc26.1.jar`
- Forge: `playerviewdistance-forge-2.1.0+mc26.2.jar`
- NeoForge: `playerviewdistance-neoforge-2.1.0+mc1.21.11.jar`
- NeoForge: `playerviewdistance-neoforge-2.1.0+mc26.1.jar`
- NeoForge: `playerviewdistance-neoforge-2.1.0+mc26.2.jar`
- Paper/Folia: `playerviewdistance-paper-2.1.0+mc1.21.11.jar`
- Paper/Folia: `playerviewdistance-paper-2.1.0+mc26.x.jar`

The 26.1 mod-loader artifacts run unchanged on 26.1, 26.1.1, and 26.1.2.
Use Java 21 for Minecraft 1.21.11 and Java 25 for 26.x.

## Upgrading

Existing schema 2 configuration and UUID overrides remain valid. Install only
the artifact for the selected platform. Fabric, Forge, and NeoForge artifacts
are dedicated-server-only; install the Paper/Folia artifact as a plugin.
