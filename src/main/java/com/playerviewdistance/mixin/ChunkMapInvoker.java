package com.playerviewdistance.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface ChunkMapInvoker {
    @Accessor("serverViewDistance")
    int playerviewdistance$getServerViewDistance();

    @Invoker("updateChunkTracking")
    void playerviewdistance$updateChunkTracking(ServerPlayer player);

    @Invoker("getPlayerViewDistance")
    int playerviewdistance$getPlayerViewDistance(ServerPlayer player);
}
