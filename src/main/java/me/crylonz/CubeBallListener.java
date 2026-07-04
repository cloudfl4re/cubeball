package me.crylonz;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.util.Vector;

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
    public static void onPlayerJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        for (Match match : matches.values()) {
            match.replacePlayer(player);
        }
    }

    @EventHandler
    public static void onSwapItem(PlayerSwapHandItemsEvent event) {
        for (Match match : matches.values()) {
            if (match.getMatchState() == MatchState.IN_PROGRESS && match.containsPlayer(event.getPlayer())) {
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
    }

    @EventHandler
    public static void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        for (Match match : matches.values()) {
            if (match.isInProgress() && match.containsPlayer(player)) {
                event.setCancelled(true);
                player.setAllowFlight(false);
                return;
            }
        }
    }

    @EventHandler
    public static void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("cubeball.admin")) return;
        for (Match match : matches.values()) {
            if (match.isInProgress() && match.containsPlayer(player)) {
                String label = event.getMessage().trim().toLowerCase();
                if (label.startsWith("/ccb")) return;
                event.setCancelled(true);
                return;
            }
        }
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
