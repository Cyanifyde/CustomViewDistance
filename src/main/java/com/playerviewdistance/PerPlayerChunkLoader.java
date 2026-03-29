package com.playerviewdistance;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class PerPlayerChunkLoader {

    private static volatile int adminMaxViewDistance = 32;
    private static MinecraftServer server;
    private static ExecutorService workerPool;
    private static int tickCounter;

    // Per-player state (only accessed on server thread)
    private static final Map<UUID, PlayerState> players = new HashMap<>();

    // Operator overrides (UUID -> forced RD)
    private static final Map<UUID, Integer> operatorOverrides = new HashMap<>();

    // Per-dimension ref counts (only modified on server thread)
    private static final Map<ResourceKey<Level>, Long2IntOpenHashMap> refCounts = new HashMap<>();

    // Async op queue (produced by worker, consumed by server thread)
    private static final ConcurrentLinkedQueue<TicketOp> pendingOps = new ConcurrentLinkedQueue<>();

    private PerPlayerChunkLoader() {}

    // --- Lifecycle ---

    public static void init(MinecraftServer srv) {
        server = srv;
        tickCounter = 0;
        players.clear();
        operatorOverrides.clear();
        refCounts.clear();
        pendingOps.clear();

        int threads = ViewDistanceConfig.get().workerThreadCount;
        workerPool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "PVD-Worker");
            t.setDaemon(true);
            return t;
        });

        PlayerViewDistanceMod.LOGGER.info("PerPlayerChunkLoader initialized with {} worker thread(s)", threads);
    }

    public static void shutdown() {
        if (workerPool != null) {
            workerPool.shutdownNow();
            try {
                workerPool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            workerPool = null;
        }
        players.clear();
        operatorOverrides.clear();
        refCounts.clear();
        pendingOps.clear();
        server = null;
    }

    // --- Public API ---

    public static void setAdminMaxViewDistance(int vd) {
        adminMaxViewDistance = vd;
    }

    public static int getEffectiveViewDistance(ServerPlayer player) {
        UUID uuid = player.getUUID();
        Integer override = operatorOverrides.get(uuid);
        if (override != null) {
            return clampViewDistance(override);
        }
        return clampViewDistance(player.requestedViewDistance());
    }

    public static void setOperatorOverride(UUID uuid, int distance) {
        operatorOverrides.put(uuid, distance);
        // Trigger recalculation for this player if online
        if (server != null) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                handleRenderDistanceChange(player);
            }
        }
    }

    public static void removeOperatorOverride(UUID uuid) {
        operatorOverrides.remove(uuid);
        if (server != null) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                handleRenderDistanceChange(player);
            }
        }
    }

    public static boolean hasOverride(UUID uuid) {
        return operatorOverrides.containsKey(uuid);
    }

    public static int getOverride(UUID uuid) {
        return operatorOverrides.getOrDefault(uuid, -1);
    }

    public static Map<UUID, PlayerState> getPlayerStates() {
        return Map.copyOf(players);
    }

    // --- Player Events ---

    public static void onPlayerJoin(ServerPlayer player) {
        UUID uuid = player.getUUID();
        int rd = getEffectiveViewDistance(player);
        ChunkPos center = player.chunkPosition();
        ResourceKey<Level> dim = player.level().dimension();

        PlayerState state = new PlayerState(uuid, center.x(), center.z(), rd, dim);
        players.put(uuid, state);

        PlayerViewDistanceMod.LOGGER.info("Player {} joined with effective view distance {} (client: {})",
                player.getGameProfile().name(), rd, player.requestedViewDistance());

        // Immediate inner ring load (on server thread)
        int innerRd = Math.min(ViewDistanceConfig.get().instantInnerRadius, rd);
        if (innerRd >= CircleTemplate.MIN_RD) {
            int[] innerOffsets = CircleTemplate.getCircle(innerRd);
            Long2IntOpenHashMap dimRefs = getOrCreateRefMap(dim);
            for (int i = 0; i < innerOffsets.length; i += 2) {
                long packed = ChunkPos.pack(center.x() + innerOffsets[i], center.z() + innerOffsets[i + 1]);
                int oldRef = dimRefs.get(packed);
                dimRefs.put(packed, oldRef + 1);
                if (oldRef == 0) {
                    addTicketDirect(dim, center.x() + innerOffsets[i], center.z() + innerOffsets[i + 1]);
                }
            }
        }

        // Queue the rest asynchronously
        final int finalInnerRd = innerRd;
        submitWork(() -> {
            int[] fullOffsets = CircleTemplate.getCircle(rd);
            int innerR2 = finalInnerRd * finalInnerRd;
            for (int i = 0; i < fullOffsets.length; i += 2) {
                int dx = fullOffsets[i];
                int dz = fullOffsets[i + 1];
                // Skip chunks already loaded in the inner ring
                if (dx * dx + dz * dz <= innerR2 && finalInnerRd >= CircleTemplate.MIN_RD) continue;
                pendingOps.add(new TicketOp(dim, ChunkPos.pack(center.x() + dx, center.z() + dz), true));
            }
        });
    }

    public static void onPlayerLeave(ServerPlayer player) {
        UUID uuid = player.getUUID();
        PlayerState state = players.remove(uuid);
        if (state == null) return;

        PlayerViewDistanceMod.LOGGER.info("Player {} left, cleaning up {} view distance chunks",
                player.getGameProfile().name(), CircleTemplate.getChunkCount(state.rd));

        // Queue async removal
        final int cx = state.cx;
        final int cz = state.cz;
        final int rd = state.rd;
        final ResourceKey<Level> dim = state.dimension;
        submitWork(() -> {
            int[] offsets = CircleTemplate.getCircle(rd);
            for (int i = 0; i < offsets.length; i += 2) {
                pendingOps.add(new TicketOp(dim, ChunkPos.pack(cx + offsets[i], cz + offsets[i + 1]), false));
            }
        });
    }

    // --- Server Tick ---

    public static void onServerTick(MinecraftServer srv) {
        if (srv != server) return;
        tickCounter++;

        // 1. Process batched ticket operations
        processBatch();

        // 2. Periodic move check
        ViewDistanceConfig config = ViewDistanceConfig.get();
        if (tickCounter % config.moveCheckIntervalTicks == 0) {
            checkPlayerMoves();
        }

        // 3. Periodic RD poll
        if (tickCounter % config.rdPollIntervalTicks == 0) {
            checkRenderDistanceChanges();
        }
    }

    // --- Internal ---

    private static void processBatch() {
        TicketOp op;
        while ((op = pendingOps.poll()) != null) {
            Long2IntOpenHashMap dimRefs = getOrCreateRefMap(op.dimension);
            if (op.add) {
                int oldRef = dimRefs.get(op.packedPos);
                dimRefs.put(op.packedPos, oldRef + 1);
                if (oldRef == 0) {
                    addTicketDirect(op.dimension, ChunkPos.getX(op.packedPos), ChunkPos.getZ(op.packedPos));
                }
            } else {
                int oldRef = dimRefs.get(op.packedPos);
                if (oldRef <= 1) {
                    dimRefs.remove(op.packedPos);
                    removeTicketDirect(op.dimension, ChunkPos.getX(op.packedPos), ChunkPos.getZ(op.packedPos));
                } else {
                    dimRefs.put(op.packedPos, oldRef - 1);
                }
            }
        }
    }

    private static void checkPlayerMoves() {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            PlayerState state = players.get(uuid);
            if (state == null) continue;

            ChunkPos current = player.chunkPosition();
            ResourceKey<Level> currentDim = player.level().dimension();

            // Dimension change = treat as teleport
            if (!currentDim.equals(state.dimension)) {
                handleDimensionChange(player, state, currentDim, current);
                continue;
            }

            int dx = current.x() - state.cx;
            int dz = current.z() - state.cz;
            if (dx == 0 && dz == 0) continue;

            // Cardinal 1-chunk move: use pre-computed deltas
            int dir = CircleTemplate.cardinalDirection(dx, dz);
            if (dir >= 0) {
                handleCardinalMove(player, state, dir, current);
            } else {
                // Non-cardinal or multi-chunk: full diff
                handleTeleport(player, state, current, currentDim);
            }
        }
    }

    private static void handleCardinalMove(ServerPlayer player, PlayerState state, int dir, ChunkPos newCenter) {
        int rd = state.rd;
        int oldCx = state.cx;
        int oldCz = state.cz;
        ResourceKey<Level> dim = state.dimension;

        // Update state immediately
        state.cx = newCenter.x();
        state.cz = newCenter.z();

        submitWork(() -> {
            int[] entering = CircleTemplate.getEntering(dir, rd);
            int[] leaving = CircleTemplate.getLeaving(dir, rd);

            // Entering offsets are relative to OLD center, so add to old center
            for (int i = 0; i < entering.length; i += 2) {
                pendingOps.add(new TicketOp(dim, ChunkPos.pack(oldCx + entering[i], oldCz + entering[i + 1]), true));
            }
            for (int i = 0; i < leaving.length; i += 2) {
                pendingOps.add(new TicketOp(dim, ChunkPos.pack(oldCx + leaving[i], oldCz + leaving[i + 1]), false));
            }
        });
    }

    private static void handleDimensionChange(ServerPlayer player, PlayerState state,
                                               ResourceKey<Level> newDim, ChunkPos newCenter) {
        UUID uuid = player.getUUID();
        int oldCx = state.cx;
        int oldCz = state.cz;
        int rd = state.rd;
        ResourceKey<Level> oldDim = state.dimension;

        // Update state
        state.cx = newCenter.x();
        state.cz = newCenter.z();
        state.dimension = newDim;

        // Recalculate RD in case it changed
        int newRd = getEffectiveViewDistance(player);
        state.rd = newRd;

        submitWork(() -> {
            // Remove all old dimension chunks
            int[] oldOffsets = CircleTemplate.getCircle(rd);
            for (int i = 0; i < oldOffsets.length; i += 2) {
                pendingOps.add(new TicketOp(oldDim, ChunkPos.pack(oldCx + oldOffsets[i], oldCz + oldOffsets[i + 1]), false));
            }
            // Add all new dimension chunks
            int[] newOffsets = CircleTemplate.getCircle(newRd);
            for (int i = 0; i < newOffsets.length; i += 2) {
                pendingOps.add(new TicketOp(newDim,
                        ChunkPos.pack(newCenter.x() + newOffsets[i], newCenter.z() + newOffsets[i + 1]), true));
            }
        });
    }

    private static void handleTeleport(ServerPlayer player, PlayerState state, ChunkPos newCenter,
                                        ResourceKey<Level> dim) {
        int oldCx = state.cx;
        int oldCz = state.cz;
        int rd = state.rd;

        state.cx = newCenter.x();
        state.cz = newCenter.z();

        submitWork(() -> {
            int r2 = rd * rd;
            int[] offsets = CircleTemplate.getCircle(rd);

            // Compute diff: iterate full circle for both old and new
            for (int i = 0; i < offsets.length; i += 2) {
                int dx = offsets[i];
                int dz = offsets[i + 1];
                long oldPacked = ChunkPos.pack(oldCx + dx, oldCz + dz);
                long newPacked = ChunkPos.pack(newCenter.x() + dx, newCenter.z() + dz);

                // Check if old chunk is in new circle
                int newDx = (oldCx + dx) - newCenter.x();
                int newDz = (oldCz + dz) - newCenter.z();
                if (newDx * newDx + newDz * newDz > r2) {
                    // Old chunk not in new circle -> remove
                    pendingOps.add(new TicketOp(dim, oldPacked, false));
                }

                // Check if new chunk was in old circle
                int oldDx = (newCenter.x() + dx) - oldCx;
                int oldDz = (newCenter.z() + dz) - oldCz;
                if (oldDx * oldDx + oldDz * oldDz > r2) {
                    // New chunk not in old circle -> add
                    pendingOps.add(new TicketOp(dim, newPacked, true));
                }
            }
        });
    }

    private static void checkRenderDistanceChanges() {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            PlayerState state = players.get(uuid);
            if (state == null) continue;

            int newRd = getEffectiveViewDistance(player);
            if (newRd != state.rd) {
                PlayerViewDistanceMod.LOGGER.info("Player {} changed view distance: {} -> {}",
                        player.getGameProfile().name(), state.rd, newRd);
                handleRenderDistanceChange(player, state, newRd);
            }
        }
    }

    private static void handleRenderDistanceChange(ServerPlayer player) {
        PlayerState state = players.get(player.getUUID());
        if (state == null) return;
        int newRd = getEffectiveViewDistance(player);
        if (newRd != state.rd) {
            handleRenderDistanceChange(player, state, newRd);
        }
    }

    private static void handleRenderDistanceChange(ServerPlayer player, PlayerState state, int newRd) {
        int oldRd = state.rd;
        int cx = state.cx;
        int cz = state.cz;
        ResourceKey<Level> dim = state.dimension;
        state.rd = newRd;

        submitWork(() -> {
            int oldR2 = oldRd * oldRd;
            int newR2 = newRd * newRd;
            int maxRd = Math.max(oldRd, newRd);

            // Iterate over the larger circle
            int[] offsets = CircleTemplate.getCircle(maxRd);
            for (int i = 0; i < offsets.length; i += 2) {
                int dx = offsets[i];
                int dz = offsets[i + 1];
                int dist2 = dx * dx + dz * dz;
                boolean wasIn = dist2 <= oldR2;
                boolean nowIn = dist2 <= newR2;
                if (nowIn && !wasIn) {
                    pendingOps.add(new TicketOp(dim, ChunkPos.pack(cx + dx, cz + dz), true));
                } else if (wasIn && !nowIn) {
                    pendingOps.add(new TicketOp(dim, ChunkPos.pack(cx + dx, cz + dz), false));
                }
            }
        });
    }

    private static void addTicketDirect(ResourceKey<Level> dim, int chunkX, int chunkZ) {
        if (server == null) return;
        ServerLevel level = server.getLevel(dim);
        if (level == null) return;
        ServerChunkCache cache = level.getChunkSource();
        cache.addTicketWithRadius(TicketType.PLAYER_LOADING, new ChunkPos(chunkX, chunkZ), 1);
    }

    private static void removeTicketDirect(ResourceKey<Level> dim, int chunkX, int chunkZ) {
        if (server == null) return;
        ServerLevel level = server.getLevel(dim);
        if (level == null) return;
        ServerChunkCache cache = level.getChunkSource();
        cache.removeTicketWithRadius(TicketType.PLAYER_LOADING, new ChunkPos(chunkX, chunkZ), 1);
    }

    private static Long2IntOpenHashMap getOrCreateRefMap(ResourceKey<Level> dim) {
        return refCounts.computeIfAbsent(dim, k -> new Long2IntOpenHashMap());
    }

    private static int clampViewDistance(int rd) {
        ViewDistanceConfig config = ViewDistanceConfig.get();
        return Math.max(config.minViewDistance, Math.min(Math.min(config.maxViewDistance, adminMaxViewDistance), rd));
    }

    private static void submitWork(Runnable task) {
        if (workerPool != null && !workerPool.isShutdown()) {
            workerPool.submit(task);
        }
    }

    // --- Inner Types ---

    public static final class PlayerState {
        public final UUID uuid;
        public int cx;
        public int cz;
        public int rd;
        public ResourceKey<Level> dimension;

        public PlayerState(UUID uuid, int cx, int cz, int rd, ResourceKey<Level> dimension) {
            this.uuid = uuid;
            this.cx = cx;
            this.cz = cz;
            this.rd = rd;
            this.dimension = dimension;
        }
    }

    private record TicketOp(ResourceKey<Level> dimension, long packedPos, boolean add) {}
}
