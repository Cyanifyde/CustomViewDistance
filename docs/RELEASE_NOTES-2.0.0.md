# PlayerViewDistance 2.0.0

PlayerViewDistance 2 is a correctness and performance rewrite for Fabric dedicated servers.

The mod now represents nearby players as an exact union of shared square loading sources instead of issuing a ticket for every chunk. A single coalescing planner performs geometry, overlap reduction, diffing, prioritization, and adaptive-governor work off-thread. The server thread only captures primitive state, retires stale sources, and applies budgeted Minecraft ticket mutations.

Vanilla simulation behavior is preserved independently of client view distance: `simulation-distance` controls ticking coverage, while each client receives terrain up to its clamped personal distance. PVD's private tickets are loading-only and never remove forced, portal, spawn, simulation, or another mod's tickets.

This release adds transactional schema 2 configuration, atomic persistent UUID overrides, `/pvd status`, C2ME no-tick and VMP adapters, and compatibility with common optimization and chunk-loading mods.

Artifacts:

- `playerviewdistance-2.0.0+mc1.21.11.jar` — Minecraft 1.21.11, Java 21
- `playerviewdistance-2.0.0+mc26.1.jar` — Minecraft 26.1, 26.1.1, and 26.1.2, Java 25
- `playerviewdistance-2.0.0+mc26.2.jar` — Minecraft 26.2, Java 25

This is a server-only mod. Read the migration guide before upgrading from 1.x.
