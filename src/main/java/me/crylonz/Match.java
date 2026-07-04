package me.crylonz;

import com.github.squi2rel.cb.I18n;
import com.github.squi2rel.cb.MatchData;
import com.github.squi2rel.cb.util.FoliaScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static me.crylonz.CubeBall.*;
import static me.crylonz.MatchState.*;

public class Match {
    public int matchTimer;

    private final String name;
    private final Random rand = new Random();
    private final Set<Player> blueTeam = ConcurrentHashMap.newKeySet();
    private final Set<Player> redTeam = ConcurrentHashMap.newKeySet();
    private final Set<Player> spectatorTeam = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> goals = new ConcurrentHashMap<>();
    private volatile MatchState matchState;
    private volatile UUID lastTouchPlayer;
    private volatile int blueScore = 0;
    private volatile int redScore = 0;
    private volatile boolean canceled;
    private final AtomicInteger roundGeneration = new AtomicInteger();
    private final Set<ScheduledTask> roundTasks = ConcurrentHashMap.newKeySet();
    private final MatchData data;

    public Match(String name, Player player) {
        this(name, MatchData.create(player.getName(), player.getUniqueId()));
    }
    public Match(String name, MatchData config) {
        this.name = name;
        this.data = config;
        matchState = CREATED;
    }

    public void scanPlayer() {
        blueTeam.clear();
        redTeam.clear();
        spectatorTeam.clear();

        List<Location> blueSpawns = new ArrayList<>(data.blueTeamSpawns);
        List<Location> redSpawns = new ArrayList<>(data.redTeamSpawns);
        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        int taskCount = blueSpawns.size() + redSpawns.size() + onlinePlayers.size();
        if (taskCount <= 0) {
            matchState = READY;
            return;
        }

        AtomicInteger remaining = new AtomicInteger(taskCount);
        for (Location spawn : blueSpawns) {
            scanNearPlayers(spawn, Team.BLUE, remaining);
        }
        for (Location spawn : redSpawns) {
            scanNearPlayers(spawn, Team.RED, remaining);
        }

        World world = data.ballSpawn.getWorld();
        for (Player player : onlinePlayers) {
            FoliaScheduler.runEntity(player, () -> {
                try {
                    if (player.getWorld() != world) return;
                    if (player.getLocation().distance(data.ballSpawn) < 256) {
                        if (!blueTeam.contains(player) && !redTeam.contains(player)) addPlayerToTeam(player, Team.SPECTATOR);
                    }
                } finally {
                    finishScan(remaining);
                }
            }, () -> finishScan(remaining));
        }
    }

    private void scanNearPlayers(Location spawn, Team team, AtomicInteger remaining) {
        if (spawn == null) {
            finishScan(remaining);
            return;
        }
        FoliaScheduler.runRegion(spawn, () -> {
            try {
                World world = Objects.requireNonNull(spawn.getWorld());
                for (Entity entity : world.getNearbyEntities(spawn, 1, 1, 1)) {
                    if (entity instanceof Player) {
                        Player player = (Player) entity;
                        if (player.getVehicle() == null) addPlayerToTeam(player, team);
                    }
                }
            } finally {
                finishScan(remaining);
            }
        });
    }

    private void finishScan(AtomicInteger remaining) {
        if (remaining.decrementAndGet() == 0) {
            matchState = READY;
        }
    }

    public void start(Player p) {
        if (matchState == READY) {
            reset();
            if (!blueTeam.isEmpty() || !redTeam.isEmpty()) {
                sortSpawns();

                startDelayedRound();
                matchTimer = data.matchDuration;
                matchState = IN_PROGRESS;

                sendPlayerMessage(p, I18n.get("match_starting"));
                forEachPlayer(true, player -> {
                    player.sendMessage(I18n.format("match_started", "min", matchTimer / 60, "sec", matchTimer - ((matchTimer / 60) * 60)));
                    player.sendMessage(I18n.format("max_goals", "max", data.maxGoal <= 0 ? I18n.get("max_goals_unlimited") : data.maxGoal));
                });
            } else {
                sendPlayerMessage(p, I18n.get("need_add_players"));
            }
        } else {
            sendPlayerMessage(p, I18n.get("match_not_ready"));
        }
    }

    private void sortSpawns() {
        Comparator<Location> sort = Comparator.comparingDouble(l -> l.distance(data.ballSpawn));
        data.blueTeamSpawns.sort(sort);
        data.redTeamSpawns.sort(sort);
    }

