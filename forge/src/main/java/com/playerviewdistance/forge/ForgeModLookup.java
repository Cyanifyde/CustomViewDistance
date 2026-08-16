package com.playerviewdistance.forge;

import net.minecraftforge.fml.ModList;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Resolves Forge's 1.21.11 instance API and 26.x static API once at startup. */
final class ForgeModLookup {
    private static final Method IS_LOADED;
    private static final Object RECEIVER;

    static {
        try {
            Method method = ModList.class.getMethod("isLoaded", String.class);
            if (Modifier.isStatic(method.getModifiers())) {
                IS_LOADED = method;
                RECEIVER = null;
            } else {
                Method get = ModList.class.getMethod("get");
                IS_LOADED = method;
                RECEIVER = get.invoke(null);
            }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException failure) {
            throw new IllegalStateException(
                    "PVD cannot locate Forge's mod-list API for this exact loader build", failure);
        }
    }

    private ForgeModLookup() {
    }

    static boolean isLoaded(String modId) {
        try {
            return (boolean) IS_LOADED.invoke(RECEIVER, modId);
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new IllegalStateException("PVD could not query Forge's loaded mods", failure);
        }
    }
}
