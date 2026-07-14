package me.crylonz;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

public final class EmotecraftHook {
    private static Object verificationEvent;
    private static Object verifier;
    private static Method unregisterMethod;
    private static Method stopEmoteMethod;

    private EmotecraftHook() {
    }

    public static void init() {
        if (verifier != null) return;
        Plugin emotecraft = Bukkit.getPluginManager().getPlugin("emotecraft");
        if (emotecraft == null || !emotecraft.isEnabled()) return;

        try {
            ClassLoader loader = emotecraft.getClass().getClassLoader();
            Class<?> eventsClass = loader.loadClass("io.github.kosmx.emotes.api.events.server.ServerEmoteEvents");
            Class<?> verifierClass = loader.loadClass("io.github.kosmx.emotes.api.events.server.ServerEmoteEvents$EmoteVerifier");
            Class<?> resultClass = loader.loadClass("com.zigythebird.playeranimcore.event.EventResult");
            Object pass = enumValue(resultClass, "PASS");
            Object fail = enumValue(resultClass, "FAIL");

            verifier = Proxy.newProxyInstance(loader, new Class<?>[]{verifierClass}, (proxy, method, args) -> {
                if (method.getName().equals("verify")) {
                    UUID playerId = (UUID) args[1];
                    return CubeBall.isPlaying(playerId) ? fail : pass;
                }
                if (method.getName().equals("equals")) return proxy == args[0];
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("toString")) return "CubeBallEmoteVerifier";
                return null;
            });

            Field eventField = eventsClass.getField("EMOTE_VERIFICATION");
            verificationEvent = eventField.get(null);
            Method register = verificationEvent.getClass().getMethod("register", Object.class);
            unregisterMethod = verificationEvent.getClass().getMethod("unregister", Object.class);
            register.invoke(verificationEvent, verifier);

            Class<?> apiClass = loader.loadClass("io.github.kosmx.emotes.api.events.server.ServerEmoteAPI");
            Class<?> animationClass = loader.loadClass("com.zigythebird.playeranimcore.animation.Animation");
            stopEmoteMethod = apiClass.getMethod("setPlayerPlayingEmote", UUID.class, animationClass);
        } catch (ReflectiveOperationException | LinkageError exception) {
            verificationEvent = null;
            verifier = null;
            unregisterMethod = null;
            stopEmoteMethod = null;
            CubeBall.plugin.getLogger().warning("Failed to hook Emotecraft: " + exception.getMessage());
        }
    }

    public static void stopEmote(UUID playerId) {
        if (stopEmoteMethod == null || playerId == null) return;
        try {
            stopEmoteMethod.invoke(null, playerId, null);
        } catch (ReflectiveOperationException exception) {
            CubeBall.plugin.getLogger().warning("Failed to stop Emotecraft animation: " + exception.getMessage());
        }
    }

    public static void shutdown() {
        if (verificationEvent != null && verifier != null && unregisterMethod != null) {
            try {
                unregisterMethod.invoke(verificationEvent, verifier);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        verificationEvent = null;
        verifier = null;
        unregisterMethod = null;
        stopEmoteMethod = null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumValue(Class<?> type, String name) {
        return Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), name);
    }
}