    public int[] randomIds(int size, int n) {
        if (size <= 0 || n <= 0) return new int[0];

        int[] result = new int[n];
        int filled = 0;

        while (filled < n) {
            int segmentLen = Math.min(size, n - filled);

            int[] pool = new int[segmentLen];
            for (int i = 0; i < segmentLen; i++) {
                pool[i] = i;
            }

            for (int i = 0; i < segmentLen; i++) {
                int j = i + rand.nextInt(segmentLen - i);
                int tmp = pool[i];
                pool[i] = pool[j];
                pool[j] = tmp;
                result[filled++] = pool[i];
            }
        }
        return result;
    }

    public void teleportTeam(Set<Player> team, List<Location> spawns) {
        int[] ids = randomIds(spawns.size(), team.size());
        int i = 0;
        for (Player player : team) {
            Location target = getFacingLocation(spawns.get(ids[i++]), data.ballSpawn);
            runForPlayer(player, p -> p.teleportAsync(target));
        }
    }

    public static Location getFacingLocation(Location from, Location to) {
        Location loc = from.clone();
        Vector direction = to.toVector().subtract(loc.toVector());
        double dx = direction.getX();
        double dy = direction.getY();
        double dz = direction.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        loc.setYaw(yaw);
        loc.setPitch(pitch);
        return loc;
    }

    private void startDelayedRound() {
        int roundToken = beginRoundSequence();
        teleportTeam(blueTeam, data.blueTeamSpawns);
        teleportTeam(redTeam, data.redTeamSpawns);

        PotionEffect effect = new PotionEffect(PotionEffectType.SLOWNESS, 80, 255);
        List<Location> allSpawns = getAllSpawns();
        for (Location spawn : allSpawns) setSurrounding(spawn, Material.BARRIER);
        forEachPlayer(false, player -> {
            if (!PlayerStateCache.has(player)) PlayerStateCache.save(player);
            PlayerStateCache.clear(player);
            equipTeamKit(player);
            player.setAllowFlight(false);
            player.setFlying(false);
            player.setVelocity(new Vector(0, 0, 0));
            player.addPotionEffect(effect);
        });
        scheduleRoundTask(roundToken, () -> sendMessageToAllPlayer(I18n.get("countdown_3"), "", 1, Sound.BLOCK_NOTE_BLOCK_BELL, 1), 20);
        scheduleRoundTask(roundToken, () -> sendMessageToAllPlayer(I18n.get("countdown_2"), "", 1, Sound.BLOCK_NOTE_BLOCK_BELL, 1), 40);
        scheduleRoundTask(roundToken, () -> sendMessageToAllPlayer(I18n.get("countdown_1"), "", 1, Sound.BLOCK_NOTE_BLOCK_BELL, 1), 60);
        scheduleRoundTask(roundToken, () -> {
            sendMessageToAllPlayer(I18n.get("go"), "", 1, Sound.BLOCK_NOTE_BLOCK_BELL, 2);
            for (Location spawn : getAllSpawns()) setSurrounding(spawn, Material.AIR);
            if (!isRoundActive(roundToken)) return;
            FoliaScheduler.runRegion(data.ballSpawn, () -> startRound(roundToken));
        }, 80);
    }

    private List<Location> getAllSpawns() {
        ArrayList<Location> allSpawns = new ArrayList<>();
        allSpawns.addAll(data.blueTeamSpawns);
        allSpawns.addAll(data.redTeamSpawns);
        return allSpawns;
    }

    private void startRound(int roundToken) {
        if (!isRoundActive(roundToken)) return;
        matchState = matchTimer > 0 ? IN_PROGRESS : OVERTIME;
        removeBall();
        generateBall(data, name, data.ballSpawn, null);
    }

    private int beginRoundSequence() {
        cancelRoundTasks(false);
        return roundGeneration.incrementAndGet();
    }

    private void scheduleGoalRestart() {
        cancelRoundTasks(false);
        int roundToken = roundGeneration.incrementAndGet();
        ScheduledTask task = FoliaScheduler.runGlobalLater(() -> {
            if (roundGeneration.get() != roundToken || canceled || matchState != GOAL) return;
            startDelayedRound();
        }, 20 * 3);
        roundTasks.add(task);
    }

    private void scheduleRoundTask(int roundToken, Runnable runnable, long delayTicks) {
        ScheduledTask task = FoliaScheduler.runGlobalLater(() -> {
            if (!isRoundActive(roundToken)) return;
            runnable.run();
        }, delayTicks);
        roundTasks.add(task);
    }

