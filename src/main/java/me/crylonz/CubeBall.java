package me.crylonz;

import com.github.squi2rel.cb.CCBCommand;
import com.github.squi2rel.cb.I18n;
import com.github.squi2rel.cb.MatchData;
import com.github.squi2rel.cb.menu.builder.MenuManager;
import com.github.squi2rel.cb.util.FoliaScheduler;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bstats.bukkit.Metrics;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.Math.abs;
import static me.crylonz.MatchState.*;

public class CubeBall extends JavaPlugin {
    public static Plugin plugin;

    public static Map<String, Ball> balls = new ConcurrentHashMap<>();
    public static Map<String, Match> matches = new ConcurrentHashMap<>();
    public static Map<UUID, Long> cooldown = new ConcurrentHashMap<>();
    private static final Set<String> appearanceWarnings = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Integer> exitGenerations = new ConcurrentHashMap<>();
    private static final Material ITEM_BALL_CARRIER = Material.WHITE_WOOL;
    private static final double ROLL_DEGREES_PER_BLOCK = 120.0;
    private static final double GOALKEEPER_GOAL_RADIUS = 3.0;
    private static final double GOALKEEPER_DIRECT_KICK_DISTANCE = 3.0;
    private static final double GOALKEEPER_ALIGNED_KICK_DISTANCE = 4.2;
    private static final double MAX_KICK_VERTICAL_REACH = 2.6;

    public static int maxMatchPerPlayer;
    public static boolean debugMode;
    public static boolean ballGlow;
    public static boolean ballRollEnabled;
    public static double ballRollSpeed;
    private static volatile Location lobbySpawn;
    private static volatile Location exitSpawn;
    private static volatile String waitingLobbyResidence = "zqc";
    private static final String DEFAULT_BOSS_BAR_RED_TEAM = "\u7ea2\u961f";
    private static final String DEFAULT_BOSS_BAR_BLUE_TEAM = "\u84dd\u961f";
    private static volatile String bossBarRedTeam = DEFAULT_BOSS_BAR_RED_TEAM;
    private static volatile String bossBarBlueTeam = DEFAULT_BOSS_BAR_BLUE_TEAM;
    public static void generateBall(MatchData data, String id, Location location, Vector lastVelocity) {
        if (balls.get(id) != null) {
            throw new IllegalStateException("Same ID cannot be put on the same ball");
        }
        debug("generateBall id=" + id + " loc=" + formatLocation(location) + " lastVelocity=" + formatVector(lastVelocity));
        spawnBall(data, id, location, lastVelocity, null);
    }

    public static Ball respawnBall(MatchData data, String id, Location location, Vector lastVelocity) {
        Ball previous = balls.remove(id);
        debug("respawnBall id=" + id + " loc=" + formatLocation(location)
                + " previous=" + describeBall(previous)
                + " lastVelocity=" + formatVector(lastVelocity));
        if (previous != null) {
            previous.cancelPhysicsTask();
        }

        Ball next = spawnBall(data, id, location, lastVelocity, null);

        if (previous != null) {
            Entity previousCarrier = previous.getBall();
            if (previousCarrier != null) {
                FoliaScheduler.runEntity(previousCarrier, previousCarrier::remove);
            }
            if (previous.getDisplay() != null) {
                Display oldDisplay = previous.getDisplay();
                FoliaScheduler.runEntity(oldDisplay, oldDisplay::remove);
            }
        }

        return next;
    }

