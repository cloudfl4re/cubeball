package me.crylonz;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

public final class VisualEffects {
    private static volatile boolean enabled;
    private static volatile boolean menuSounds;
    private static volatile boolean setupFeedback;
    private static volatile boolean lobbyFeedback;
    private static volatile boolean matchFeedback;
    private static volatile boolean ballImpact;
    private static volatile boolean ballTrail;
    private static volatile int ballTrailInterval;
    private static volatile boolean goalBurst;

    private VisualEffects() {
    }

    public static void init(Plugin plugin) {
        FileConfiguration config = plugin.getConfig();
        enabled = config.getBoolean("visuals.enabled", true);
        menuSounds = config.getBoolean("visuals.menu-sounds", true);
        setupFeedback = config.getBoolean("visuals.setup-feedback", true);
        lobbyFeedback = config.getBoolean("visuals.lobby-feedback", true);
        matchFeedback = config.getBoolean("visuals.match-feedback", true);
        ballImpact = config.getBoolean("visuals.ball-impact", true);
        ballTrail = config.getBoolean("visuals.ball-trail.enabled", true);
        ballTrailInterval = Math.max(1, config.getInt("visuals.ball-trail.interval-ticks", 4));
        goalBurst = config.getBoolean("visuals.goal-burst", true);
    }

    public static void menuClick(Player player) {
        if (!enabled || !menuSounds || player == null) return;
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.45f, 1.35f);
    }

    public static void setupSuccess(Player player) {
        if (!enabled || !setupFeedback || player == null) return;
        Location location = player.getLocation().add(0, 1.0, 0);
        player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, location, 12, 0.45, 0.55, 0.45, 0.05);
        player.playSound(location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.6f);
    }

    public static void lobbyJoin(Player player) {
        if (!enabled || !lobbyFeedback || player == null) return;
        Location location = player.getLocation().add(0, 1.0, 0);
        player.getWorld().spawnParticle(Particle.PORTAL, location, 24, 0.5, 0.8, 0.5, 0.08);
        player.playSound(location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.85f, 1.25f);
    }

    public static void lobbyChoice(Player player, Team team) {
        if (!enabled || !lobbyFeedback || player == null) return;
        Location location = player.getLocation().add(0, 1.0, 0);
        Color color = team == Team.BLUE ? Color.fromRGB(45, 125, 255)
                : team == Team.RED ? Color.fromRGB(245, 65, 65) : Color.WHITE;
        player.getWorld().spawnParticle(Particle.DUST, location, 20, 0.45, 0.65, 0.45, 0.04,
                new Particle.DustOptions(color, 1.25f));
        player.playSound(location, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.9f,
                team == Team.BLUE ? 1.25f : team == Team.RED ? 0.85f : 1.6f);
    }

    public static void countdown(Player player, int seconds) {
        if (!enabled || !matchFeedback || player == null) return;
        float pitch = Math.min(1.8f, 0.8f + (4 - Math.min(3, seconds)) * 0.25f);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, pitch);
    }

    public static void roundStart(Player player) {
        if (!enabled || !matchFeedback || player == null) return;
        Location location = player.getLocation().add(0, 1.0, 0);
        player.getWorld().spawnParticle(Particle.FLASH, location, 1);
        player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, location, 18, 0.55, 0.8, 0.55, 0.08);
        player.playSound(location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.8f);
    }

    public static void ballKick(Entity ball) {
        if (!enabled || !ballImpact || ball == null) return;
        Location location = ball.getLocation();
        ball.getWorld().spawnParticle(Particle.CRIT, location, 10, 0.25, 0.25, 0.25, 0.12);
        ball.getWorld().spawnParticle(Particle.SWEEP_ATTACK, location, 1, 0, 0, 0, 0);
        ball.getWorld().playSound(location, Sound.BLOCK_STONE_HIT, 1.2f, 1.1f);
    }

    public static void ballBounce(Entity ball) {
        if (!enabled || !ballImpact || ball == null) return;
        Location location = ball.getLocation();
        ball.getWorld().spawnParticle(Particle.CLOUD, location, 4, 0.18, 0.06, 0.18, 0.025);
        ball.getWorld().playSound(location, Sound.BLOCK_WOOL_HIT, 0.75f, 1.25f);
    }

    public static void overtime(Player player) {
        if (!enabled || !matchFeedback || player == null) return;
        Location location = player.getLocation().add(0, 1.0, 0);
        player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, location, 35, 0.8, 1.1, 0.8, 0.12);
        player.playSound(location, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f, 1.45f);
    }

    public static void matchResult(Player player, boolean winner, boolean draw) {
        if (!enabled || !matchFeedback || player == null) return;
        Location location = player.getLocation().add(0, 1.0, 0);
        if (draw) {
            player.getWorld().spawnParticle(Particle.CLOUD, location, 18, 0.65, 0.8, 0.65, 0.06);
            player.playSound(location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.9f, 0.85f);
        } else if (winner) {
            player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, location, 45, 0.75, 1.0, 0.75, 0.12);
            player.playSound(location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        } else {
            player.getWorld().spawnParticle(Particle.SMOKE, location, 16, 0.55, 0.7, 0.55, 0.04);
            player.playSound(location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.65f);
        }
    }

    public static void ballTrail(Entity ball, int physicsTick) {
        if (!enabled || !ballTrail || ball == null || physicsTick % ballTrailInterval != 0) return;
        Vector velocity = ball.getVelocity();
        if (velocity.lengthSquared() < 0.0125) return;
        Location location = ball.getLocation().add(velocity.clone().normalize().multiply(-0.35));
        ball.getWorld().spawnParticle(Particle.END_ROD, location, 1, 0.08, 0.08, 0.08, 0.005);
    }

    public static void goalBurst(Location center, Team scoringTeam) {
        if (!enabled || !goalBurst || center == null) return;
        World world = center.getWorld();
        if (world == null) return;
        Location effect = center.clone().add(0.5, 0.8, 0.5);
        Color color = scoringTeam == Team.BLUE ? Color.fromRGB(45, 125, 255) : Color.fromRGB(245, 65, 65);
        world.spawnParticle(Particle.DUST, effect, 80, 1.8, 1.2, 1.8, 0.08, new Particle.DustOptions(color, 1.6f));
        world.spawnParticle(Particle.FIREWORK, effect, 35, 1.2, 1.0, 1.2, 0.18);
        world.spawnParticle(Particle.CLOUD, effect, 22, 1.0, 0.35, 1.0, 0.05);
        world.playSound(effect, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.4f, 1.05f);
    }
}