    private boolean isRoundActive(int roundToken) {
        MatchState state = matchState;
        return roundGeneration.get() == roundToken && !canceled && (state == IN_PROGRESS || state == GOAL || state == OVERTIME);
    }

    private void invalidateRoundSequence() {
        roundGeneration.incrementAndGet();
        cancelRoundTasks(true);
    }

    private void cancelRoundTasks(boolean clearBarriers) {
        for (ScheduledTask task : roundTasks) {
            if (task != null) task.cancel();
        }
        roundTasks.clear();
        if (clearBarriers) {
            for (Location spawn : getAllSpawns()) setSurrounding(spawn, Material.AIR);
        }
    }

    private static void surroundWith(Location base, Material block) {
        Block pos = base.getBlock();
        int[][] offsets = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        for (int[] offset : offsets) {
            pos.getRelative(offset[0], 0, offset[1]).setType(block);
            pos.getRelative(offset[0], 1, offset[1]).setType(block);
        }
        pos.getRelative(0, 2, 0).setType(block);
    }

    private static void setSurrounding(Location base, Material block) {
        FoliaScheduler.runRegion(base, () -> surroundWith(base, block));
    }

    public void replacePlayer(Player player) {
        replacePlayer(blueTeam, player);
        replacePlayer(redTeam, player);
        replacePlayer(spectatorTeam, player);
    }

    private static void replacePlayer(Set<Player> players, Player newPlayer) {
        UUID uuid = newPlayer.getUniqueId();
        Player oldPlayer = null;

        for (Player p : players) {
            if (p.getUniqueId().equals(uuid)) {
                oldPlayer = p;
                break;
            }
        }

        if (oldPlayer != null) {
            players.remove(oldPlayer);
            players.add(newPlayer);
        }
    }


    public void addPlayerToTeam(Player p, Team team) {
        if (p != null) {
            if (team.equals(Team.BLUE)) {
                blueTeam.add(p);
                redTeam.remove(p);
                spectatorTeam.remove(p);
            } else if (team.equals(Team.RED)) {
                redTeam.add(p);
                blueTeam.remove(p);
                spectatorTeam.remove(p);
            } else {
                spectatorTeam.add(p);
                blueTeam.remove(p);
                redTeam.remove(p);
            }
            sendPlayerMessage(p, I18n.format("your_team", "team", I18n.get(team == Team.BLUE ? "blue_name" : team == Team.SPECTATOR ? "spectator_name" : "red_name")));
        }
    }

    public void checkGoal(Location ballLocation) {
        if (matchState == IN_PROGRESS || matchState == OVERTIME) {

            int ballX = ballLocation.getBlockX();
            int ballZ = ballLocation.getBlockZ();
            for (Location blockLocation : data.blueTeamGoalBlocks) {
                if (ballX == blockLocation.getBlockX() &&
                        ballZ == blockLocation.getBlockZ()) {
                    goal(Team.RED);
                    return;
                }
            }

            for (Location blockLocation : data.redTeamGoalBlocks) {
                if (ballX == blockLocation.getBlockX() &&
                        ballZ == blockLocation.getBlockZ()) {
                    goal(Team.BLUE);
                    return;
                }
            }
        }
    }

    private void goal(Team team) {
        if (Team.BLUE.equals(team)) {
            blueScore++;
            triggerGoalAnimation(Team.BLUE);

        } else {
            redScore++;
            triggerGoalAnimation(Team.RED);
        }

        if (matchState == IN_PROGRESS && (data.maxGoal <= 0 || (blueScore != data.maxGoal && redScore != data.maxGoal))) {
            sendScoreToPlayer();
            matchState = GOAL;
            scheduleGoalRestart();
        } else {
            matchState = GOAL;
            endMatch();
        }
        destroyBall(name);
    }

    public void spawnFirework(Team team) {
        for (int i = 0; i < 3; i++) {
            int offset = i * 30;
            FoliaScheduler.runGlobalLater(() -> spawnFireworkFor(team), offset + 5);
            FoliaScheduler.runGlobalLater(() -> spawnFireworkFor(team), offset + 10);
            FoliaScheduler.runGlobalLater(() -> spawnFireworkFor(team), offset + 15);
        }
    }

    public void spawnFireworkFor(Team team) {
        Set<Player> players = team == Team.BLUE ? blueTeam : redTeam;
        for (Player player : players) {
            runForPlayer(player, p -> p.getWorld().spawnEntity(p.getLocation(), EntityType.FIREWORK_ROCKET));
        }
    }

