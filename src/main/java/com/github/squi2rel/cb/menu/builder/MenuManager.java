package com.github.squi2rel.cb.menu.builder;

import com.github.squi2rel.cb.util.FoliaScheduler;
import me.crylonz.VisualEffects;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class MenuManager implements Listener {
    private static final Map<UUID, MenuContainer<?>> menus = new ConcurrentHashMap<>();
    private static final Map<UUID, Consumer<String>> handlers = new ConcurrentHashMap<>();

    private MenuManager() {
    }

    public static <T> void registerMenu(Player player, MenuContext<T> menu, T argument) {
        menus.put(player.getUniqueId(), new MenuContainer<>(menu, argument));
    }

    public static void registerChatHandler(Player player, Consumer<String> handler) {
        handlers.put(player.getUniqueId(), handler);
    }

    public static boolean submitInput(Player player, String input) {
        Consumer<String> handler = handlers.remove(player.getUniqueId());
        if (handler == null) return false;
        FoliaScheduler.runEntity(player, () -> handler.accept(input));
        return true;
    }

    public static void updateArgument(Player player, Object argument) {
        MenuContainer<?> container = menus.get(player.getUniqueId());
        if (container != null) container.setArgument(argument);
    }

    public static void openMenu(Player player, Runnable defaultMenuRunner) {
        MenuContainer<?> container = menus.get(player.getUniqueId());
        if (container != null && container.isClosed) {
            container.context.send(player, container.argument);
        } else {
            defaultMenuRunner.run();
        }
    }

    public static void closeAll() {
        for (UUID uuid : menus.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;
            FoliaScheduler.runEntity(player, player::closeInventory);
        }
        handlers.clear();
        menus.clear();
    }

    public static void init(Plugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(new MenuManager(), plugin);
    }

    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        menus.remove(uuid);
        handlers.remove(uuid);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        MenuContainer<?> container = menus.get(player.getUniqueId());
        if (container == null || container.isClosed) return;
        event.setCancelled(true);
        if (event.getRawSlot() >= 0 && event.getRawSlot() < event.getView().getTopInventory().getSize()
                && event.getCurrentItem() != null && !event.getCurrentItem().getType().isAir()) {
            VisualEffects.menuClick(player);
        }
        container.context.handleClick(player, event.getRawSlot(), event.getHotbarButton(), container.argument, event.getClick());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        MenuContainer<?> container = menus.get(uuid);
        if (container == null) return;
        if (container.context.isAutoClose()) {
            menus.remove(uuid);
        } else {
            container.isClosed = true;
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        Consumer<String> handler = handlers.remove(player.getUniqueId());
        if (handler == null) return;
        event.setCancelled(true);
        String message = event.getMessage();
        FoliaScheduler.runEntity(player, () -> handler.accept(message));
    }
}
