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
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.Math.abs;
import static me.crylonz.MatchState.*;

public class CubeBall extends JavaPlugin {
    public static Plugin plugin;

    public static Map<String, Ball> balls = new ConcurrentHashMap<>();
    public static Map<String, Match> matches = new ConcurrentHashMap<>();
    public static Map<UUID, Long> cooldown = new ConcurrentHashMap<>();

    public static int maxMatchPerPlayer;

    public static void generateBall(MatchData data, String id, Location location, Vector lastVelocity) {

        if (balls.get(id) != null) {
            throw new IllegalStateException("Same ID cannot be put on the same ball");
        }

        BallAppearance appearance = resolveAppearance(data);

        BlockData blockData = appearance.getCarrierBlockData();
        FallingBlock block = Objects.requireNonNull(location.getWorld()).spawnFallingBlock(location, blockData);
        block.setMetadata("ballID", new FixedMetadataValue(plugin, id));
        block.setDropItem(false);
        block.setInvulnerable(true);

        ItemDisplay display = null;
        if (appearance.isItemDisplayMode()) {
            display = (ItemDisplay) location.getWorld().spawnEntity(location, EntityType.ITEM_DISPLAY);
            display.setItemStack(appearance.getDisplayItem());
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.setInvulnerable(true);
            display.setGlowing(true);
        } else {
            block.setGlowing(true);
        }

        Ball ball = new Ball();
        ball.setId(id);
        ball.setBall(block);
        ball.setDisplay(display);
        // carrierBlockData 非 null 表示走 CE 路径（方块或物品模式），监听器用 BlockData 比较；
        // 原版回退保持 null，监听器走原 Material 比较分支，行为与改动前一致。
        ball.setCarrierBlockData(appearance.isCustomMode() ? blockData : null);

        if (lastVelocity != null) {
            ball.getLastVelocity().setX(0);
            ball.getLastVelocity().setZ(0);
        }

        ball.setPlayerCollisionTick(0);
        balls.put(id, ball);
        ball.setPhysicsTask(FoliaScheduler.runEntityTimer(block, () -> tickBall(id), 1, 2));
    }

    /**
     * 解析足球外观：ballCustomId 为空 → 原版 cubeBallBlock 的 BlockData，无显示实体；
     * 否则尝试 CE 解析，命中用 CE 结果，未命中/CE 未装回退原版并 warn 一次。
     */
    private static BallAppearance resolveAppearance(MatchData data) {
        String customId = data.ballCustomId;
        if (customId != null && !customId.isEmpty()) {
            if (CraftEngineHook.isAvailable()) {
                BallAppearance app = CraftEngineHook.resolve(customId, data.cubeBallBlock);
                if (app != null) {
                    return app;
                }
                plugin.getLogger().warning("CraftEngine custom id not found: " + customId + ", falling back to " + data.cubeBallBlock);
            } else {
                plugin.getLogger().warning("ballCustomId is set (" + customId + ") but CraftEngine is not installed, falling back to " + data.cubeBallBlock);
            }
        }
        return new BallAppearance(Bukkit.createBlockData(data.cubeBallBlock), null, false);
    }

