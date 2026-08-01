package me.crylonz;

import com.github.squi2rel.cb.GoalSelectionManager;
import com.github.squi2rel.cb.util.FoliaScheduler;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.UUID;

import static java.lang.Double.max;
import static java.lang.Double.min;
import static java.lang.Math.abs;
import static me.crylonz.CubeBall.*;

public class CubeBallListener implements Listener {

    @EventHandler
    public void blockChangeEvent(EntityChangeBlockEvent e) {
        if (e.getEntityType() != EntityType.FALLING_BLOCK) return;

        String ballId = getBallId(e);
        if (ballId == null) return;

        Match match = matches.get(ballId);
        if (match == null) return;

        Ball ballData = balls.get(ballId);
        // 落地判断：CE 路径（carrierBlockData 非 null）用 BlockData 比较；
        // 原版回退（null）保留原 Material 比较，行为与改动前一致。
        BlockData carrier = ballData != null ? ballData.getCarrierBlockData() : null;
        if (carrier != null) {
            if (!e.getTo().equals(carrier)) return;
        } else {
            Material block = match.getData().cubeBallBlock;
            if (!e.getTo().equals(block)) return;
        }
        e.setCancelled(true);

        Vector velocity;
        synchronized (match) {
            if (!match.canProcessBallPhysics() || balls.get(ballId) != ballData
                    || ballData == null || ballData.getBall() == null) {
                debug("landing ignored id=" + ballId + " ballData missing or match paused");
                return;
            }

            velocity = ballData.getBall().getVelocity();
            debug("landing id=" + ballId
                    + " to=" + e.getTo()
                    + " carrier=" + (carrier == null ? "vanilla:" + match.getData().cubeBallBlock : carrier.getAsString())
                    + " loc=" + e.getEntity().getLocation()
                    + " oldVelocity=" + velocity
                    + " valid=" + ballData.getBall().isValid()
                    + " dead=" + ballData.getBall().isDead());
            double zVelocity = abs(velocity.getZ()) / 1.5;
            double xVelocity = abs(velocity.getX()) / 1.5;
            double maxZX = max(zVelocity, xVelocity);

            velocity.setY(min(maxZX, 0.5));

            ballData = respawnBall(match.getData(), ballId, e.getEntity().getLocation(), ballData.getLastVelocity());
            ballData.getBall().setVelocity(velocity);
        }

        if (abs(velocity.getX() + velocity.getY() + velocity.getZ()) <= 0.001 || velocity.getY() < 0.025) {
            ballData.getBall().setVelocity(ballData.getBall().getVelocity().zero());
            ballData.getBall().setGravity(false);
        } else {
            ballData.getBall().setGravity(true);
            if (abs(velocity.getX() + velocity.getY() + velocity.getZ()) > 0.1) {
                ballData.getBall().getWorld().playSound(ballData.getBall().getLocation(), Sound.BLOCK_WOOL_HIT, 10, 1);
            }
        }
        debug("landing complete id=" + ballId
                + " newVelocity=" + ballData.getBall().getVelocity()
                + " gravity=" + ballData.getBall().hasGravity()
                + " valid=" + ballData.getBall().isValid()
                + " dead=" + ballData.getBall().isDead());
    }

    @EventHandler
    public static void onPlayerInteract(PlayerInteractEvent event) {
        if (GoalSelectionManager.handle(event)) return;
        if (JoinSignManager.handleSelectorInteract(event)) return;
        JoinSignManager.handleSignInteract(event);
    }

