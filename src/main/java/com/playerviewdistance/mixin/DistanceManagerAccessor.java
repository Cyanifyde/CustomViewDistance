package com.playerviewdistance.mixin;

import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DistanceManager.class)
public interface DistanceManagerAccessor {
    @Accessor("simulationDistance")
    int playerviewdistance$getSimulationDistance();
}
