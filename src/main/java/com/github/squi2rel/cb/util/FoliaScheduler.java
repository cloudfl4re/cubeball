package com.github.squi2rel.cb.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.Objects;

public final class FoliaScheduler {
    private static final TaskHandle NOOP = () -> {
    };
    private static final Object LIFECYCLE_LOCK = new Object();
    private static Plugin plugin;
    private static boolean folia;
    private static boolean acceptingTasks;

    private FoliaScheduler() {
    }

    public static void init(Plugin plugin) {
        synchronized (LIFECYCLE_LOCK) {
            FoliaScheduler.plugin = Objects.requireNonNull(plugin, "plugin");
            FoliaScheduler.folia = detectFolia();
            FoliaScheduler.acceptingTasks = true;
        }
    }

    public static boolean isFolia() {
        synchronized (LIFECYCLE_LOCK) {
            return folia;
        }
    }

    public static TaskHandle runGlobal(Runnable task) {
        return schedule(owner -> folia
                ? handle(Bukkit.getGlobalRegionScheduler().run(owner, scheduledTask -> task.run()))
                : handle(Bukkit.getScheduler().runTask(owner, task)));
    }

    public static TaskHandle runGlobalLater(Runnable task, long delayTicks) {
        long delay = normalizeTicks(delayTicks);
        return schedule(owner -> folia
                ? handle(Bukkit.getGlobalRegionScheduler().runDelayed(owner, scheduledTask -> task.run(), delay))
                : handle(Bukkit.getScheduler().runTaskLater(owner, task, delay)));
    }

    public static TaskHandle runGlobalTimer(Runnable task, long initialDelayTicks, long periodTicks) {
        long initialDelay = normalizeTicks(initialDelayTicks);
        long period = normalizeTicks(periodTicks);
        return schedule(owner -> folia
                ? handle(Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                owner, scheduledTask -> task.run(), initialDelay, period))
                : handle(Bukkit.getScheduler().runTaskTimer(owner, task, initialDelay, period)));
    }

    public static TaskHandle runRegion(Location location, Runnable task) {
        return schedule(owner -> folia
                ? handle(Bukkit.getRegionScheduler().run(owner, location, scheduledTask -> task.run()))
                : handle(Bukkit.getScheduler().runTask(owner, task)));
    }

    public static TaskHandle runRegionLater(Location location, Runnable task, long delayTicks) {
        long delay = normalizeTicks(delayTicks);
        return schedule(owner -> folia
                ? handle(Bukkit.getRegionScheduler().runDelayed(owner, location, scheduledTask -> task.run(), delay))
                : handle(Bukkit.getScheduler().runTaskLater(owner, task, delay)));
    }

    public static TaskHandle runEntity(Entity entity, Runnable task) {
        return runEntity(entity, task, () -> {
        });
    }

    public static TaskHandle runEntity(Entity entity, Runnable task, Runnable retired) {
        return schedule(owner -> folia
                ? handle(entity.getScheduler().run(owner, scheduledTask -> task.run(), retired))
                : handle(Bukkit.getScheduler().runTask(owner, task)));
    }

    public static TaskHandle runEntityLater(Entity entity, Runnable task, long delayTicks) {
        long delay = normalizeTicks(delayTicks);
        return schedule(owner -> folia
                ? handle(entity.getScheduler().runDelayed(owner, scheduledTask -> task.run(), () -> {
                }, delay))
                : handle(Bukkit.getScheduler().runTaskLater(owner, task, delay)));
    }

    public static TaskHandle runEntityTimer(Entity entity, Runnable task, long initialDelayTicks, long periodTicks) {
        long initialDelay = normalizeTicks(initialDelayTicks);
        long period = normalizeTicks(periodTicks);
        return schedule(owner -> folia
                ? handle(entity.getScheduler().runAtFixedRate(
                owner, scheduledTask -> task.run(), () -> {
                }, initialDelay, period))
                : handle(Bukkit.getScheduler().runTaskTimer(owner, task, initialDelay, period)));
    }

    public static TaskHandle runAsync(Runnable task) {
        return schedule(owner -> folia
                ? handle(Bukkit.getAsyncScheduler().runNow(owner, scheduledTask -> task.run()))
                : handle(Bukkit.getScheduler().runTaskAsynchronously(owner, task)));
    }

    public static CompletableFuture<Boolean> teleport(Entity entity, Location location) {
        synchronized (LIFECYCLE_LOCK) {
            if (!acceptingTasks || plugin == null) return CompletableFuture.completedFuture(false);
            return entity.teleportAsync(location);
        }
    }

    public static void cancelPluginTasks(Plugin plugin) {
        synchronized (LIFECYCLE_LOCK) {
            cancelPluginTasksInternal(plugin);
        }
    }

    public static void shutdown(Plugin owner) {
        synchronized (LIFECYCLE_LOCK) {
            acceptingTasks = false;
            cancelPluginTasksInternal(owner);
            if (plugin == owner) plugin = null;
        }
    }

    private static void cancelPluginTasksInternal(Plugin plugin) {
        if (folia) {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
            return;
        }
        Bukkit.getScheduler().cancelTasks(plugin);
    }

    private static TaskHandle schedule(Function<Plugin, TaskHandle> factory) {
        synchronized (LIFECYCLE_LOCK) {
            if (!acceptingTasks || plugin == null) return NOOP;
            return factory.apply(plugin);
        }
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
        return task == null ? NOOP : task::cancel;
    }

    private static TaskHandle handle(org.bukkit.scheduler.BukkitTask task) {
        return task::cancel;
    }
}
