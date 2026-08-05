package com.github.squi2rel.cb.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class FoliaScheduler {
    private static final TaskHandle NOOP = () -> {
    };
    private static final Object LIFECYCLE_LOCK = new Object();
    private static final Set<TrackedTaskHandle> TRACKED_TASKS = ConcurrentHashMap.newKeySet();
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
        return schedule(task, null, false, (owner, execute, retired) -> folia
                ? handle(Bukkit.getGlobalRegionScheduler().run(owner, scheduledTask -> execute.run()))
                : handle(Bukkit.getScheduler().runTask(owner, execute)));
    }

    public static TaskHandle runGlobalLater(Runnable task, long delayTicks) {
        long delay = normalizeTicks(delayTicks);
        return schedule(task, null, false, (owner, execute, retired) -> folia
                ? handle(Bukkit.getGlobalRegionScheduler().runDelayed(owner, scheduledTask -> execute.run(), delay))
                : handle(Bukkit.getScheduler().runTaskLater(owner, execute, delay)));
    }

    public static TaskHandle runGlobalTimer(Runnable task, long initialDelayTicks, long periodTicks) {
        long initialDelay = normalizeTicks(initialDelayTicks);
        long period = normalizeTicks(periodTicks);
        return schedule(task, null, true, (owner, execute, retired) -> folia
                ? handle(Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                owner, scheduledTask -> execute.run(), initialDelay, period))
                : handle(Bukkit.getScheduler().runTaskTimer(owner, execute, initialDelay, period)));
    }

    public static TaskHandle runRegion(Location location, Runnable task) {
        return schedule(task, null, false, (owner, execute, retired) -> folia
                ? handle(Bukkit.getRegionScheduler().run(owner, location, scheduledTask -> execute.run()))
                : handle(Bukkit.getScheduler().runTask(owner, execute)));
    }

    public static TaskHandle runRegionLater(Location location, Runnable task, long delayTicks) {
        long delay = normalizeTicks(delayTicks);
        return schedule(task, null, false, (owner, execute, retired) -> folia
                ? handle(Bukkit.getRegionScheduler().runDelayed(owner, location, scheduledTask -> execute.run(), delay))
                : handle(Bukkit.getScheduler().runTaskLater(owner, execute, delay)));
    }

    public static TaskHandle runEntity(Entity entity, Runnable task) {
        return runEntity(entity, task, () -> {
        });
    }

    public static TaskHandle runEntity(Entity entity, Runnable task, Runnable retired) {
        return schedule(task, retired, false, (owner, execute, retiredTask) -> folia
                ? handle(entity.getScheduler().run(owner, scheduledTask -> execute.run(), retiredTask))
                : handle(Bukkit.getScheduler().runTask(owner, execute)));
    }

    public static TaskHandle runEntityLater(Entity entity, Runnable task, long delayTicks) {
        long delay = normalizeTicks(delayTicks);
        return schedule(task, null, false, (owner, execute, retired) -> folia
                ? handle(entity.getScheduler().runDelayed(owner, scheduledTask -> execute.run(), retired, delay))
                : handle(Bukkit.getScheduler().runTaskLater(owner, execute, delay)));
    }

    public static TaskHandle runEntityTimer(Entity entity, Runnable task, long initialDelayTicks, long periodTicks) {
        long initialDelay = normalizeTicks(initialDelayTicks);
        long period = normalizeTicks(periodTicks);
        return schedule(task, null, true, (owner, execute, retired) -> folia
                ? handle(entity.getScheduler().runAtFixedRate(
                owner, scheduledTask -> execute.run(), retired, initialDelay, period))
                : handle(Bukkit.getScheduler().runTaskTimer(owner, execute, initialDelay, period)));
    }

    public static TaskHandle runAsync(Runnable task) {
        return schedule(task, null, false, (owner, execute, retired) -> folia
                ? handle(Bukkit.getAsyncScheduler().runNow(owner, scheduledTask -> execute.run()))
                : handle(Bukkit.getScheduler().runTaskAsynchronously(owner, execute)));
    }

    public static CompletableFuture<Boolean> teleport(Entity entity, Location location) {
        synchronized (LIFECYCLE_LOCK) {
            if (!acceptingTasks || plugin == null) return CompletableFuture.completedFuture(false);
            return entity.teleportAsync(location);
        }
    }

    public static void cancelPluginTasks(Plugin plugin) {
        boolean foliaPath;
        List<TrackedTaskHandle> trackedTasks;
        synchronized (LIFECYCLE_LOCK) {
            foliaPath = folia;
            trackedTasks = detachTrackedTasks(plugin);
        }
        trackedTasks.forEach(TrackedTaskHandle::cancel);
        cancelPluginTasksInternal(plugin, foliaPath);
    }

    public static void shutdown(Plugin owner) {
        boolean foliaPath;
        List<TrackedTaskHandle> trackedTasks;
        synchronized (LIFECYCLE_LOCK) {
            acceptingTasks = false;
            foliaPath = folia;
            trackedTasks = detachTrackedTasks(owner);
            if (plugin == owner) plugin = null;
        }
        for (TrackedTaskHandle task : trackedTasks) task.cancel();
        cancelPluginTasksInternal(owner, foliaPath);
    }

    private static void cancelPluginTasksInternal(Plugin plugin, boolean foliaPath) {
        if (foliaPath) {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
            return;
        }
        Bukkit.getScheduler().cancelTasks(plugin);
    }

    private static List<TrackedTaskHandle> detachTrackedTasks(Plugin owner) {
        List<TrackedTaskHandle> tasks = new ArrayList<>();
        for (TrackedTaskHandle task : TRACKED_TASKS) {
            if (task.owner == owner && TRACKED_TASKS.remove(task)) tasks.add(task);
        }
        return tasks;
    }

    private static TaskHandle schedule(Runnable task, Runnable retired, boolean repeating, ScheduleFactory factory) {
        Objects.requireNonNull(task, "task");
        synchronized (LIFECYCLE_LOCK) {
            if (!acceptingTasks || plugin == null) return NOOP;
            Plugin owner = plugin;
            TrackedTaskHandle tracked = new TrackedTaskHandle(owner, repeating);
            TRACKED_TASKS.add(tracked);
            try {
                TaskHandle delegate = factory.schedule(owner,
                        () -> tracked.execute(task), () -> tracked.retire(retired));
                tracked.bind(delegate);
                return tracked;
            } catch (RuntimeException | Error error) {
                tracked.close(false);
                throw error;
            }
        }
    }

    private static boolean isAccepting(Plugin owner) {
        synchronized (LIFECYCLE_LOCK) {
            return acceptingTasks && plugin == owner;
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

    @FunctionalInterface
    private interface ScheduleFactory {
        TaskHandle schedule(Plugin owner, Runnable execute, Runnable retired);
    }

    private static final class TrackedTaskHandle implements TaskHandle {
        private final Plugin owner;
        private final boolean repeating;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<TaskHandle> delegate = new AtomicReference<>(NOOP);

        private TrackedTaskHandle(Plugin owner, boolean repeating) {
            this.owner = owner;
            this.repeating = repeating;
        }

        private void bind(TaskHandle task) {
            TaskHandle actual = task == null ? NOOP : task;
            delegate.set(actual);
            if (closed.get()) actual.cancel();
        }

        private void execute(Runnable task) {
            if (closed.get()) return;
            try {
                if (isAccepting(owner)) task.run();
            } finally {
                if (!repeating) close(false);
            }
        }

        private void retire(Runnable task) {
            try {
                if (!closed.get() && task != null && isAccepting(owner)) task.run();
            } finally {
                close(false);
            }
        }

        @Override
        public void cancel() {
            close(true);
        }

        private void close(boolean cancelDelegate) {
            if (!closed.compareAndSet(false, true)) return;
            TRACKED_TASKS.remove(this);
            if (cancelDelegate) delegate.get().cancel();
        }
    }
}
