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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 将玩家背包、盔甲、副手、飞行和游戏模式备份到文件。
 * 每人一个文件：plugins/CubeCubeBall/inv_backup/{uuid}.yml
 * restore() 恢复完毕后自动删除临时文件。
 */
public final class PlayerStateCache {

    private PlayerStateCache() {
    }

    private static Plugin plugin;
    private static File backupDir;
    private static final Map<UUID, String> backups = new ConcurrentHashMap<>();

    public static void init(Plugin p) {
        plugin = p;
        backupDir = new File(p.getDataFolder(), "inv_backup");
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }
        backups.clear();
        File[] files = backupDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                try {
                    UUID uuid = UUID.fromString(file.getName().substring(0, file.getName().length() - 4));
                    backups.put(uuid, Files.readString(file.toPath(), StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                    p.getLogger().warning("无法读取玩家备份文件: " + file.getName());
                }
            }
        }
    }

    private static File fileFor(Player player) {
        return new File(backupDir, player.getUniqueId() + ".yml");
    }

    /** 将玩家当前状态写入文件（已有备份则跳过，防止多轮覆盖原始背包）。 */
    public static void save(Player player) {
        if (player == null || plugin == null) return;
        UUID uuid = player.getUniqueId();
        if (backups.containsKey(uuid)) return;
        File file = fileFor(player);

        PlayerInventory inv = player.getInventory();
        YamlConfiguration config = new YamlConfiguration();

        // 主背包（含副手在部分实现里，按实际 slot 保存）
        ItemStack[] contents = inv.getContents();
        config.set("contents-size", contents.length);
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null && !contents[i].getType().isAir()) {
                config.set("contents." + i, contents[i]);
            }
        }

        // 盔甲槽（boots/leggings/chestplate/helmet，索引 0-3）
        ItemStack[] armor = inv.getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            if (armor[i] != null && !armor[i].getType().isAir()) {
                config.set("armor." + i, armor[i]);
            }
        }

        // 副手（offhand）
        ItemStack[] extra = inv.getExtraContents();
        for (int i = 0; i < extra.length; i++) {
            if (extra[i] != null && !extra[i].getType().isAir()) {
                config.set("extra." + i, extra[i]);
            }
        }

        // 状态
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

        String serialized = config.saveToString();
        String playerName = player.getName();
        if (backups.putIfAbsent(uuid, serialized) != null) return;
        FoliaScheduler.runAsync(() -> {
            try {
                if (!serialized.equals(backups.get(uuid))) return;
                Files.writeString(file.toPath(), serialized, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } catch (Exception e) {
                backups.remove(uuid, serialized);
                plugin.getLogger().log(Level.SEVERE, "无法保存 " + playerName + " 的背包备份", e);
            }
        });
    }

    /** 从文件恢复玩家状态，恢复成功后删除临时文件。 */
    public static void restore(Player player) {
        if (player == null || plugin == null) return;
        File file = fileFor(player);
        UUID uuid = player.getUniqueId();
        String serialized = backups.get(uuid);
        if (serialized == null) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new StringReader(serialized));
        PlayerInventory inv = player.getInventory();

        // 恢复主背包（保留原始长度，确保槽位顺序一致）
        int size = config.getInt("contents-size", 36);
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

        backups.remove(uuid, serialized);
        FoliaScheduler.runAsync(() -> {
            try {
                Files.deleteIfExists(file.toPath());
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "无法删除备份文件：" + file.getPath(), e);
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
        return player != null && plugin != null && backups.containsKey(player.getUniqueId());
    }
}