    private static Ball spawnBall(MatchData data, String id, Location location, Vector lastVelocity, Display reusableDisplay) {
        BallAppearance appearance = resolveAppearance(data);

        BlockData blockData = appearance.getCarrierBlockData();
        debug("spawnBall id=" + id
                + " loc=" + formatLocation(location)
                + " blockData=" + (blockData == null ? "null" : blockData.getAsString())
                + " itemMode=" + appearance.isItemDisplayMode()
                + " item=" + describeItem(appearance.getDisplayItem())
                + " reusableDisplay=" + describeEntity(reusableDisplay));
        Entity carrier;
        if (appearance.isItemDisplayMode()) {
            // 优先用 CE 空白物品作载体（视觉透明），CE 不可用则回退 BARRIER + 不可见
            ItemStack carrierItem = new ItemStack(Material.BARRIER);
            if (CraftEngineHook.isAvailable()) {
                ItemStack ceBlank = CraftEngineHook.buildCustomItemIcon("server_img_library:litesignin_air");
                if (ceBlank != null) carrierItem = ceBlank;
            }
            final ItemStack finalCarrierItem = carrierItem;
            Item item = Objects.requireNonNull(location.getWorld()).dropItem(location, finalCarrierItem, dropped -> {
                dropped.setMetadata("ballID", new FixedMetadataValue(plugin, id));
                dropped.setPickupDelay(Integer.MAX_VALUE);
                dropped.setCanPlayerPickup(false);
                dropped.setCanMobPickup(false);
                dropped.setWillAge(false);
                dropped.setUnlimitedLifetime(true);
                dropped.setInvulnerable(true);
                dropped.setGravity(true);
                dropped.setSilent(true);
                dropped.setInvisible(true);
                dropped.setVelocity(new Vector(0, 0, 0));
            });
            carrier = item;
        } else {
            FallingBlock block = Objects.requireNonNull(location.getWorld()).spawnFallingBlock(location, blockData);
            block.setMetadata("ballID", new FixedMetadataValue(plugin, id));
            block.setDropItem(false);
            block.setInvulnerable(true);
            carrier = block;
        }

        ItemDisplay display = null;
        if (appearance.isItemDisplayMode()) {
            display = (ItemDisplay) location.getWorld().spawnEntity(location, EntityType.ITEM_DISPLAY);
            display.setItemStack(appearance.getDisplayItem());
            // HEAD transform 使用物品的 head display 设置，避免 NONE 带来的等轴视角旋转
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
            display.setRotation(0, 0);
            display.setInvulnerable(true);
            display.setGlowing(ballGlow);
            carrier.addPassenger(display);
        } else {
            carrier.setGlowing(ballGlow);
        }

        Ball ball = new Ball();
        ball.setId(id);
        ball.setBall(carrier);
        ball.setDisplay(display);
        // carrierBlockData 非 null 表示走 CE 方块路径，监听器用 BlockData 比较；
        // item mode 和原版回退保持 null
        ball.setCarrierBlockData(appearance.isCustomMode() && !appearance.isItemDisplayMode() ? blockData : null);

        if (lastVelocity != null) {
            ball.getLastVelocity().setX(0);
            ball.getLastVelocity().setZ(0);
        }

        ball.setPlayerCollisionTick(0);
        balls.put(id, ball);
        ball.setPhysicsTask(FoliaScheduler.runEntityTimer(carrier, () -> tickBall(id), 1, 2));
        debug("spawnBall done id=" + id + " carrier=" + describeEntity(carrier) + " display=" + describeEntity(display));
        return ball;
    }

