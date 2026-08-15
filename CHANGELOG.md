# Changelog

## 2.0.0

### Correctness

- Separate per-player client tracking from server loading.
- Make `server.properties` view distance the hard cap and preserve persistent override precedence.
- Move vanilla global player-loading coverage to the simulation distance so mobs, items, redstone, block entities, and random ticks retain vanilla semantics.
- Add an identity-distinct, loading-only private ticket type and remove only that type.
- Use square sources with Minecraft's two-cell loading margin and exact deterministic overlap reduction.
- Remove stale PVD sources synchronously on movement, disconnect, dimension change, reload, and shutdown.
- Refresh chunk tracking immediately after client options, overrides, cap changes, and reloads.

### Performance

- Replace circular per-chunk tickets, the executor pool, and growing operation queues with a latest-state UUID mailbox, one coalescing planner, generation rejection, and one replaceable result.
- Move spatial indexing, union refcounts, matching, diffing, percentiles, and prioritization off-thread.
- Add adaptive graph-cell/nanosecond budgeting, backlog pressure feedback, and weighted deficit round-robin growth.
- Preserve achieved coverage across source movement and account for coverage supplied by merged sources.

### Operations

- Add schema 2 transactional configuration, validated legacy migration, and atomic persistent UUID overrides.
- Add `/pvd status` diagnostics.
- Add explicit C2ME no-tick and VMP adapters plus Chunk Loaders coexistence support.
- Add Java 21 Minecraft 1.21.11 and Java 25 Minecraft 26.1–26.2 artifacts.

### Breaking changes

- Remove 1.x polling, worker-count, inner-radius, and raw batch settings.
- Drop older Minecraft versions. The supported server matrix is 1.21.11, 26.1, 26.1.1, 26.1.2, and 26.2.
