# PlayerViewDistance 2.0.1

PlayerViewDistance 2.0.1 is a compatibility and correctness update for the
2.0 architecture.

## What changed

- PVD is now a composable hard ceiling. Server load governors can lower a
  player's active view distance, but cannot raise it beyond PVD's effective
  distance.
- The same ceiling is enforced on outgoing client chunk-cache-radius packets.
- View and simulation distance are tracked per dimension, including direct
  runtime changes made by other mods.
- Distance changes remove and re-plan only PVD's private loading sources.
  Forced chunks, portal tickets, simulation coverage, and mod-owned chunk
  loader tickets are left untouched.
- Reset achieved coverage is synchronized with the coalescing planner mailbox,
  preventing a stale radius from being reused after a transactional reset.

## Artifacts

- `playerviewdistance-2.0.1+mc1.21.11.jar` — Minecraft 1.21.11, Java 21
- `playerviewdistance-2.0.1+mc26.1.jar` — Minecraft 26.1, 26.1.1, and 26.1.2, Java 25
- `playerviewdistance-2.0.1+mc26.2.jar` — Minecraft 26.2, Java 25

This remains a Fabric dedicated-server-only mod. Fabric Loader 0.19.3 and the
matching Fabric API are required.