    /**
     * 解析足球外观：ballCustomId 为空 → 原版 cubeBallBlock 的 BlockData，无显示实体；
     * 否则尝试 CE 解析，命中用 CE 结果，未命中/CE 未装回退原版并 warn 一次。
     */
    private static BallAppearance resolveAppearance(MatchData data) {
        debug("resolveAppearance customId=" + data.ballCustomId
                + " customItem=" + describeItem(data.ballCustomItem)
                + " carrier=" + data.cubeBallBlock);
        if (data.ballCustomItem != null && !data.ballCustomItem.getType().isAir()) {
            ItemStack item = data.ballCustomItem.clone();
            item.setAmount(1);
            debug("resolveAppearance using saved ItemStack snapshot item=" + describeItem(item)
                    + " carrier=" + ITEM_BALL_CARRIER);
            return new BallAppearance(Bukkit.createBlockData(ITEM_BALL_CARRIER), item, true);
        }

        String customId = data.ballCustomId;
        if (customId != null && !customId.isEmpty()) {
            if (CraftEngineHook.isAvailable()) {
                BallAppearance app = CraftEngineHook.resolve(customId, data.cubeBallBlock);
                if (app != null) {
                    debug("resolveAppearance using CraftEngine id=" + customId
                            + " itemMode=" + app.isItemDisplayMode()
                            + " item=" + describeItem(app.getDisplayItem()));
                    if (app.isItemDisplayMode()) {
                        return new BallAppearance(Bukkit.createBlockData(ITEM_BALL_CARRIER), app.getDisplayItem(), true);
                    }
                    return app;
                }
                warnAppearanceOnce("missing:" + customId, "CraftEngine custom id not found: " + customId + ", falling back to " + data.cubeBallBlock);
            } else {
                warnAppearanceOnce("no-ce:" + customId, "ballCustomId is set (" + customId + ") but CraftEngine is not installed, falling back to " + data.cubeBallBlock);
            }
        }
        return new BallAppearance(Bukkit.createBlockData(data.cubeBallBlock), null, false);
    }

    private static void warnAppearanceOnce(String key, String message) {
        if (plugin != null && appearanceWarnings.add(key)) {
            plugin.getLogger().warning(message);
        }
    }

    public static void destroyBall(String id) {
        Ball ballData = balls.remove(id);
        debug("destroyBall id=" + id + " ball=" + describeBall(ballData));
        if (ballData != null) {
            ballData.cancelPhysicsTask();
            Entity carrier = ballData.getBall();
            if (carrier != null) {
                FoliaScheduler.runEntity(carrier, carrier::remove);
            }
            Display display = ballData.getDisplay();
            if (display != null) {
                FoliaScheduler.runEntity(display, display::remove);
            }
        }
    }

    public void onEnable() {
        plugin = this;
        FoliaScheduler.init(this);
        PlayerStateCache.init(this);

        saveDefaultConfig();

        debugMode = getConfig().getBoolean("debug", false);
        ballGlow = getConfig().getBoolean("ball.glow", true);
        ballRollEnabled = getConfig().getBoolean("ball.roll.enabled", true);
        ballRollSpeed = getConfig().getDouble("ball.roll.speed", 1.0);
        lobbySpawn = getConfig().getSerializable("lobbySpawn", Location.class);
        exitSpawn = getConfig().getSerializable("exitSpawn", Location.class);
        String residenceName = getConfig().getString("waitingLobby.residence", "zqc");
        waitingLobbyResidence = residenceName == null ? "" : residenceName.trim();
        bossBarRedTeam = normalizeBossBarTeamName(getConfig().getString("bossbar.redteam"), DEFAULT_BOSS_BAR_RED_TEAM);
        bossBarBlueTeam = normalizeBossBarTeamName(getConfig().getString("bossbar.blueteam"), DEFAULT_BOSS_BAR_BLUE_TEAM);
        if (!waitingLobbyResidence.isEmpty()) {
            ResidenceHook.init();
            if (!ResidenceHook.isAvailable()) {
                getLogger().warning("Waiting lobby residence checks are disabled: " + ResidenceHook.getFailure());
            }
        }

        String lang = getConfig().getString("language", "en");
        I18n.init(this, lang);

        MenuManager.init(this);

        ConfigurationSection section = getConfig().getConfigurationSection("matches");
        if (section == null) section = new MemoryConfiguration();
        for (String key : Objects.requireNonNull(section).getKeys(false)) {
            matches.put(key, new Match(key, MatchData.from(section.getConfigurationSection(key))));
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            FoliaScheduler.runEntity(player, () -> {
                if (PlayerStateCache.has(player)) restorePlayerAndExit(player);
            });
        }

        maxMatchPerPlayer = getConfig().getInt("maxMatchPerPlayer", 3);

        getServer().getPluginManager().registerEvents(new CubeBallListener(), this);
        EmotecraftHook.init();

        new Metrics(this, 17634);

        launchRepeatingTask();

        CCBCommand ccbCommand = new CCBCommand();
        Objects.requireNonNull(getCommand("ccb")).setExecutor(ccbCommand);
        Objects.requireNonNull(getCommand("ccb")).setTabCompleter(ccbCommand);
    }

