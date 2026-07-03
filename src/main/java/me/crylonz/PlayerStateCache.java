package me.crylonz;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches a player's inventory, armour and flight state so it can be restored
 * after a cubeball match ends.
 */
public final class PlayerStateCache {

    private PlayerStateCache() {
    }

    private static final ConcurrentHashMap<UUID, Snapshot> cache = new ConcurrentHashMap<>();

    public static void save(Player player) {
        if (player == null) return;
        PlayerInventory inv = player.getInventory();
        Snapshot snapshot = new Snapshot();
        snapshot.contents = inv.getContents().clone();
        snapshot.armor = inv.getArmorContents().clone();
        snapshot.allowFlight = player.getAllowFlight();
        snapshot.flying = player.isFlying();
        snapshot.gameMode = player.getGameMode();
        cache.put(player.getUniqueId(), snapshot);
    }

    public static void restore(Player player) {
        if (player == null) return;
        Snapshot snapshot = cache.remove(player.getUniqueId());
        if (snapshot == null) return;
        PlayerInventory inv = player.getInventory();
        inv.setContents(snapshot.contents);
        inv.setArmorContents(snapshot.armor);
        player.setGameMode(snapshot.gameMode);
        player.setAllowFlight(snapshot.allowFlight);
        player.setFlying(snapshot.flying && snapshot.allowFlight);
        player.updateInventory();
    }

    public static void clear(Player player) {
        if (player == null) return;
        PlayerInventory inv = player.getInventory();
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        player.updateInventory();
    }

    public static boolean has(Player player) {
        return player != null && cache.containsKey(player.getUniqueId());
    }

    private static final class Snapshot {
        private ItemStack[] contents;
        private ItemStack[] armor;
        private boolean allowFlight;
        private boolean flying;
        private GameMode gameMode;
    }
}
