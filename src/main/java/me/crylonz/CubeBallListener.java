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
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.util.Vector;

import java.util.Locale;

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

        if (ballData == null || ballData.getBall() == null) {
            debug("landing ignored id=" + ballId + " ballData missing");
            return;
        }

        Vector velocity = ballData.getBall().getVelocity();
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
    public static void onChat(AsyncPlayerChatEvent event) {
        String message = event.getMessage().trim();
        String lower = message.toLowerCase(Locale.ROOT);
        if (!lower.equals(".p") && !lower.equals(".un") && !lower.equals(".agree") && !lower.equals(".deny")
                && !lower.equals(".rs") && !lower.startsWith(".rs ")) return;

        Player player = event.getPlayer();
        for (Match match : matches.values()) {
            if (!match.containsPlayer(player)) continue;
            event.setCancelled(true);
            if (lower.equals(".p")) {
                FoliaScheduler.runGlobal(() -> match.requestTechnicalPause(player));
                return;
            }
            if (lower.equals(".un")) {
                FoliaScheduler.runGlobal(() -> match.requestUnpauseVote(player));
                return;
            }
            if (lower.equals(".agree")) {
                FoliaScheduler.runGlobal(() -> match.voteUnpause(player, true));
                return;
            }
            if (lower.equals(".deny")) {
                FoliaScheduler.runGlobal(() -> match.voteUnpause(player, false));
                return;
            }
            if (lower.equals(".rs") || lower.startsWith(".rs ")) {
                String reason = message.length() > 3 ? message.substring(3).trim() : "";
                FoliaScheduler.runGlobal(() -> match.requestRs(player, reason));
                return;
            }
        }
    }

    @EventHandler
    public static void onPlayerJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        for (Match match : matches.values()) {
            match.replacePlayer(player);
        }
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
        cooldown.remove(event.getPlayer().getUniqueId());
        JoinSignManager.removeWaitingPlayer(event.getPlayer(), true);
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
        if (Match.isTeamKit(event.getItemDrop().getItemStack()) || JoinSignManager.isSelectorItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
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
            if (match.isInProgress() && match.containsPlayer(player)) {
                event.setCancelled(true);
                player.setAllowFlight(false);
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
        if (CubeBall.isPlaying(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(com.github.squi2rel.cb.I18n.get("match_commands_blocked"));
            return;
        }
        if (isBlockedBodySizeCommand(event.getMessage(), player)) {
            event.setCancelled(true);
            player.sendMessage(com.github.squi2rel.cb.I18n.get("match_bodysize_blocked"));
            return;
        }
        if (player.hasPermission("cubeball.admin")) return;
        String label = event.getMessage().trim().toLowerCase(Locale.ROOT);
        if (JoinSignManager.isWaiting(player)) {
            if (label.equals("/ccb") || label.startsWith("/ccb ")) return;
            event.setCancelled(true);
            return;
        }
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

    private String getBallId(EntityChangeBlockEvent event) {
        for (MetadataValue value : event.getEntity().getMetadata("ballID")) {
            if (value.getOwningPlugin() == plugin) {
                return value.asString();
            }
        }
        return null;
    }
}
