# Migrating to PlayerViewDistance 2.0.0

1. Stop the dedicated server and back up its `config` directory.
2. Remove every older PlayerViewDistance/CustomViewDistance JAR. Install exactly one 2.x artifact matching the server version.
3. Install Fabric Loader 0.19.3 and the matching Fabric API.
4. Start the server once. If `config/playerviewdistance.json` is absent and `customviewdistance.json` is valid, PVD writes schema 2 first and then archives the old file as `customviewdistance.json.migrated`.
5. Review `minViewDistance`, `maxViewDistance`, and `telemetryIntervalSeconds`. Removed 1.x settings are intentionally ignored: polling intervals, worker counts, instant/inner radii, and raw ticket batch sizes no longer exist.
6. Set `view-distance` to the maximum any client may receive and set `simulation-distance` to the radius where gameplay should tick. PVD does not make entities or redstone tick outside simulation distance.
7. Use `/pvd list` and `/pvd status` after players join. Existing overrides are not inferred from the old config; create persistent overrides with `/pvd set`.

Invalid schema 2 or legacy files are never rewritten during a failed load. Correct the reported validation error and run `/pvd reload`.

PVD 2 may temporarily provide less than a player's requested distant coverage while the adaptive governor responds to fresh terrain or chunk backlog. It does not intentionally retain stale coverage and never removes forced, portal, spawn, simulation, or third-party loader tickets.