    public void endMatch() {
        invalidateRoundSequence();
        String title;
        if (getBlueScore() > getRedScore()) {
            title = I18n.get("blue_win");
            spawnFirework(Team.BLUE);
        } else if (getBlueScore() < getRedScore()) {
            title = I18n.get("red_win");
            spawnFirework(Team.RED);
        } else {
            title = I18n.get("overtime");
            setMatchState(OVERTIME);
        }

        String score = ChatColor.BLUE.toString() + getBlueScore() + ChatColor.WHITE + " - " + ChatColor.RED + getRedScore();
        sendMessageToAllPlayer(title, score, 3, Sound.ENTITY_RABBIT_DEATH, 0.5f);

        ArrayList<Map.Entry<UUID, Integer>> list = new ArrayList<>(goals.entrySet());
        list.sort((e1, e2) -> Integer.compare(e2.getValue(), e1.getValue()));
        forEachPlayer(true, player -> {
            player.sendMessage(I18n.get("game_over"));
            player.sendMessage(I18n.get("goal_rank"));
            player.sendMessage(I18n.format("total_goals", "total", redScore + blueScore));
            int i = 0;
            for (Map.Entry<UUID, Integer> entry : list) {
                player.sendMessage(I18n.format("player_goal",
                        "rank", ++i,
                        "color", (blueTeam.stream().anyMatch(p -> p.getUniqueId().equals(entry.getKey())) ? ChatColor.BLUE : ChatColor.RED),
                        "name", getName(entry.getKey()),
                        "goals", entry.getValue()
                ));
            }
        });

        removeBall();
        forEachPlayer(true, PlayerStateCache::restore);
        reset();
    }

    private static String getName(UUID uuid) {
        if (uuid == null) return "null";
        OfflinePlayer p = Bukkit.getOfflinePlayer(uuid);
        String name = p.getName();
        return name != null ? name : uuid.toString();
    }

    public void reset() {
        invalidateRoundSequence();
        setMatchState(READY);
        blueScore = 0;
        redScore = 0;
        goals.clear();
        canceled = false;
    }

    public void cancel() {
        invalidateRoundSequence();
        removeBall();
        reset();
        canceled = true;
        forEachPlayer(true, p -> {
            PlayerStateCache.restore(p);
            p.sendMessage(I18n.get("match_canceled"));
        });
    }

    public void sendScoreToPlayer() {
        String title = I18n.format("score_title", "blue", blueScore, "red", redScore);
        String subtitle = I18n.format("score_subtitle", "name", getName(lastTouchPlayer).toUpperCase(), "speed", computeSpeedGoal());
        if (lastTouchPlayer != null) {
            goals.put(lastTouchPlayer, goals.getOrDefault(lastTouchPlayer, 0) + 1);
        }
        sendMessageToAllPlayer(title, subtitle, 3, Sound.WEATHER_RAIN, 0.5f);
    }

    public double computeSpeedGoal() {

        Ball ball = balls.get(name);

        if (ball != null && ball.getBall() != null) {
            return Math.round((Math.abs((ball.getLastVelocity().getX())) + Math.abs((ball.getLastVelocity().getY())) + Math.abs((ball.getLastVelocity().getZ()))) * 100);
        }
        return 0;
    }

    public void sendMessageToAllPlayer(String title, String subtitle, int duration, Sound sound, float pitch) {
        send(blueTeam, title, subtitle, duration, sound, pitch);
        send(redTeam, title, subtitle, duration, sound, pitch);
        send(spectatorTeam, title, subtitle, duration, sound, pitch);
    }

    private void send(Set<Player> team, String title, String subtitle, int duration, Sound sound, float pitch) {
        team.forEach(player -> {
            if (player != null) {
                runForPlayer(player, p -> {
                    p.sendTitle(title, subtitle, 1, duration * 20, 1);
                    p.playSound(p.getLocation(), sound, 10, pitch);
                });
            }
        });
    }

    private void forEachPlayer(boolean spectator, Consumer<Player> action) {
        for (Player player : getAllPlayer(spectator)) {
            runForPlayer(player, action);
        }
    }

    private void sendPlayerMessage(Player player, String message) {
        runForPlayer(player, p -> p.sendMessage(message));
    }

    private void runForPlayer(Player player, Consumer<Player> action) {
        if (player == null) return;
        FoliaScheduler.runEntity(player, () -> {
            if (player.isOnline()) {
                action.accept(player);
            }
        });
    }

