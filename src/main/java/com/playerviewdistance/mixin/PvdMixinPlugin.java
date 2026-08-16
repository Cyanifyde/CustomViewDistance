package com.playerviewdistance.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.net.URL;
import java.util.List;
import java.util.Set;

/** Selects the native Moonrise player-loader path before Minecraft classes load. */
public final class PvdMixinPlugin implements IMixinConfigPlugin {
    private static final String VANILLA_LOADING_MIXIN =
            "com.playerviewdistance.mixin.ChunkMapVanillaLoadingMixin";
    private static final String NEOFORGE_CONFIGURATION_MIXIN =
            "com.playerviewdistance.mixin.NeoForgeConfigurationTaskMixin";
    private static final String NEOFORGE_CONFIGURATION_INVOKER =
            "com.playerviewdistance.mixin.ServerConfigurationPacketListenerInvoker";
    private static final String MOONRISE_HOOK =
            "ca/spottedleaf/moonrise/common/PlatformHooks.class";
    private static final String NEOFORGE_MOD_LOADER =
            "net/neoforged/fml/ModLoader.class";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (VANILLA_LOADING_MIXIN.equals(mixinClassName)) {
            return !resourcePresent(MOONRISE_HOOK);
        }

        if (NEOFORGE_CONFIGURATION_MIXIN.equals(mixinClassName)
                || NEOFORGE_CONFIGURATION_INVOKER.equals(mixinClassName)) {
            return resourcePresent(NEOFORGE_MOD_LOADER);
        }

        return true;
    }

    private static boolean resourcePresent(String resourceName) {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        URL resource = context == null ? null : context.getResource(resourceName);
        if (resource == null) {
            resource = PvdMixinPlugin.class.getClassLoader().getResource(resourceName);
        }
        return resource != null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
    }
}
