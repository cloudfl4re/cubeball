package me.crylonz;

import com.github.squi2rel.cb.CCBCommand;
import com.github.squi2rel.cb.GoalSelectionManager;
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
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
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
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import static java.lang.Math.abs;
import static me.crylonz.MatchState.*;

public class CubeBall extends JavaPlugin {
    public static Plugin plugin;

    public static Map<String, Ball> balls = new ConcurrentHashMap<>();
    public static Map<String, Match> matches = new ConcurrentHashMap<>();
    public static Map<UUID, Long> cooldown = new ConcurrentHashMap<>();
    private static final int MAX_APPEARANCE_WARNINGS = 256;
    private static final Set<String> appearanceWarnings = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Integer> exitGenerations = new ConcurrentHashMap<>();
    private static NamespacedKey spectatorInvisibleKey;
    private static final Material ITEM_BALL_CARRIER = Material.WHITE_WOOL;
    private static final double ROLL_DEGREES_PER_BLOCK = 120.0;
    private static final double GOALKEEPER_GOAL_RADIUS = 3.0;
    private static final double GOALKEEPER_DIRECT_KICK_DISTANCE = 3.0;
    private static final double GOALKEEPER_ALIGNED_KICK_DISTANCE = 4.2;
    private static final double MAX_KICK_VERTICAL_REACH = 2.6;