    public void triggerGoalAnimation(Team team) {
        if (team.equals(Team.BLUE)) {
            data.redTeamGoalBlocks.forEach(block -> {
                FoliaScheduler.runRegion(block, () -> {
                    Location location = block.getBlock().getLocation();
                    Objects.requireNonNull(block.getWorld()).spawnEntity(location, EntityType.FIREWORK_ROCKET);
                    Objects.requireNonNull(block.getWorld()).playEffect(location, Effect.VILLAGER_PLANT_GROW, 3);
                });
            });
        } else {
            data.blueTeamGoalBlocks.forEach(block -> {
                FoliaScheduler.runRegion(block, () -> {
                    Location location = block.getBlock().getLocation();
                    Objects.requireNonNull(block.getWorld()).spawnEntity(location, EntityType.FIREWORK_ROCKET);
                    Objects.requireNonNull(block.getWorld()).playEffect(location, Effect.VILLAGER_PLANT_GROW, 3);
                });
            });

        }
    }

    public String buildTeam() {
        StringBuilder sb = new StringBuilder();
        sb.append(I18n.format("blue_team", "count", this.blueTeam.size())).append('\n');
        this.blueTeam.forEach(player -> {
            if (player != null) {
                sb.append("- ").append(ChatColor.BLUE).append(player.getDisplayName()).append('\n');
            }
        });

        sb.append(I18n.format("red_team", "count", this.redTeam.size())).append('\n');
        this.redTeam.forEach(player -> {
            if (player != null) {
                sb.append("- ").append(ChatColor.RED).append(player.getDisplayName()).append('\n');
            }
        });

        sb.append(I18n.format("spectator_team", "count", this.spectatorTeam.size())).append('\n');
        this.spectatorTeam.forEach(player -> {
            if (player != null) {
                sb.append("- ").append(ChatColor.GREEN).append(player.getDisplayName()).append('\n');
            }
        });
        return sb.toString();
    }

    public String getName() {
        return name;
    }

    public void setLastTouchPlayer(Player lastTouchPlayer) {
        this.lastTouchPlayer = lastTouchPlayer.getUniqueId();
    }

    public Set<Player> getBlueTeam() {
        return blueTeam;
    }

    public Set<Player> getRedTeam() {
        return redTeam;
    }

    public Set<Player> getSpectatorTeam() {
        return spectatorTeam;
    }

    public ArrayList<Player> getAllPlayer(boolean spectator) {
        ArrayList<Player> team = new ArrayList<>();
        team.addAll(getRedTeam());
        team.addAll(getBlueTeam());
        if (spectator) team.addAll(getSpectatorTeam());
        return team;
    }

    public boolean containsPlayer(Player player) {
        if (getRedTeam().contains(player)) return true;
        return getBlueTeam().contains(player);
    }

    public int getBlueScore() {
        return blueScore;
    }

    public int getRedScore() {
        return redScore;
    }

    public MatchState getMatchState() {
        return matchState;
    }

    public void setMatchState(MatchState matchState) {
        this.matchState = matchState;
    }

    public MatchData getData() {
        return data;
    }

    public void removeBall() {
        destroyBall(name);
    }

    public boolean pause() {
        if (matchState == IN_PROGRESS || matchState == OVERTIME) {
            matchState = PAUSED;
            removeBall();
            return true;
        }
        return false;
    }

    public boolean resume() {
        if (matchState == PAUSED) {
            matchState = matchTimer > 0 ? IN_PROGRESS : OVERTIME;
            startDelayedRound();
            return true;
        }
        return false;
    }

    public boolean isInProgress() {
        return matchState == IN_PROGRESS || matchState == OVERTIME || matchState == GOAL || matchState == PAUSED;
    }

    private void equipTeamKit(Player player) {
        org.bukkit.inventory.ItemStack chest = new org.bukkit.inventory.ItemStack(Material.LEATHER_CHESTPLATE);
        org.bukkit.Color color = blueTeam.contains(player) ? org.bukkit.Color.BLUE : org.bukkit.Color.RED;
        if (chest.getItemMeta() instanceof org.bukkit.inventory.meta.LeatherArmorMeta) {
            org.bukkit.inventory.meta.LeatherArmorMeta meta = (org.bukkit.inventory.meta.LeatherArmorMeta) chest.getItemMeta();
            meta.setColor(color);
            chest.setItemMeta(meta);
        }
        player.getInventory().setChestplate(chest);
    }
}
