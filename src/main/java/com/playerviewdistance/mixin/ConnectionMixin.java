package com.playerviewdistance.mixin;

import com.playerviewdistance.compat.ConnectionDistanceCeiling;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Connection.class)
public abstract class ConnectionMixin implements ConnectionDistanceCeiling {
    @Unique
    private volatile int playerviewdistance$viewDistanceCeiling = 32;

    @Override
    public int playerviewdistance$getViewDistanceCeiling() {
        return this.playerviewdistance$viewDistanceCeiling;
    }

    @Override
    public void playerviewdistance$setViewDistanceCeiling(int distance) {
        this.playerviewdistance$viewDistanceCeiling = Math.max(2, Math.min(32, distance));
    }

    @ModifyVariable(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private Packet<?> playerviewdistance$enforceCacheRadiusCeiling(Packet<?> packet) {
        if (!(packet instanceof ClientboundSetChunkCacheRadiusPacket radiusPacket)) {
            return packet;
        }
        int radius = Math.max(2, Math.min(
                radiusPacket.getRadius(), this.playerviewdistance$viewDistanceCeiling));
        return radius == radiusPacket.getRadius()
                ? packet
                : new ClientboundSetChunkCacheRadiusPacket(radius);
    }
}
