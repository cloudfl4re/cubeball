package me.crylonz;

import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
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

    public static void init(Plugin p) {
        plugin = p;
        backupDir = new File(p.getDataFolder(), "inv_backup");
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }
    }

    private static File fileFor(Player player) {
        return new File(backupDir, player.getUniqueId() + ".yml");
    }

    /** 将玩家当前状态写入文件（已有备份则跳过，防止多轮覆盖原始背包）。 */
    public static void save(Player player) {
        if (player == null || plugin == null) return;
        File file = fileFor(player);
        if (file.exists()) return; // 幂等：已有备份不覆盖

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

        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE,
                    "无法保存 " + player.getName() + " 的背包备份", e);
        }
    }

    /** 从文件恢复玩家状态，恢复成功后删除临时文件。 */
    public static void restore(Player player) {
        if (player == null || plugin == null) return;
        File file = fileFor(player);
        if (!file.exists()) return;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
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

        player.updateInventory();

        // 删除临时备份文件
        if (!file.delete()) {
            plugin.getLogger().warning("无法删除备份文件：" + file.getPath());
        }
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
        return player != null && plugin != null && fileFor(player).exists();
    }
}