    public static volatile int maxMatchPerPlayer;
    public static volatile boolean debugMode;
    public static volatile boolean ballGlow;
    public static volatile boolean ballRollEnabled;
    public static volatile double ballRollSpeed;
    private static final AtomicBoolean configReloading = new AtomicBoolean();
    private static final AtomicBoolean snapshotQueued = new AtomicBoolean();
    private static final AtomicBoolean saveQueued = new AtomicBoolean();
    private static final AtomicLong saveVersion = new AtomicLong();
    private static final AtomicLong lifecycleGeneration = new AtomicLong();
    private static final Object SAVE_LOCK = new Object();
    private static volatile boolean active;
    private static volatile ConfigSnapshot pendingConfig;
    private static volatile Location lobbySpawn;
    private static volatile Location exitSpawn;
    private static volatile String waitingLobbyResidence = "zqc";
    private static final String DEFAULT_BOSS_BAR_RED_TEAM = "\u7ea2\u961f";
    private static final String DEFAULT_BOSS_BAR_BLUE_TEAM = "\u84dd\u961f";
    private static volatile String bossBarRedTeam = DEFAULT_BOSS_BAR_RED_TEAM;
    private static volatile String bossBarBlueTeam = DEFAULT_BOSS_BAR_BLUE_TEAM;
    private static volatile String playerIdentityMode = "name";
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
            ItemStack carrierItem = new ItemStack(Material.BARRIER);
            if (CraftEngineHook.isAvailable()) {
                ItemStack ceBlank = CraftEngineHook.buildCustomItemIcon("server_img_library:litesignin_air");
                if (ceBlank != null) carrierItem = ceBlank;
            }
            final ItemStack finalCarrierItem = carrierItem;
            // 给载体物品堆本身也设置 displayName，让以材质+名称判断的清道夫插件（如 Cyuclear 白名单模式）
            // 能识别为"已命名"物品而跳过清理。
            org.bukkit.inventory.meta.ItemMeta carrierMeta = finalCarrierItem.getItemMeta();
            if (carrierMeta != null) {
                carrierMeta.setDisplayName("§rCubeBall");
                finalCarrierItem.setItemMeta(carrierMeta);
            }
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
                protectBallEntity(dropped);
            });
            carrier = item;
        } else {
            FallingBlock block = Objects.requireNonNull(location.getWorld()).spawnFallingBlock(location, blockData);
            block.setMetadata("ballID", new FixedMetadataValue(plugin, id));
            block.setDropItem(false);
            block.setInvulnerable(true);
            block.setHurtEntities(false);
            protectBallEntity(block);
            carrier = block;
        }

        ItemDisplay display = null;
        if (appearance.isItemDisplayMode()) {
            display = (ItemDisplay) location.getWorld().spawnEntity(location, EntityType.ITEM_DISPLAY);
            display.setItemStack(appearance.getDisplayItem());
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
            display.setRotation(0, 0);
            display.setInvulnerable(true);
            display.setGlowing(ballGlow);
            protectBallEntity(display);
            carrier.addPassenger(display);
        } else {
            carrier.setGlowing(ballGlow);
        }

        Ball ball = new Ball();
        ball.setId(id);
        ball.setBall(carrier);
        ball.setDisplay(display);
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
        Plugin owner = plugin;
        if (owner == null) return;
        synchronized (appearanceWarnings) {
            if (appearanceWarnings.contains(key) || appearanceWarnings.size() >= MAX_APPEARANCE_WARNINGS) return;
            appearanceWarnings.add(key);
        }
        owner.getLogger().warning(message);
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
        snapshotQueued.set(false);
        saveQueued.set(false);
        configReloading.set(false);
        pendingConfig = null;
        lifecycleGeneration.incrementAndGet();
        active = true;
        spectatorInvisibleKey = new NamespacedKey(this, "spectator_invisible");
        FoliaScheduler.init(this);
        PlayerStateCache.init(this);

        saveDefaultConfig();

        applyRuntimeConfig();
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
            ConfigurationSection matchSection = section.getConfigurationSection(key);
            if (matchSection == null) {
                getLogger().warning("已跳过无效比赛配置节点: matches." + key);
                continue;
            }
            try {
                matches.put(key, new Match(key, MatchData.from(matchSection)));
            } catch (RuntimeException error) {
                getLogger().log(java.util.logging.Level.WARNING, "已跳过损坏的比赛配置: " + key, error);
            }
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            FoliaScheduler.runEntity(player, () -> {
                if (PlayerStateCache.has(player) || hasManagedSpectatorVisibility(player)) restorePlayerAndExit(player);
            });
        }

        getServer().getPluginManager().registerEvents(new CubeBallListener(), this);
        EmotecraftHook.init();

        new Metrics(this, 17634);

        launchRepeatingTask();

        CCBCommand ccbCommand = new CCBCommand();
        Objects.requireNonNull(getCommand("ccb")).setExecutor(ccbCommand);
        Objects.requireNonNull(getCommand("ccb")).setTabCompleter(ccbCommand);
    }

    public void onDisable() {
        active = false;
        lifecycleGeneration.incrementAndGet();
        ResidenceBossBar.shutdown();
        MenuManager.shutdown();
        JoinSignManager.shutdown();
        EmotecraftHook.shutdown();
        CraftEngineHook.shutdown();
        ResidenceHook.shutdown();
        PlayerStateCache.shutdown();
        FoliaScheduler.shutdown(this);
        save();

        matches.values().forEach(Match::shutdown);
        balls.values().forEach(Ball::cancelPhysicsTask);
        GoalSelectionManager.clearAll();
        balls.clear();
        matches.clear();
        cooldown.clear();
        appearanceWarnings.clear();
        exitGenerations.clear();
        configReloading.set(false);
        snapshotQueued.set(false);
        saveQueued.set(false);
        pendingConfig = null;
        lobbySpawn = null;
        exitSpawn = null;
        spectatorInvisibleKey = null;
        plugin = null;
    }

    public static void save() {
        Plugin owner = plugin;
        if (owner == null) return;
        saveVersion.incrementAndGet();
        pendingConfig = null;
        synchronized (SAVE_LOCK) {
            try {
                writeConfigAtomically(owner, serializeConfig(owner));
            } catch (Exception error) {
                owner.getLogger().log(java.util.logging.Level.SEVERE, "无法保存足球系统配置", error);
            }
        }
    }

    public static void saveAsync() {
        if (!active || plugin == null || !plugin.isEnabled() || configReloading.get()) return;
        saveVersion.incrementAndGet();
        queueConfigSnapshot();
    }

    private static void queueConfigSnapshot() {
        long generation = lifecycleGeneration.get();
        Plugin owner = plugin;
        if (!isActiveGeneration(generation) || owner == null || !owner.isEnabled() || configReloading.get()) return;
        if (!snapshotQueued.compareAndSet(false, true)) return;
        FoliaScheduler.runGlobal(() -> {
            long version = saveVersion.get();
            try {
                if (isActiveGeneration(generation) && !configReloading.get()) {
                    pendingConfig = new ConfigSnapshot(generation, version, serializeConfig(owner));
                    queueConfigWriter(generation);
                }
            } catch (Throwable error) {
                if (isActiveGeneration(generation)) {
                    owner.getLogger().log(java.util.logging.Level.SEVERE, "无法创建足球系统配置快照", error);
                }
            } finally {
                if (generation == lifecycleGeneration.get()) {
                    snapshotQueued.set(false);
                    if (isActiveGeneration(generation) && !configReloading.get()
                            && saveVersion.get() != version) queueConfigSnapshot();
                }
            }
        });
    }

    private static void queueConfigWriter(long generation) {
        Plugin owner = plugin;
        if (!isActiveGeneration(generation) || owner == null) return;
        if (!saveQueued.compareAndSet(false, true)) return;
        FoliaScheduler.runAsync(() -> {
            try {
                while (isActiveGeneration(generation)) {
                    ConfigSnapshot snapshot = pendingConfig;
                    pendingConfig = null;
                    if (snapshot != null && snapshot.generation == generation
                            && snapshot.version == saveVersion.get() && !configReloading.get()) {
                        synchronized (SAVE_LOCK) {
                            if (isActiveGeneration(generation)) {
                                writeConfigAtomically(owner, snapshot.data);
                            }
                        }
                    }
                    if (pendingConfig == null) break;
                }
            } catch (Exception error) {
                if (isActiveGeneration(generation)) {
                    owner.getLogger().log(java.util.logging.Level.SEVERE, "Unable to save CubeBall configuration asynchronously", error);
                }
            } finally {
                if (generation == lifecycleGeneration.get()) {
                    saveQueued.set(false);
                    if (isActiveGeneration(generation) && pendingConfig != null) queueConfigWriter(generation);
                }
            }
        });
    }

    private static void writeConfigAtomically(Plugin owner, String data) throws java.io.IOException {
        Path file = owner.getDataFolder().toPath().resolve("config.yml");
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling("config.yml.tmp");
        Files.writeString(temp, data, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String serializeConfig(Plugin owner) {
        YamlConfiguration snapshot = new YamlConfiguration();
        for (String key : owner.getConfig().getKeys(false)) snapshot.set(key, owner.getConfig().get(key));
        ConfigurationSection section = new MemoryConfiguration();
        for (Map.Entry<String, Match> match : matches.entrySet()) {
            MemoryConfiguration data = new MemoryConfiguration();
            match.getValue().getData().write(data);
            section.set(match.getKey(), data);
        }
        snapshot.set("matches", section);
        return snapshot.saveToString();
    }

    public static boolean reloadRuntimeSettings(Runnable success, Consumer<Throwable> failure) {
        long generation = lifecycleGeneration.get();
        if (!isActiveGeneration(generation) || !(plugin instanceof CubeBall instance)
                || !configReloading.compareAndSet(false, true)) return false;
        saveVersion.incrementAndGet();
        pendingConfig = null;
        FoliaScheduler.runAsync(() -> {
            try {
                if (!isActiveGeneration(generation)) return;
                YamlConfiguration loaded;
                synchronized (SAVE_LOCK) {
                    if (!isActiveGeneration(generation)) return;
                    loaded = YamlConfiguration.loadConfiguration(instance.getDataFolder().toPath().resolve("config.yml").toFile());
                    String language = loaded.getString("language", "en");
                    I18n.init(instance, language == null ? "en" : language);
                }
                if (!isActiveGeneration(generation)) return;
                FoliaScheduler.runGlobal(() -> {
                    try {
                        if (!isActiveGeneration(generation) || !instance.isEnabled()) return;
                        FileConfiguration activeConfig = instance.getConfig();
                        for (String key : new HashSet<>(activeConfig.getKeys(false))) activeConfig.set(key, null);
                        for (String key : loaded.getKeys(true)) {
                            if (!loaded.isConfigurationSection(key)) activeConfig.set(key, loaded.get(key));
                        }
                        instance.applyRuntimeConfig();
                        ResidenceBossBar.refreshAll();
                        success.run();
                    } catch (Throwable throwable) {
                        if (isActiveGeneration(generation)) failure.accept(throwable);
                    } finally {
                        if (generation == lifecycleGeneration.get()) configReloading.set(false);
                    }
                });
            } catch (Throwable throwable) {
                if (generation == lifecycleGeneration.get()) configReloading.set(false);
                if (isActiveGeneration(generation)) {
                    FoliaScheduler.runGlobal(() -> {
                        if (isActiveGeneration(generation)) failure.accept(throwable);
                    });
                }
            }
        });
        return true;
    }

    private void applyRuntimeConfig() {
        debugMode = getConfig().getBoolean("debug", false);
        ballGlow = getConfig().getBoolean("ball.glow", true);
        ballRollEnabled = getConfig().getBoolean("ball.roll.enabled", true);
        ballRollSpeed = Math.max(0.0, getConfig().getDouble("ball.roll.speed", 1.0));
        maxMatchPerPlayer = Math.max(1, getConfig().getInt("maxMatchPerPlayer", 3));
        String identity = getConfig().getString("player-identity.mode", "name");
        playerIdentityMode = identity != null && identity.equalsIgnoreCase("uuid") ? "uuid" : "name";
        lobbySpawn = getConfig().getSerializable("lobbySpawn", Location.class);
        exitSpawn = getConfig().getSerializable("exitSpawn", Location.class);
        String residenceName = getConfig().getString("waitingLobby.residence", "zqc");
        waitingLobbyResidence = residenceName == null ? "" : residenceName.trim();
        bossBarRedTeam = normalizeBossBarTeamName(getConfig().getString("bossbar.redteam"), DEFAULT_BOSS_BAR_RED_TEAM);
        bossBarBlueTeam = normalizeBossBarTeamName(getConfig().getString("bossbar.blueteam"), DEFAULT_BOSS_BAR_BLUE_TEAM);
        VisualEffects.init(this);
        if (!waitingLobbyResidence.isEmpty()) ResidenceHook.init();
    }

    public static void setDebugMode(boolean enabled) {
        debugMode = enabled;
        if (plugin != null) {
            updateConfig("debug", enabled);
            plugin.getLogger().info("Debug mode " + (enabled ? "enabled" : "disabled"));
        }
    }

    public static void setBallGlow(boolean enabled) {
        ballGlow = enabled;
        if (plugin != null) {
            updateConfig("ball.glow", enabled);
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
            updateConfig("ball.roll.enabled", enabled);
            plugin.getLogger().info("Ball roll " + (enabled ? "enabled" : "disabled"));
        }
    }

    public static void setBallRollSpeed(double speed) {
        ballRollSpeed = Math.max(0.0, speed);
        if (plugin != null) {
            updateConfig("ball.roll.speed", ballRollSpeed);
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
            updateConfig(path, name);
        }
        ResidenceBossBar.refreshAll();
    }

    public static void setLobbySpawn(Location location) {
        lobbySpawn = location == null ? null : location.clone();
        if (plugin != null) {
            updateConfig("lobbySpawn", lobbySpawn);
        }
    }

    public static Location getExitSpawn() {
        Location spawn = exitSpawn;
        return spawn == null ? null : spawn.clone();
    }

    public static void setExitSpawn(Location location) {
        exitSpawn = location == null ? null : location.clone();
        if (plugin != null) {
            updateConfig("exitSpawn", exitSpawn);
        }
    }

    private static void updateConfig(String path, Object value) {
        FoliaScheduler.runGlobal(() -> {
            if (plugin == null || !plugin.isEnabled()) return;
            plugin.getConfig().set(path, value);
            saveAsync();
        });
    }

    public static boolean usesNameIdentity() {
        return "name".equals(playerIdentityMode);
    }

    public static String getPlayerIdentityMode() {
        return playerIdentityMode;
    }

    public static void setPlayerIdentityMode(String mode) {
        playerIdentityMode = "uuid".equalsIgnoreCase(mode) ? "uuid" : "name";
        updateConfig("player-identity.mode", playerIdentityMode);
    }

    public static boolean isMatchOwner(Player player, MatchData data) {
        if (player == null || data == null) return false;
        if (usesNameIdentity()) {
            return data.creator != null && data.creator.equalsIgnoreCase(player.getName());
        }
        return data.creatorIdMost == player.getUniqueId().getMostSignificantBits()
                && data.creatorIdLeast == player.getUniqueId().getLeastSignificantBits();
    }

    private static boolean isActiveGeneration(long generation) {
        return active && plugin != null && generation == lifecycleGeneration.get();
    }

    private record ConfigSnapshot(long generation, long version, String data) {
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

    static boolean hasManagedSpectatorVisibility(Player player) {
        return player != null && spectatorInvisibleKey != null
                && player.getPersistentDataContainer().has(spectatorInvisibleKey, PersistentDataType.BYTE);
    }

    static void markManagedSpectatorVisibility(Player player) {
        if (player == null || spectatorInvisibleKey == null) return;
        player.getPersistentDataContainer().set(spectatorInvisibleKey, PersistentDataType.BYTE, (byte) 1);
    }

    static void clearManagedSpectatorVisibility(Player player) {
        if (player == null) return;
        player.setInvisible(false);
        player.setCollidable(true);
        if (spectatorInvisibleKey != null) {
            player.getPersistentDataContainer().remove(spectatorInvisibleKey);
        }
    }

    private static void clearManagedSpectatorMarker(Player player) {
        if (player != null && spectatorInvisibleKey != null) {
            player.getPersistentDataContainer().remove(spectatorInvisibleKey);
        }
    }

    private static void teleportForExit(Player player, Location spawn, int retries, int exitToken) {
        try {
            FoliaScheduler.teleport(player, spawn).whenComplete((success, error) -> {
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
        player.setCollidable(true);
        PlayerStateCache.restore(player);
        clearManagedSpectatorMarker(player);
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
            JoinSignManager.tickWaitingPlayers();

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
                match.maintainSpectatorStates();
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
        if (!ownsLiveState(ball)) return;
        if (!ball.isValid() || ball.isDead()) return;

        ball.setTicksLived(1);

        Match match = matches.get(ballData.getId());
        boolean displayMode = ballData.getDisplay() != null;
        double directKickDistance = displayMode ? 1.75 : 1.0;
        double alignedKickDistance = displayMode ? 3.0 : 2.5;

        Player kicker = null;
        double nearestDistanceSquared = Double.MAX_VALUE;
        Location ballLocation = ball.getLocation();
        for (Entity entity : ball.getNearbyEntities(5, 5, 5)) {
            if (!(entity instanceof Player player)) continue;
            if (!ownsLiveState(player)) continue;
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
                    + " mode=" + (displayMode ? "item" : "block")
                    + " player=" + kicker.getName()
                    + " distance=" + String.format(Locale.ROOT, "%.2f", distance)
                    + " velocity=" + formatVector(velocity)
                    + " carrier=" + describeEntity(ball)
                    + " display=" + describeEntity(ballData.getDisplay()));
            ball.setVelocity(velocity);
            ball.setGravity(true);
            VisualEffects.ballKick(ball);
            ballData.setPlayerCollisionTick(0);
            if (match != null) match.setLastTouchPlayer(ball, kicker);
        }

        if (displayMode && ball.isOnGround() && ballData.getPlayerCollisionTick() > 3) {
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
                if (!ballData.isWasOnGround()) {
                    VisualEffects.ballBounce(ball);
                }
            }
        }

        if (ballData.getPlayerCollisionTick() > 3) {

            boolean zBouncing = abs(ballData.getLastVelocity().getZ()) - abs(ball.getVelocity().getZ()) > 0.2 && ball.getVelocity().getZ() == 0;
            boolean xBouncing = abs(ballData.getLastVelocity().getX()) - abs(ball.getVelocity().getX()) > 0.2 && ball.getVelocity().getX() == 0;
            boolean yBouncing = abs(ballData.getLastVelocity().getY()) - abs(ball.getVelocity().getY()) > 0.2 && ball.getVelocity().getY() == 0;

            if (zBouncing) {
                ball.setVelocity(ball.getVelocity().setZ(-ballData.getLastVelocity().getZ()));
                ball.getVelocity().setZ(-ballData.getLastVelocity().getZ());
                VisualEffects.ballBounce(ball);
            }
            if (xBouncing) {
                ball.setVelocity(ball.getVelocity().setX(-ballData.getLastVelocity().getX()));
                ball.getVelocity().setX(-ballData.getLastVelocity().getX());
                VisualEffects.ballBounce(ball);
            }
            if (yBouncing) {
                ball.setGravity(true);
                ball.setVelocity(ball.getVelocity().setY(-ballData.getLastVelocity().getY()));
                VisualEffects.ballBounce(ball);
            }
        }

        if (match != null) {
            match.checkGoal(ball);
        }

        if (balls.get(id) != ballData) return;

        if (displayMode) {
            tickDisplayRoll(ballData);
        }

        VisualEffects.ballTrail(ball, ballData.getPlayerCollisionTick());

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

    private static boolean ownsLiveState(Entity entity) {
        if (entity == null) return false;
        return FoliaScheduler.isFolia() ? Bukkit.isOwnedByCurrentRegion(entity) : Bukkit.isPrimaryThread();
    }

    private static void protectBallEntity(Entity entity) {
        if (entity == null) return;
        entity.setCustomName("CubeBall");
        entity.setCustomNameVisible(false);
        entity.setPersistent(false);
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
        double playerUp = Math.max(0.0, player.getVelocity().getY());
        boolean jumpSneak = false;

        if (player.isSneaking()) {
            yVelocity = 0.12 + lookY * 0.25;
            xzMul = 2.8;
            jumpSneak = playerUp > 0.08 || !player.isOnGround();
            if (jumpSneak) {
                yVelocity += 0.30;
            }
        } else if (player.isSprinting()) {
            yVelocity = 0.25;
        }

        Vector horizontalDirection = direction.clone().setY(0);
        if (player.isSneaking() && horizontalDirection.lengthSquared() > 0.0001) horizontalDirection.normalize();

        Vector currentVelocity = ballData.getBall().getVelocity().clone();
        Vector velocity = currentVelocity.clone();
        double upBoost = jumpSneak ? 0.0 : playerUp / 2.0;
        velocity.setY(currentVelocity.getY() + yVelocity + upBoost);
        velocity.setX(currentVelocity.getX() + (horizontalDirection.getX() / 2.0) * xzMul);
        velocity.setZ(currentVelocity.getZ() + (horizontalDirection.getZ() / 2.0) * xzMul);

        if (!player.isSneaking()
                && player.getVelocity().lengthSquared() < 0.000001) {
            velocity.setY(0);
            velocity.setX(0);
            velocity.setZ(0);
        }
        return velocity;
    }
}

