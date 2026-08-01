package com.github.squi2rel.cb.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

public final class FoliaScheduler {
    private static Plugin plugin;
    private static boolean folia;

    private FoliaScheduler() {
    }

    public static void init(Plugin plugin) {
        FoliaScheduler.plugin = Objects.requireNonNull(plugin, "plugin");
        FoliaScheduler.folia = detectFolia();
    }

    public static boolean isFolia() {
        plugin();
        return folia;
    }

    public static TaskHandle runGlobal(Runnable task) {
        if (folia) return handle(Bukkit.getGlobalRegionScheduler().run(plugin(), scheduledTask -> task.run()));
        return handle(Bukkit.getScheduler().runTask(plugin(), task));
    }

    public static TaskHandle runGlobalLater(Runnable task, long delayTicks) {
        long delay = normalizeTicks(delayTicks);
        if (folia) return handle(Bukkit.getGlobalRegionScheduler().runDelayed(plugin(), scheduledTask -> task.run(), delay));
        return handle(Bukkit.getScheduler().runTaskLater(plugin(), task, delay));
    }

    public static TaskHandle runGlobalTimer(Runnable task, long initialDelayTicks, long periodTicks) {
        long initialDelay = normalizeTicks(initialDelayTicks);
        long period = normalizeTicks(periodTicks);
        if (folia) {
            return handle(Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                    plugin(), scheduledTask -> task.run(), initialDelay, period));
        }
        return handle(Bukkit.getScheduler().runTaskTimer(plugin(), task, initialDelay, period));
    }

    public static TaskHandle runRegion(Location location, Runnable task) {
        if (folia) return handle(Bukkit.getRegionScheduler().run(plugin(), location, scheduledTask -> task.run()));
        return handle(Bukkit.getScheduler().runTask(plugin(), task));
    }

    public static TaskHandle runRegionLater(Location location, Runnable task, long delayTicks) {
        long delay = normalizeTicks(delayTicks);
        if (folia) return handle(Bukkit.getRegionScheduler().runDelayed(plugin(), location, scheduledTask -> task.run(), delay));
        return handle(Bukkit.getScheduler().runTaskLater(plugin(), task, delay));
    }

    public static TaskHandle runEntity(Entity entity, Runnable task) {
        return runEntity(entity, task, () -> {
        });
    }

    public static TaskHandle runEntity(Entity entity, Runnable task, Runnable retired) {
        if (folia) return handle(entity.getScheduler().run(plugin(), scheduledTask -> task.run(), retired));
        return handle(Bukkit.getScheduler().runTask(plugin(), task));
    }

    public static TaskHandle runEntityLater(Entity entity, Runnable task, long delayTicks) {
        long delay = normalizeTicks(delayTicks);
        if (folia) return handle(entity.getScheduler().runDelayed(plugin(), scheduledTask -> task.run(), () -> {
        }, delay));
        return handle(Bukkit.getScheduler().runTaskLater(plugin(), task, delay));
    }

    public static TaskHandle runEntityTimer(Entity entity, Runnable task, long initialDelayTicks, long periodTicks) {
        long initialDelay = normalizeTicks(initialDelayTicks);
        long period = normalizeTicks(periodTicks);
        if (folia) {
            return handle(entity.getScheduler().runAtFixedRate(
                    plugin(), scheduledTask -> task.run(), () -> {
                    }, initialDelay, period));
        }
        return handle(Bukkit.getScheduler().runTaskTimer(plugin(), task, initialDelay, period));
    }

    public static TaskHandle runAsync(Runnable task) {
        if (folia) return handle(Bukkit.getAsyncScheduler().runNow(plugin(), scheduledTask -> task.run()));
        return handle(Bukkit.getScheduler().runTaskAsynchronously(plugin(), task));
    }

    public static void cancelPluginTasks(Plugin plugin) {
        if (folia) {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
        }
        Bukkit.getScheduler().cancelTasks(plugin);
    }

    private static Plugin plugin() {
        if (plugin == null) {
            throw new IllegalStateException("FoliaScheduler is not initialized");
        }
        return plugin;
    }

    private static long normalizeTicks(long ticks) {
        return Math.max(1L, ticks);
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private static TaskHandle handle(io.papermc.paper.threadedregions.scheduler.ScheduledTask task) {
        return task::cancel;
    }

    private static TaskHandle handle(org.bukkit.scheduler.BukkitTask task) {
        return task::cancel;
    }
}
