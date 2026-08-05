package me.crylonz;

import com.github.squi2rel.cb.util.FoliaScheduler;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.StringReader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * 将玩家背包、盔甲、副手、飞行和游戏模式备份到文件。
 * 每人一个文件：plugins/CubeCubeBall/inv_backup/{uuid}.yml
 * restore() 恢复完毕后自动删除临时文件。
 */
public final class PlayerStateCache {

    private PlayerStateCache() {
    }

    private static final Object LIFECYCLE_LOCK = new Object();
    private static volatile Plugin plugin;
    private static volatile boolean active;
    private static volatile File backupDir;
    private static final Map<UUID, BackupRecord> backups = new ConcurrentHashMap<>();
    private static final Map<UUID, CompletableFuture<Void>> ioTails = new ConcurrentHashMap<>();
    private static final AtomicLong backupVersion = new AtomicLong(System.currentTimeMillis());
    private static final AtomicLong lifecycleGeneration = new AtomicLong();

    public static void init(Plugin p) {
        synchronized (LIFECYCLE_LOCK) {
            active = false;
            lifecycleGeneration.incrementAndGet();
            backups.clear();
            ioTails.clear();
            plugin = p;
            backupDir = new File(p.getDataFolder(), "inv_backup");
            active = true;
        }
        File directory = backupDir;
        if (!directory.exists()) {
            directory.mkdirs();
        }
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                try {
                    UUID uuid = UUID.fromString(file.getName().substring(0, file.getName().length() - 4));
                    backups.put(uuid, BackupRecord.loaded(
                            Files.readString(file.toPath(), StandardCharsets.UTF_8), backupVersion.incrementAndGet()));
                } catch (Exception ignored) {
                    p.getLogger().warning("无法读取玩家备份文件: " + file.getName());
                }
            }
        }
    }

    private static File fileFor(Player player) {
        File directory = backupDir;
        return directory == null ? null : new File(directory, player.getUniqueId() + ".yml");
    }

    /** 将玩家当前状态写入文件（已有备份则跳过，防止多轮覆盖原始背包）。 */
    public static void save(Player player) {
        saveThen(player, () -> {
        }, error -> {
        });
    }

    /**
     * 持久化完成后回到玩家 Entity 上下文执行后续操作。
     * 这保证调用方不会在备份真正落盘前清空玩家背包。
     */
    public static void saveThen(Player player, Runnable success, Consumer<Throwable> failure) {
        Plugin owner = plugin;
        if (player == null || owner == null || !active) return;
        UUID uuid = player.getUniqueId();
        long generation = lifecycleGeneration.get();
        File file = fileFor(player);
        if (file == null) return;
        String playerName = player.getName();
        BackupRecord record = backups.get(uuid);
        if (record == null) {
            record = new BackupRecord(serialize(player), backupVersion.incrementAndGet());
            BackupRecord raced = backups.putIfAbsent(uuid, record);
            if (raced != null) record = raced;
        }

        schedulePersistence(uuid, file.toPath(), playerName, record, generation, owner);
        BackupRecord expected = record;
        record.persisted.whenComplete((ignored, error) -> {
            if (!isActive(generation)) return;
            FoliaScheduler.runEntity(player, () -> {
                if (!isActive(generation)
                        || !player.isOnline() || backups.get(uuid) != expected) return;
                if (error == null) {
                    success.run();
                } else {
                    failure.accept(unwrap(error));
                }
            });
        });
    }

    private static String serialize(Player player) {
        PlayerInventory inv = player.getInventory();
        YamlConfiguration config = new YamlConfiguration();
        ItemStack[] contents = inv.getContents();
        config.set("contents-size", contents.length);
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null && !contents[i].getType().isAir()) config.set("contents." + i, contents[i]);
        }
        ItemStack[] armor = inv.getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            if (armor[i] != null && !armor[i].getType().isAir()) config.set("armor." + i, armor[i]);
        }
        ItemStack[] extra = inv.getExtraContents();
        for (int i = 0; i < extra.length; i++) {
            if (extra[i] != null && !extra[i].getType().isAir()) config.set("extra." + i, extra[i]);
        }
        config.set("allowFlight", player.getAllowFlight());
        config.set("flying", player.isFlying());
        config.set("gameMode", player.getGameMode().name());
        config.set("invisible", player.isInvisible());
        config.set("collidable", player.isCollidable());
        config.set("foodLevel", player.getFoodLevel());
        config.set("saturation", player.getSaturation());
        config.set("exhaustion", player.getExhaustion());
        Attribute scaleAttribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("scale"));
        AttributeInstance scale = scaleAttribute == null ? null : player.getAttribute(scaleAttribute);
        if (scale != null) config.set("scale", scale.getBaseValue());
        return config.saveToString();
    }

    private static void schedulePersistence(UUID uuid, Path file, String playerName, BackupRecord record,
                                            long generation, Plugin owner) {
        synchronized (record) {
            if (record.persisted != null && !record.persisted.isCompletedExceptionally()) return;
            record.persisted = enqueueIo(uuid, generation, () -> {
                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(temp, record.serialized, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                if (!isActive(generation) || backups.get(uuid) != record) {
                    Files.deleteIfExists(temp);
                    return;
                }
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            });
            record.persisted.whenComplete((ignored, error) -> {
                if (error != null && !(unwrap(error) instanceof CancellationException) && isActive(generation)) {
                    owner.getLogger().log(Level.SEVERE, "无法保存 " + playerName + " 的背包备份", unwrap(error));
                }
            });
        }
    }

    public static void shutdown() {
        synchronized (LIFECYCLE_LOCK) {
            active = false;
            lifecycleGeneration.incrementAndGet();
            for (CompletableFuture<Void> future : ioTails.values()) {
                future.cancel(false);
            }
            ioTails.clear();
            backups.clear();
            backupDir = null;
            plugin = null;
        }
    }

    /** 从文件恢复玩家状态，恢复成功后删除临时文件。 */
    public static void restore(Player player) {
        Plugin owner = plugin;
        if (player == null || owner == null || !active) return;
        long generation = lifecycleGeneration.get();
        File file = fileFor(player);
        if (file == null) return;
        UUID uuid = player.getUniqueId();
        BackupRecord record = backups.get(uuid);
        if (record == null) return;
        String serialized = record.serialized;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new StringReader(serialized));
        PlayerInventory inv = player.getInventory();

        // 恢复主背包（保留原始长度，确保槽位顺序一致）
        int size = Math.max(0, Math.min(config.getInt("contents-size", inv.getContents().length), inv.getContents().length));
        ItemStack[] contents = new ItemStack[size];
        for (int i = 0; i < size; i++) {
            contents[i] = config.getItemStack("contents." + i, null);
        }
        inv.setContents(contents);

        // 恢复盔甲槽
        ItemStack[] armor = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            armor[i] = config.getItemStack("armor." + i, null);
        }
        inv.setArmorContents(armor);

        // 恢复副手
        ItemStack[] extra = new ItemStack[inv.getExtraContents().length];
        for (int i = 0; i < extra.length; i++) {
            extra[i] = config.getItemStack("extra." + i, null);
        }
        inv.setExtraContents(extra);

        // 恢复游戏模式
        String gameModeStr = config.getString("gameMode", "SURVIVAL");
        try {
            player.setGameMode(GameMode.valueOf(gameModeStr));
        } catch (IllegalArgumentException ignored) {
            player.setGameMode(GameMode.SURVIVAL);
        }

        // 恢复飞行状态
        boolean allowFlight = config.getBoolean("allowFlight", false);
        player.setAllowFlight(allowFlight);
        player.setFlying(config.getBoolean("flying", false) && allowFlight);
        player.setInvisible(config.getBoolean("invisible", false));
        player.setCollidable(config.getBoolean("collidable", true));
        if (config.contains("foodLevel")) player.setFoodLevel(config.getInt("foodLevel"));
        if (config.contains("saturation")) player.setSaturation((float) config.getDouble("saturation"));
        if (config.contains("exhaustion")) player.setExhaustion((float) config.getDouble("exhaustion"));
        if (config.contains("scale")) {
            Attribute scaleAttribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("scale"));
            AttributeInstance scale = scaleAttribute == null ? null : player.getAttribute(scaleAttribute);
            if (scale != null) scale.setBaseValue(config.getDouble("scale"));
        }

        player.updateInventory();

        if (!backups.remove(uuid, record)) return;
        enqueueIo(uuid, generation, () -> {
            if (isActive(generation) && !backups.containsKey(uuid)) Files.deleteIfExists(file.toPath());
        }).whenComplete((ignored, error) -> {
            if (error != null && !(unwrap(error) instanceof CancellationException) && isActive(generation)) {
                owner.getLogger().log(Level.WARNING, "无法删除备份文件：" + file.getPath(), unwrap(error));
            }
        });
    }

    /** 清空玩家背包（主背包 + 盔甲 + 副手）。 */
    public static void clear(Player player) {
        if (player == null) return;
        PlayerInventory inv = player.getInventory();
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        inv.setExtraContents(new ItemStack[inv.getExtraContents().length]);
        player.updateInventory();
    }

    /** 判断该玩家是否存在备份文件。 */
    public static boolean has(Player player) {
        return player != null && has(player.getUniqueId());
    }

    public static boolean has(UUID playerId) {
        return playerId != null && active && plugin != null && backups.containsKey(playerId);
    }

    private static CompletableFuture<Void> enqueueIo(UUID uuid, long generation, IoOperation operation) {
        CompletableFuture<Void> next = new CompletableFuture<>();
        synchronized (LIFECYCLE_LOCK) {
            if (!isActive(generation)) {
                next.cancel(false);
                return next;
            }
            ioTails.compute(uuid, (ignored, tail) -> {
                CompletableFuture<Void> previous = tail == null
                        ? CompletableFuture.completedFuture(null)
                        : tail.handle((value, error) -> null);
                previous.whenComplete((value, error) -> {
                    if (!isActive(generation)) {
                        next.cancel(false);
                        return;
                    }
                    FoliaScheduler.runAsync(() -> {
                        if (!isActive(generation)) {
                            next.cancel(false);
                            return;
                        }
                        try {
                            operation.run();
                            next.complete(null);
                        } catch (Throwable throwable) {
                            next.completeExceptionally(throwable);
                        }
                    });
                });
                return next;
            });
        }
        next.whenComplete((value, error) -> ioTails.remove(uuid, next));
        return next;
    }

    private static boolean isActive(long generation) {
        return active && plugin != null && generation == lifecycleGeneration.get();
    }

    private static Throwable unwrap(Throwable error) {
        return error.getCause() == null ? error : error.getCause();
    }

    @FunctionalInterface
    private interface IoOperation {
        void run() throws Exception;
    }

    private static final class BackupRecord {
        final String serialized;
        final long version;
        volatile CompletableFuture<Void> persisted;

        BackupRecord(String serialized, long version) {
            this.serialized = serialized;
            this.version = version;
        }

        static BackupRecord loaded(String serialized, long version) {
            BackupRecord record = new BackupRecord(serialized, version);
            record.persisted = CompletableFuture.completedFuture(null);
            return record;
        }
    }
}