    public void onDisable() {
        ResidenceBossBar.shutdown();
        MenuManager.closeAll();
        JoinSignManager.shutdown();
        EmotecraftHook.shutdown();
        save();

        balls.forEach((key, value) -> {
            if (value.getBall() != null) {
                destroyBall(key);
            }
        });
        FoliaScheduler.cancelPluginTasks(this);
    }

    public static void save() {
        ConfigurationSection section = new MemoryConfiguration();
        for (Map.Entry<String, Match> match : matches.entrySet()) {
            MemoryConfiguration m = new MemoryConfiguration();
            match.getValue().getData().write(m);
            section.set(match.getKey(), m);
        }
        plugin.getConfig().set("matches", section);
        plugin.saveConfig();
    }

    public static void setDebugMode(boolean enabled) {
        debugMode = enabled;
        if (plugin != null) {
            plugin.getConfig().set("debug", enabled);
            plugin.saveConfig();
            plugin.getLogger().info("Debug mode " + (enabled ? "enabled" : "disabled"));
        }
    }

    public static void setBallGlow(boolean enabled) {
        ballGlow = enabled;
        if (plugin != null) {
            plugin.getConfig().set("ball.glow", enabled);
            plugin.saveConfig();
            balls.values().forEach(ballData -> {
                Entity carrier = ballData.getBall();
                Display display = ballData.getDisplay();
                if (display != null) {
                    FoliaScheduler.runEntity(display, () -> display.setGlowing(enabled));
                } else if (carrier != null) {
                    FoliaScheduler.runEntity(carrier, () -> carrier.setGlowing(enabled));
                }
            });
            plugin.getLogger().info("Ball glow " + (enabled ? "enabled" : "disabled"));
        }
    }

    public static void setBallRollEnabled(boolean enabled) {
        ballRollEnabled = enabled;
        if (plugin != null) {
            plugin.getConfig().set("ball.roll.enabled", enabled);
            plugin.saveConfig();
            plugin.getLogger().info("Ball roll " + (enabled ? "enabled" : "disabled"));
        }
    }

    public static void setBallRollSpeed(double speed) {
        ballRollSpeed = Math.max(0.0, speed);
        if (plugin != null) {
            plugin.getConfig().set("ball.roll.speed", ballRollSpeed);
            plugin.saveConfig();
            plugin.getLogger().info("Ball roll speed set to " + ballRollSpeed);
        }
    }

    public static Location getLobbySpawn() {
        Location spawn = lobbySpawn;
        return spawn == null ? null : spawn.clone();
    }

    public static String getWaitingLobbyResidence() {
        return waitingLobbyResidence;
    }

    public static String getBossBarRedTeam() {
        return bossBarRedTeam;
    }

    public static String getBossBarBlueTeam() {
        return bossBarBlueTeam;
    }

    public static void setBossBarRedTeam(String name) {
        bossBarRedTeam = normalizeBossBarTeamName(name, DEFAULT_BOSS_BAR_RED_TEAM);
        saveBossBarTeam("bossbar.redteam", bossBarRedTeam);
    }

    public static void setBossBarBlueTeam(String name) {
        bossBarBlueTeam = normalizeBossBarTeamName(name, DEFAULT_BOSS_BAR_BLUE_TEAM);
        saveBossBarTeam("bossbar.blueteam", bossBarBlueTeam);
    }

    private static String normalizeBossBarTeamName(String name, String fallback) {
        if (name == null) return fallback;
        String value = name.trim();
        return value.isEmpty() ? fallback : value;
    }

    private static void saveBossBarTeam(String path, String name) {
        if (plugin != null) {
            plugin.getConfig().set(path, name);
            plugin.saveConfig();
        }
        ResidenceBossBar.refreshAll();
    }