    @EventHandler
    public static void onPlayerJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        UUID playerId = player.getUniqueId();
        boolean activeMatch = false;
        for (Match match : matches.values()) {
            match.replacePlayer(player);
            if ((match.isInProgress() && match.hasPlayer(player)) || match.hasActiveSpectatorState(playerId)) {
                activeMatch = true;
            }
        }
        if (!activeMatch && (PlayerStateCache.has(player) || isExiting(playerId) || hasManagedSpectatorVisibility(player))) {
            restorePlayerAndExit(player);
        }
        FoliaScheduler.runEntityLater(player, () -> {
            for (Match match : matches.values()) {
                if ((match.isInProgress() && match.hasPlayer(player)) || match.hasActiveSpectatorState(playerId)) return;
            }
            if (PlayerStateCache.has(player) || isExiting(playerId) || hasManagedSpectatorVisibility(player)) {
                restorePlayerAndExit(player);
            }
        }, 1L);
        ResidenceBossBar.refreshLater(player);
    }

    @EventHandler
    public static void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        FoliaScheduler.runEntityLater(player, () -> {
            for (Match match : matches.values()) {
                if (match.isInProgress() && match.isSpectator(player)) {
                    match.refreshSpectatorState(player);
                    return;
                }
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public static void onExhaustion(EntityExhaustionEvent event) {
        if (event.getEntity() instanceof Player player && shouldPreserveFood(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public static void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player
                && event.getFoodLevel() < player.getFoodLevel()
                && shouldPreserveFood(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public static void onWaitingLobbyMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        String residenceName = leftWaitingLobbyResidence(player, event.getFrom(), event.getTo());
        if (residenceName == null) return;
        scheduleWaitingLobbyExit(player, residenceName);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public static void onResidenceBossBarMove(PlayerMoveEvent event) {
        if (event.getTo() == null || sameBlock(event.getFrom(), event.getTo())) return;
        ResidenceBossBar.refreshLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public static void onWaitingLobbyTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        String residenceName = leftWaitingLobbyResidence(player, event.getFrom(), event.getTo());
        if (residenceName == null) return;
        scheduleWaitingLobbyExit(player, residenceName);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public static void onResidenceBossBarTeleport(PlayerTeleportEvent event) {
        ResidenceBossBar.refreshLater(event.getPlayer());
    }

    private static void scheduleWaitingLobbyExit(Player player, String residenceName) {
        FoliaScheduler.runEntityLater(player, () -> {
            if (!JoinSignManager.isWaiting(player)) return;
            if (ResidenceHook.getState(player.getLocation(), residenceName) != ResidenceHook.State.OUTSIDE) return;
            if (JoinSignManager.leaveWaitingPlayerIfPresent(player)) {
                player.sendMessage(com.github.squi2rel.cb.I18n.get("lobby_left_residence"));
            }
        }, 1L);
    }

    private static String leftWaitingLobbyResidence(Player player, Location from, Location to) {
        if (!JoinSignManager.isWaiting(player)) return null;
        if (to == null || sameBlock(from, to)) return null;

        String residenceName = CubeBall.getWaitingLobbyResidence();
        if (residenceName == null || residenceName.isBlank()) return null;
        if (ResidenceHook.getState(from, residenceName) != ResidenceHook.State.INSIDE) return null;
        if (ResidenceHook.getState(to, residenceName) != ResidenceHook.State.OUTSIDE) return null;
        return residenceName;
    }

    @EventHandler
    public static void onSwapItem(PlayerSwapHandItemsEvent event) {
        if (JoinSignManager.isSelectorItem(event.getMainHandItem()) || JoinSignManager.isSelectorItem(event.getOffHandItem())) {
            event.setCancelled(true);
            return;
        }
        for (Match match : matches.values()) {
            if (match.canUseDash() && match.containsPlayer(event.getPlayer())) {
                int cd = match.getData().dashCooldown;
                if (cd <= 0) break;
                if (!cooldown.containsKey(event.getPlayer().getUniqueId())) {
                    cooldown.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + cd * 1000L);
                    launch(event.getPlayer(), 2);
                    event.setCancelled(true);
                }
                break;
            }
        }
    }

    @EventHandler
    public static void onPlayerLeave(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        GoalSelectionManager.cancel(player);
        ResidenceBossBar.remove(player);
        cooldown.remove(player.getUniqueId());
        if (JoinSignManager.isWaiting(player)) {
            JoinSignManager.removeWaitingPlayer(player, false);
            reservePlayerExit(player);
        }
    }

    @EventHandler
    public static void onInventoryClick(InventoryClickEvent event) {
        if (Match.isTeamKit(event.getCurrentItem()) || Match.isTeamKit(event.getCursor())
                || JoinSignManager.isSelectorItem(event.getCurrentItem()) || JoinSignManager.isSelectorItem(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public static void onInventoryDrag(InventoryDragEvent event) {
        if (Match.isTeamKit(event.getOldCursor()) || JoinSignManager.isSelectorItem(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public static void onDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        // 比赛中的参赛玩家不能丢弃任何物品，防止游戏结束后物品丢失
        if (Match.isTeamKit(event.getItemDrop().getItemStack()) || JoinSignManager.isSelectorItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            return;
        }
        for (Match match : matches.values()) {
            if (match.isInProgress() && match.containsPlayer(player)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public static void onPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        // 足球载体的 pickupDelay 已设为 MAX_VALUE，正常不会到达此处；
        // 这里额外拦截：参赛玩家比赛中不能捡任何物品
        for (Match match : matches.values()) {
            if (match.isInProgress() && match.containsPlayer(player)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public static void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (JoinSignManager.isWaiting(player)) {
            event.setCancelled(true);
            player.setFlying(false);
            player.setAllowFlight(false);
            return;
        }
        for (Match match : matches.values()) {
            if (match.isInProgress() && (match.containsPlayer(player) || match.isSpectator(player))) {
                event.setCancelled(true);
                if (match.isSpectator(player)) {
                    player.setAllowFlight(true);
                    player.setFlying(true);
                } else {
                    player.setAllowFlight(false);
                }
                return;
            }
        }
    }

    @EventHandler
    public static void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (!JoinSignManager.isWaiting(event.getPlayer())) return;
        if (event.getNewGameMode() == org.bukkit.GameMode.SURVIVAL) return;
        event.setCancelled(true);
        JoinSignManager.lockWaitingState(event.getPlayer());
    }

    @EventHandler
    public static void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        boolean commandBypass = player.hasPermission("cubeball.commandbypass");
        if (isBlockedBodySizeCommand(event.getMessage(), player)) {
            event.setCancelled(true);
            player.sendMessage(com.github.squi2rel.cb.I18n.get("match_bodysize_blocked"));
            return;
        }
        if (CubeBall.isPlaying(player.getUniqueId())) {
            if (commandBypass || isAllowedPlayingCommand(event.getMessage(), player)) return;
            event.setCancelled(true);
            player.sendMessage(com.github.squi2rel.cb.I18n.get("match_commands_blocked"));
            return;
        }
        if (commandBypass || player.hasPermission("cubeball.admin")) return;
        String label = event.getMessage().trim().toLowerCase(Locale.ROOT);
        if (JoinSignManager.isWaiting(player)) {
            if (label.equals("/ccb") || label.startsWith("/ccb ")) return;
            event.setCancelled(true);
            return;
        }
    }

    private static boolean isAllowedPlayingCommand(String command, Player player) {
        String value = command.startsWith("/") ? command.substring(1) : command;
        String[] parts = value.trim().split("\\s+");
        if (parts.length < 2) return false;
        String label = parts[0].toLowerCase(Locale.ROOT);
        int namespace = label.indexOf(':');
        if (namespace >= 0) label = label.substring(namespace + 1);
        if (!label.equals("ccb")) return false;
        String subcommand = parts[1].toLowerCase(Locale.ROOT);
        if (subcommand.equals("votepause")) return player.hasPermission("cubeball.timeout");
        return player.hasPermission("cubeball.admin")
                && (subcommand.equals("pause") || subcommand.equals("resume") || subcommand.equals("end"));
    }

    @EventHandler
    public static void onServerCommand(ServerCommandEvent event) {
        if (!isBlockedBodySizeCommand(event.getCommand(), null)) return;
        event.setCancelled(true);
        event.getSender().sendMessage(com.github.squi2rel.cb.I18n.get("match_bodysize_blocked"));
    }

    @EventHandler
    public static void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equalsIgnoreCase("emotecraft")) EmotecraftHook.init();
        if (event.getPlugin().getName().equalsIgnoreCase("Residence")) ResidenceHook.retry();
    }

    private static boolean sameBlock(Location first, Location second) {
        return first.getWorld() == second.getWorld()
                && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    private static boolean isBlockedBodySizeCommand(String command, Player sender) {
        String value = command.startsWith("/") ? command.substring(1) : command;
        String[] parts = value.trim().split("\\s+");
        if (parts.length == 0) return false;
        String label = parts[0].toLowerCase(Locale.ROOT);
        int namespace = label.indexOf(':');
        if (namespace >= 0) label = label.substring(namespace + 1);
        if (!label.equals("bodysize") && !label.equals("bs") && !label.equals("size")) return false;
        if (sender != null && CubeBall.isPlaying(sender.getUniqueId())) return true;
        if (parts.length < 3) return false;
        Player target = Bukkit.getPlayerExact(parts[2]);
        return target != null && CubeBall.isPlaying(target.getUniqueId());
    }

    private static boolean shouldPreserveFood(Player player) {
        if (JoinSignManager.isWaiting(player)) return true;
        for (Match match : matches.values()) {
            if (match.isInProgress() && match.hasPlayer(player)) return true;
        }
        return false;
    }

    private String getBallId(EntityChangeBlockEvent event) {
        for (MetadataValue value : event.getEntity().getMetadata("ballID")) {
            if (value.getOwningPlugin() == plugin) {
                return value.asString();
            }
        }
        return null;
    }
}