    public static void destroyBall(String id) {
        Ball ballData = balls.remove(id);
        if (ballData != null) {
            ballData.cancelPhysicsTask();
            FallingBlock ball = ballData.getBall();
            if (ball != null) {
                FoliaScheduler.runEntity(ball, ball::remove);
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

        saveDefaultConfig();

        String lang = getConfig().getString("language", "en");
        I18n.init(this, lang);

        MenuManager.init(this);

        ConfigurationSection section = getConfig().getConfigurationSection("matches");
        if (section == null) section = new MemoryConfiguration();
        for (String key : Objects.requireNonNull(section).getKeys(false)) {
            matches.put(key, new Match(key, MatchData.from(section.getConfigurationSection(key))));
        }

        maxMatchPerPlayer = getConfig().getInt("maxMatchPerPlayer", 3);

        getServer().getPluginManager().registerEvents(new CubeBallListener(), this);

        new Metrics(this, 17634);

        launchRepeatingTask();

        Objects.requireNonNull(getCommand("ccb")).setExecutor(new CCBCommand());
    }

    public void onDisable() {
        MenuManager.closeAll();

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

    public static void launch(Player player, double power) {
        Vector direction = player.getLocation().getDirection().normalize();
        direction.setY(0.2);
        Vector velocity = direction.multiply(power);
        player.setVelocity(velocity);
    }

    private void launchRepeatingTask() {

        FoliaScheduler.runGlobalTimer(() -> {

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
                if (match.getMatchState().equals(IN_PROGRESS)) {
                    int matchTimer = --match.matchTimer;

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
                        if (match.getMatchState() != OVERTIME) {
                            match.setMatchState(READY);
                        }
                    }
                } else {
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

        FallingBlock ball = ballData.getBall();
        if (!ball.isValid() || ball.isDead()) return;

        ball.setTicksLived(1);

        Match match = matches.get(ballData.getId());

        ball.getNearbyEntities(3, 3, 3)
                .stream().filter(entity -> entity instanceof Player)
                .forEach(p -> {
                    Player player = (Player) p;
                    // if player is colliding the ball
                    if (player.getLocation().distance(ball.getLocation()) < 1 || (
                            player.getLocation().distance(ball.getLocation()) < 2.5 &&
                                    Math.floor(ball.getLocation().getX()) == Math.floor(player.getLocation().getX()) &&
                                    Math.floor(ball.getLocation().getZ()) == Math.floor(player.getLocation().getZ()))) {

                        // compute velocity to the ball
                        Vector velocity = getVector(player, ballData);

                        // apply ball trajectory
                        ball.setVelocity(velocity);
                        ball.setGravity(true);
                        ball.getWorld().playSound(ball.getLocation(), Sound.BLOCK_STONE_HIT, 10, 1);
                        ballData.setPlayerCollisionTick(0);

                        if (match != null) {
                            match.setLastTouchPlayer(player);
                        }
                    }
                });

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
            match.checkGoal(ball.getLocation());
        }

        if (balls.get(id) != ballData) return;

        ballData.setLastVelocity(ball.getVelocity().clone());
        ballData.setPlayerCollisionTick(ballData.getPlayerCollisionTick() + 1);

        Display display = ballData.getDisplay();
        if (display != null) {
            FoliaScheduler.runEntity(display, () -> display.teleport(ball.getLocation()));
        }
    }

    private static TextComponent getDashCooldownText(boolean b, long targetTime) {
        if (b) return new TextComponent(I18n.get("dash_ready"));
        return new TextComponent(I18n.format("dash_cooldown", "time", (int) ((targetTime - System.currentTimeMillis()) / 1000.0 + 1)));
    }

    private static Vector getVector(Player player, Ball ballData) {
        double yVelocity = 0.15;
        double xzMul = 1;

        if (player.isSneaking()) {
            yVelocity = 0.3;
            xzMul = 3.5;
        } else if (player.isSprinting()) {
            yVelocity = 0.25;
        }

        Vector velocity = ballData.getBall().getVelocity();
        velocity.setY(ballData.getBall().getVelocity().getY() + yVelocity + player.getVelocity().getY() / 2);
        velocity.setX(ballData.getBall().getVelocity().getX() + (player.getLocation().getDirection().getX() / 2) * xzMul);
        velocity.setZ(ballData.getBall().getVelocity().getZ() + (player.getLocation().getDirection().getZ() / 2) * xzMul);

        // if player is not moving, create bouncing on it
        if (abs(player.getVelocity().getX() + player.getVelocity().getY() + player.getVelocity().getZ()) == 0) {
            velocity.setY(0);
            velocity.setX(0);
            velocity.setZ(0);
        }
        return velocity;
    }
}