    public static void setLobbySpawn(Location location) {
        lobbySpawn = location == null ? null : location.clone();
        if (plugin != null) {
            plugin.getConfig().set("lobbySpawn", lobbySpawn);
            plugin.saveConfig();
        }
    }

    public static Location getExitSpawn() {
        Location spawn = exitSpawn;
        return spawn == null ? null : spawn.clone();
    }

    public static void setExitSpawn(Location location) {
        exitSpawn = location == null ? null : location.clone();
        if (plugin != null) {
            plugin.getConfig().set("exitSpawn", exitSpawn);
            plugin.saveConfig();
        }
    }

    public static void restorePlayerAndExit(Player player) {
        UUID playerId = player.getUniqueId();
        int exitToken = exitGenerations.merge(playerId, 1, Integer::sum);
        Location spawn = getExitSpawn();
        if (spawn == null) {
            restorePlayerState(player, exitToken);
            return;
        }
        teleportForExit(player, spawn, 1, exitToken);
    }

    public static boolean isExiting(UUID playerId) {
        return playerId != null && exitGenerations.containsKey(playerId);
    }

    public static void reservePlayerExit(Player player) {
        if (player != null) reservePlayerExit(player.getUniqueId());
    }

    public static void reservePlayerExit(UUID playerId) {
        if (playerId != null) exitGenerations.putIfAbsent(playerId, 0);
    }

    private static void teleportForExit(Player player, Location spawn, int retries, int exitToken) {
        try {
            player.teleportAsync(spawn).whenComplete((success, error) -> {
                if (!Objects.equals(exitGenerations.get(player.getUniqueId()), exitToken)) return;
                if (Boolean.TRUE.equals(success)) {
                    FoliaScheduler.runEntity(player, () -> restorePlayerState(player, exitToken));
                } else if (retries > 0 && player.isOnline()) {
                    FoliaScheduler.runEntityLater(player, () -> teleportForExit(player, spawn, retries - 1, exitToken), 20L);
                } else if (player.isOnline()) {
                    FoliaScheduler.runEntity(player, () -> restorePlayerState(player, exitToken));
                }
            });
        } catch (RuntimeException ignored) {
            restorePlayerState(player, exitToken);
        }
    }

