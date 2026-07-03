package com.github.squi2rel.cb.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

public final class FoliaScheduler {
    private static Plugin plugin;

    private FoliaScheduler() {
    }

    public static void init(Plugin plugin) {
        FoliaScheduler.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public static ScheduledTask runGlobal(Runnable task) {
        return Bukkit.getGlobalRegionScheduler().run(plugin(), scheduledTask -> task.run());
    }

    public static ScheduledTask runGlobalLater(Runnable task, long delayTicks) {
        return Bukkit.getGlobalRegionScheduler().runDelayed(plugin(), scheduledTask -> task.run(), normalizeTicks(delayTicks));
    }

    public static ScheduledTask runGlobalTimer(Runnable task, long initialDelayTicks, long periodTicks) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin(),
                scheduledTask -> task.run(),
                normalizeTicks(initialDelayTicks),
                normalizeTicks(periodTicks)
        );
    }

    public static ScheduledTask runRegion(Location location, Runnable task) {
        return Bukkit.getRegionScheduler().run(plugin(), location, scheduledTask -> task.run());
    }

    public static ScheduledTask runRegionLater(Location location, Runnable task, long delayTicks) {
        return Bukkit.getRegionScheduler().runDelayed(plugin(), location, scheduledTask -> task.run(), normalizeTicks(delayTicks));
    }

    public static ScheduledTask runEntity(Entity entity, Runnable task) {
        return runEntity(entity, task, () -> {
        });
    }

    public static ScheduledTask runEntity(Entity entity, Runnable task, Runnable retired) {
        return entity.getScheduler().run(plugin(), scheduledTask -> task.run(), retired);
    }

    public static ScheduledTask runEntityLater(Entity entity, Runnable task, long delayTicks) {
        return entity.getScheduler().runDelayed(plugin(), scheduledTask -> task.run(), () -> {
        }, normalizeTicks(delayTicks));
    }

    public static ScheduledTask runEntityTimer(Entity entity, Runnable task, long initialDelayTicks, long periodTicks) {
        return entity.getScheduler().runAtFixedRate(
                plugin(),
                scheduledTask -> task.run(),
                () -> {
                },
                normalizeTicks(initialDelayTicks),
                normalizeTicks(periodTicks)
        );
    }

    public static void cancelPluginTasks(Plugin plugin) {
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        Bukkit.getAsyncScheduler().cancelTasks(plugin);
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
}
