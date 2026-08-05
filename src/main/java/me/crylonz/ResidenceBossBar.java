package me.crylonz;

import com.github.squi2rel.cb.util.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ResidenceBossBar {
    private static final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();

    private ResidenceBossBar() {
    }

    public static void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            FoliaScheduler.runEntity(player, () -> refresh(player));
        }
    }

    public static void refreshLater(Player player) {
        if (player == null || !player.isOnline()) return;
        FoliaScheduler.runEntityLater(player, () -> refresh(player), 1L);
    }

    public static void refreshAll() {
        tick();
    }

    public static void refresh(Player player) {
        if (player == null || !player.isOnline()) {
            remove(player);
            return;
        }

        String residenceName = CubeBall.getWaitingLobbyResidence();
        if (residenceName == null || residenceName.isBlank()
                || ResidenceHook.getState(player.getLocation(), residenceName) != ResidenceHook.State.INSIDE) {
            remove(player);
            return;
        }

        Match match = getActiveMatch(player);
        if (match == null) {
            remove(player);
            return;
        }

        BossBar bar = bars.computeIfAbsent(player.getUniqueId(), ignored -> Bukkit.createBossBar("", BarColor.WHITE, BarStyle.SOLID));
        String title = ChatColor.translateAlternateColorCodes('&', "&c" + CubeBall.getBossBarRedTeam()
                + " &e" + match.getRedScore() + " &b- &b" + CubeBall.getBossBarBlueTeam() + " &c" + match.getBlueScore());
        if (!title.equals(bar.getTitle())) bar.setTitle(title);
        bar.setProgress(1.0D);
        if (!bar.getPlayers().contains(player)) bar.addPlayer(player);
    }

    public static void remove(Player player) {
        if (player == null) return;
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) bar.removeAll();
    }

    public static void shutdown() {
        bars.clear();
    }

    private static Match getActiveMatch(Player player) {
        for (Match match : CubeBall.matches.values()) {
            if (match.isInProgress() && match.hasPlayer(player)) return match;
        }

        Location playerLocation = player.getLocation();
        Match closestMatch = null;
        double closestDistance = Double.MAX_VALUE;
        for (Match match : CubeBall.matches.values()) {
            if (!match.isInProgress()) continue;
            Location ballSpawn = match.getConfigSnapshot().ballSpawn();
            if (ballSpawn == null || ballSpawn.getWorld() != playerLocation.getWorld()) continue;
            double distance = playerLocation.distanceSquared(ballSpawn);
            if (distance < closestDistance) {
                closestDistance = distance;
                closestMatch = match;
            }
        }
        return closestMatch;
    }
}