    private static void restorePlayerState(Player player, int exitToken) {
        UUID playerId = player.getUniqueId();
        if (!Objects.equals(exitGenerations.get(playerId), exitToken)) return;
        org.bukkit.potion.PotionEffect effect = player.getPotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS);
        if (effect != null && effect.getAmplifier() >= 255) player.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS);
        player.setInvisible(false);
        player.setFlying(false);
        player.setAllowFlight(false);
        PlayerStateCache.restore(player);
        exitGenerations.remove(playerId, exitToken);
    }

    public static boolean isPlaying(UUID playerId) {
        if (playerId == null) return false;
        for (Match match : matches.values()) {
            if (match.isInProgress() && match.containsPlayer(playerId)) return true;
        }
        return false;
    }

    public static void debug(String message) {
        if (debugMode && plugin != null) {
            plugin.getLogger().info("[DEBUG] " + message);
        }
    }

    public static String describeItem(ItemStack item) {
        if (item == null) return "null";
        return item.getType() + "x" + item.getAmount()
                + " hasMeta=" + item.hasItemMeta()
                + " meta=" + (item.hasItemMeta() ? item.getItemMeta().getClass().getSimpleName() : "none");
    }

    private static String describeBall(Ball ball) {
        if (ball == null) return "null";
        return "carrier=" + describeEntity(ball.getBall())
                + " display=" + describeEntity(ball.getDisplay())
                + " carrier=" + (ball.getCarrierBlockData() == null ? "null" : ball.getCarrierBlockData().getAsString())
                + " lastVelocity=" + formatVector(ball.getLastVelocity());
    }

    private static String describeEntity(org.bukkit.entity.Entity entity) {
        if (entity == null) return "null";
        return entity.getType() + "{" + entity.getUniqueId()
                + ",valid=" + entity.isValid()
                + ",dead=" + entity.isDead()
                + ",loc=" + formatLocation(entity.getLocation())
                + "}";
    }

    private static String formatLocation(Location location) {
        if (location == null) return "null";
        return (location.getWorld() == null ? "null" : location.getWorld().getName())
                + ":" + String.format(Locale.ROOT, "%.2f,%.2f,%.2f", location.getX(), location.getY(), location.getZ());
    }

    private static String formatVector(Vector vector) {
        if (vector == null) return "null";
        return String.format(Locale.ROOT, "%.3f,%.3f,%.3f", vector.getX(), vector.getY(), vector.getZ());
    }

    public static void launch(Player player, double power) {
        Vector direction = player.getLocation().getDirection().normalize();
        direction.setY(0.2);
        Vector velocity = direction.multiply(power);
        player.setVelocity(velocity);
    }

    private void launchRepeatingTask() {

        FoliaScheduler.runGlobalTimer(() -> {

            ResidenceBossBar.tick();

            cooldown.entrySet().removeIf(entry -> {
                long targetTime = entry.getValue();
                boolean b = System.currentTimeMillis() > targetTime;
                Player p = Bukkit.getPlayer(entry.getKey());
                if (p != null) {
                    FoliaScheduler.runEntity(p, () -> p.spigot().sendMessage(ChatMessageType.ACTION_BAR, getDashCooldownText(b, targetTime)));
                }
                return b;
            });

            for (Match match : matches.values()) {
                Integer matchTimerValue = match.tickMatchTimer();
                if (matchTimerValue != null) {
                    int matchTimer = matchTimerValue;

                    if (matchTimer % 60 == 0 && matchTimer > 0) {
                        match.getAllPlayer(true).forEach(player -> {
                            if (player != null) {
                                FoliaScheduler.runEntity(player, () -> player.sendMessage(I18n.format("match_time_left_min", "min", matchTimer / 60)));
                            }
                        });
                    }
                    if (matchTimer == 30 || matchTimer == 15 || matchTimer <= 10 && matchTimer > 0) {
                        match.getAllPlayer(true).forEach(player -> {
                            if (player != null) {
                                FoliaScheduler.runEntity(player, () -> player.sendMessage(I18n.format("match_time_left_sec", "sec", matchTimer)));
                            }
                        });
                    }
                    if (matchTimer <= 0) {
                        match.endMatch();
                    }
                } else if (!match.getMatchState().equals(OVERTIME)) {
                    for (Player player : match.getAllPlayer(false)) {
                        cooldown.remove(player.getUniqueId());
                    }
                }
            }
        }, 1, 20);
    }

    private static void tickBall(String id) {
        Ball ballData = balls.get(id);
        if (ballData == null || ballData.getBall() == null) return;

        Entity ball = ballData.getBall();
        if (!ball.isValid() || ball.isDead()) return;

        ball.setTicksLived(1);

        Match match = matches.get(ballData.getId());
        boolean itemMode = ballData.getDisplay() != null;
        double directKickDistance = itemMode ? 1.75 : 1.0;
        double alignedKickDistance = itemMode ? 3.0 : 2.5;

        Player kicker = null;
        double nearestDistanceSquared = Double.MAX_VALUE;
        Location ballLocation = ball.getLocation();
        for (Entity entity : ball.getNearbyEntities(5, 5, 5)) {
            if (!(entity instanceof Player player)) continue;
            if (match != null && !match.containsPlayer(player)) continue;
            Location playerLocation = player.getLocation();
            if (ballLocation.getY() - playerLocation.getY() > MAX_KICK_VERTICAL_REACH) continue;
            double distanceSquared = playerLocation.distanceSquared(ballLocation);
            boolean goalkeeper = isGoalkeeper(match, player);
            double directDistance = goalkeeper ? GOALKEEPER_DIRECT_KICK_DISTANCE : directKickDistance;
            double alignedDistance = goalkeeper ? GOALKEEPER_ALIGNED_KICK_DISTANCE : alignedKickDistance;
            boolean colliding = distanceSquared < directDistance * directDistance || (
                    distanceSquared < alignedDistance * alignedDistance
                            && Math.floor(ballLocation.getX()) == Math.floor(playerLocation.getX())
                            && Math.floor(ballLocation.getZ()) == Math.floor(playerLocation.getZ()));
            if (colliding && distanceSquared < nearestDistanceSquared) {
                nearestDistanceSquared = distanceSquared;
                kicker = player;
            }
        }
        if (kicker != null) {
            double distance = Math.sqrt(nearestDistanceSquared);
            Vector velocity = getVector(kicker, ballData);
            debug("kick id=" + id
                    + " mode=" + (itemMode ? "item" : "block")
                    + " player=" + kicker.getName()
                    + " distance=" + String.format(Locale.ROOT, "%.2f", distance)
                    + " velocity=" + formatVector(velocity)
                    + " carrier=" + describeEntity(ball)
                    + " display=" + describeEntity(ballData.getDisplay()));
            ball.setVelocity(velocity);
            ball.setGravity(true);
            ball.getWorld().playSound(ball.getLocation(), Sound.BLOCK_STONE_HIT, 10, 1);
            ballData.setPlayerCollisionTick(0);
            if (match != null) match.setLastTouchPlayer(ball, kicker);
        }

        if (ballData.getDisplay() != null && ball.isOnGround() && ballData.getPlayerCollisionTick() > 3) {
            Vector velocity = ball.getVelocity();
            double zVelocity = abs(velocity.getZ()) / 1.5;
            double xVelocity = abs(velocity.getX()) / 1.5;
            double yVelocity = Math.min(Math.max(zVelocity, xVelocity), 0.5);
            if (abs(velocity.getX() + velocity.getZ()) <= 0.001 || yVelocity < 0.025) {
                ball.setVelocity(velocity.zero());
                ball.setGravity(false);
            } else {
                velocity.setY(yVelocity);
                ball.setVelocity(velocity);
                ball.setGravity(true);
                // 只在落地瞬间（上一tick不在地面）播放声音，避免每tick重复播放
                if (!ballData.isWasOnGround()) {
                    ball.getWorld().playSound(ball.getLocation(), Sound.BLOCK_WOOL_HIT, 10, 1);
                }
            }
        }

        //compute bouncing on other blocks
        if (ballData.getPlayerCollisionTick() > 3) {

            boolean zBouncing = abs(ballData.getLastVelocity().getZ()) - abs(ball.getVelocity().getZ()) > 0.2 && ball.getVelocity().getZ() == 0;
            boolean xBouncing = abs(ballData.getLastVelocity().getX()) - abs(ball.getVelocity().getX()) > 0.2 && ball.getVelocity().getX() == 0;
            boolean yBouncing = abs(ballData.getLastVelocity().getY()) - abs(ball.getVelocity().getY()) > 0.2 && ball.getVelocity().getY() == 0;

            if (zBouncing) {
                ball.setVelocity(ball.getVelocity().setZ(-ballData.getLastVelocity().getZ()));
                ball.getVelocity().setZ(-ballData.getLastVelocity().getZ());
                ball.getWorld().playSound(ball.getLocation(), Sound.BLOCK_WOOL_HIT, 10, 1);
            }
            if (xBouncing) {
                ball.setVelocity(ball.getVelocity().setX(-ballData.getLastVelocity().getX()));
                ball.getVelocity().setX(-ballData.getLastVelocity().getX());
                ball.getWorld().playSound(ball.getLocation(), Sound.BLOCK_WOOL_HIT, 10, 1);
            }
            if (yBouncing) {
                ball.setGravity(true);
                ball.setVelocity(ball.getVelocity().setY(-ballData.getLastVelocity().getY()));
                ball.getWorld().playSound(ball.getLocation(), Sound.BLOCK_WOOL_HIT, 10, 1);
            }
        }

        if (match != null) {
            match.checkGoal(ball);
        }

        if (balls.get(id) != ballData) return;

        if (itemMode) {
            tickDisplayRoll(ballData);
        }

        ballData.setLastVelocity(ball.getVelocity().clone());
        ballData.setPlayerCollisionTick(ballData.getPlayerCollisionTick() + 1);
        ballData.setWasOnGround(ball.isOnGround());

    }

    private static boolean isGoalkeeper(Match match, Player player) {
        if (match == null || player == null) return false;
        Location location = player.getLocation();
        if (match.getBlueTeam().contains(player)) {
            return match.getData().isNearBlueTeamGoal(location, GOALKEEPER_GOAL_RADIUS);
        }
        if (match.getRedTeam().contains(player)) {
            return match.getData().isNearRedTeamGoal(location, GOALKEEPER_GOAL_RADIUS);
        }
        return false;
    }

    private static void tickDisplayRoll(Ball ballData) {
        Display display = ballData.getDisplay();
        Entity carrier = ballData.getBall();
        if (!ballRollEnabled || ballRollSpeed <= 0 || display == null || carrier == null) return;

        Vector velocity = carrier.getVelocity();
        double horizontalSpeed = Math.hypot(velocity.getX(), velocity.getZ());
        if (horizontalSpeed < 0.001) return;

        float roll = (float) ((ballData.getRollAngle() + horizontalSpeed * ROLL_DEGREES_PER_BLOCK * ballRollSpeed) % 360.0);
        float yaw = (float) Math.toDegrees(Math.atan2(-velocity.getX(), velocity.getZ()));
        ballData.setRollAngle(roll);
        display.setRotation(yaw, 0);
        display.setTransformation(new Transformation(
                new Vector3f(),
                new AxisAngle4f((float) Math.toRadians(roll), 1.0f, 0.0f, 0.0f),
                new Vector3f(1.0f, 1.0f, 1.0f),
                new AxisAngle4f()
        ));
    }

    private static TextComponent getDashCooldownText(boolean b, long targetTime) {
        if (b) return new TextComponent(I18n.get("dash_ready"));
        return new TextComponent(I18n.format("dash_cooldown", "time", (int) ((targetTime - System.currentTimeMillis()) / 1000.0 + 1)));
    }

    private static Vector getVector(Player player, Ball ballData) {
        double yVelocity = 0.15;
        double xzMul = 1;
        Vector direction = player.getLocation().getDirection();
        double lookY = Math.max(0.0, Math.min(0.9, direction.getY()));

        if (player.isSneaking()) {
            yVelocity = 0.3 + lookY * 0.8;
            xzMul = 3.5;
        } else if (player.isSprinting()) {
            yVelocity = 0.25;
        }

        Vector horizontalDirection = direction.clone().setY(0);
        if (player.isSneaking() && horizontalDirection.lengthSquared() > 0.0001) horizontalDirection.normalize();

        Vector currentVelocity = ballData.getBall().getVelocity().clone();
        Vector velocity = currentVelocity.clone();
        if (player.isSneaking()) {
            velocity.setY(yVelocity);
            velocity.setX((horizontalDirection.getX() / 2) * xzMul);
            velocity.setZ((horizontalDirection.getZ() / 2) * xzMul);
        } else {
            velocity.setY(currentVelocity.getY() + yVelocity + player.getVelocity().getY() / 2);
            velocity.setX(currentVelocity.getX() + (horizontalDirection.getX() / 2) * xzMul);
            velocity.setZ(currentVelocity.getZ() + (horizontalDirection.getZ() / 2) * xzMul);
        }

        // if player is not moving, create bouncing on it
        if (!player.isSneaking()
                && player.getVelocity().lengthSquared() < 0.000001) {
            velocity.setY(0);
            velocity.setX(0);
            velocity.setZ(0);
        }
        return velocity;
    }
}


