package me.crylonz;

import com.github.squi2rel.cb.I18n;
import com.github.squi2rel.cb.MatchData;
import com.github.squi2rel.cb.util.FoliaScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
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
    private final Map<UUID, Double> originalScales = new ConcurrentHashMap<>();
    private volatile MatchState matchState;
    private volatile UUID lastTouchPlayer;
    private volatile UUID lastGoalPlayer;
    private volatile Team lastGoalTeam;
    private volatile boolean lastGoalVoidable;
    private volatile int blueScore = 0;
    private volatile int redScore = 0;
    private volatile boolean canceled;
    private volatile boolean pendingTechnicalPause;
    private volatile boolean technicalPauseActive;
    private volatile boolean roundCountdownActive;
    private volatile boolean unpauseVoteActive;
    private volatile UUID unpauseVoteStarter;
    private final Set<UUID> unpauseAgreeVotes = ConcurrentHashMap.newKeySet();
    private final Set<UUID> unpauseDenyVotes = ConcurrentHashMap.newKeySet();
    private volatile int blueTechnicalPauses;
    private volatile int redTechnicalPauses;
    private volatile int blueRsRequests;
    private volatile int redRsRequests;
    private volatile RsRequest pendingRsRequest;
    private volatile int rsRequestSequence;
    private final Set<UUID> rsSuspendedPlayers = ConcurrentHashMap.newKeySet();
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
                    boolean nearGoal = data.isNearAnyGoal(player.getLocation(), 100.0);
                    boolean nearField = player.getLocation().distance(data.ballSpawn) < 256 || nearGoal;
                    if (nearField) {
                        if (!blueTeam.contains(player) && !redTeam.contains(player) && !spectatorTeam.contains(player)) {
                            addPlayerToTeam(player, Team.SPECTATOR);
                            if (nearGoal) sendPlayerMessage(player, I18n.get("spectator_auto_join"));
                        }
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
        startDelayedRound(80L);
    }

    private void startDelayedRound(long goDelayTicks) {
        if (pendingTechnicalPause) {
            startTechnicalPause();
            return;
        }
        int roundToken = beginRoundSequence();
        roundCountdownActive = true;
        teleportTeam(blueTeam, data.blueTeamSpawns);
        teleportTeam(redTeam, data.redTeamSpawns);

        PotionEffect effect = new PotionEffect(PotionEffectType.SLOWNESS, 60, 255);
        List<Location> allSpawns = getAllSpawns();
        for (Location spawn : allSpawns) setSurrounding(spawn, Material.BARRIER);
        forEachPlayer(false, player -> {
            normalizePlayerForMatch(player);
            if (!PlayerStateCache.has(player)) PlayerStateCache.save(player);
            PlayerStateCache.clear(player);
            applyTeamKit(player, getPlayingTeam(player));
            player.setAllowFlight(false);
            player.setFlying(false);
            player.setVelocity(new Vector(0, 0, 0));
            player.addPotionEffect(effect);
        });
        if (goDelayTicks > 80L) {
            for (long ticksLeft = goDelayTicks; ticksLeft > 0L; ticksLeft -= 20L) {
                long delay = goDelayTicks - ticksLeft;
                int secondsLeft = (int) (ticksLeft / 20L);
                scheduleRoundTask(roundToken, () -> sendMessageToAllPlayer("§e" + secondsLeft, "", 1, Sound.BLOCK_NOTE_BLOCK_BELL, 1), Math.max(1L, delay));
            }
        } else {
            scheduleRoundTask(roundToken, () -> sendMessageToAllPlayer(I18n.get("countdown_3"), "", 1, Sound.BLOCK_NOTE_BLOCK_BELL, 1), 20);
            scheduleRoundTask(roundToken, () -> sendMessageToAllPlayer(I18n.get("countdown_2"), "", 1, Sound.BLOCK_NOTE_BLOCK_BELL, 1), 40);
            scheduleRoundTask(roundToken, () -> sendMessageToAllPlayer(I18n.get("countdown_1"), "", 1, Sound.BLOCK_NOTE_BLOCK_BELL, 1), 60);
        }
        scheduleRoundTask(roundToken, () -> {
            sendMessageToAllPlayer(I18n.get("go"), "", 1, Sound.BLOCK_NOTE_BLOCK_BELL, 2);
            for (Location spawn : getAllSpawns()) setSurrounding(spawn, Material.AIR);
            if (!isRoundActive(roundToken)) return;
            FoliaScheduler.runRegion(data.ballSpawn, () -> startRound(roundToken));
        }, goDelayTicks);
    }

    private List<Location> getAllSpawns() {
        ArrayList<Location> allSpawns = new ArrayList<>();
        allSpawns.addAll(data.blueTeamSpawns);
        allSpawns.addAll(data.redTeamSpawns);
        return allSpawns;
    }

    private void startRound(int roundToken) {
        if (!isRoundActive(roundToken)) return;
        roundCountdownActive = false;
        lastGoalVoidable = false;
        lastGoalTeam = null;
        lastGoalPlayer = null;
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

    private void startTechnicalPause() {
        pendingTechnicalPause = false;
        technicalPauseActive = true;
        clearUnpauseVote();
        invalidateRoundSequence();
        matchState = PAUSED;
        sendTextToAllPlayer("[CCB] 技术暂停开始，60秒后自动继续。");
        scheduleTechnicalPauseActionBar();
        ScheduledTask task = FoliaScheduler.runGlobalLater(() -> {
            if (!technicalPauseActive || canceled || matchState != PAUSED || pendingRsRequest != null) return;
            resumeFromTechnicalPause("[CCB] 技术暂停结束，准备开球。");
        }, 20 * 60);
        roundTasks.add(task);
    }

    private void scheduleTechnicalPauseActionBar() {
        for (int elapsed = 0; elapsed < 60; elapsed++) {
            int secondsLeft = 60 - elapsed;
            ScheduledTask task = FoliaScheduler.runGlobalLater(() -> {
                if (!technicalPauseActive || canceled || matchState != PAUSED || pendingRsRequest != null) return;
                sendActionBarToAllPlayer("技术暂停剩余 " + secondsLeft + " 秒");
            }, elapsed * 20L + 1L);
            roundTasks.add(task);
        }
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
        roundCountdownActive = false;
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
            if (team != Team.SPECTATOR && rsSuspendedPlayers.contains(p.getUniqueId())) {
                spectatorTeam.add(p);
                blueTeam.remove(p);
                redTeam.remove(p);
                sendPlayerMessage(p, "[CCB] 滥用rs，下一次有人进球后才能继续参赛。");
                return;
            }
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
                enableSpectatorFlight(p);
            }
            sendPlayerMessage(p, I18n.format("your_team", "team", I18n.get(team == Team.BLUE ? "blue_name" : team == Team.SPECTATOR ? "spectator_name" : "red_name")));
        }
    }

    public boolean isConfiguredForStart() {
        return data.ballSpawn != null
                && !data.blueTeamSpawns.isEmpty()
                && !data.redTeamSpawns.isEmpty()
                && data.hasBlueTeamGoalArea()
                && data.hasRedTeamGoalArea();
    }

    public void prepareLobbyTeams(Collection<Player> redPlayers, Collection<Player> bluePlayers, Collection<Player> spectatorPlayers) {
        redTeam.clear();
        blueTeam.clear();
        spectatorTeam.clear();

        addLobbyPlayers(redTeam, redPlayers);
        addLobbyPlayers(blueTeam, bluePlayers);
        addLobbyPlayers(spectatorTeam, spectatorPlayers);
        spectatorTeam.removeAll(redTeam);
        spectatorTeam.removeAll(blueTeam);
        for (Player spectator : spectatorTeam) {
            runForPlayer(spectator, player -> {
                PlayerStateCache.clear(player);
                enableSpectatorFlight(player);
                JoinSignManager.giveActiveSpectatorItem(player);
            });
        }

        if ((matchState == CREATED || matchState == READY) && isConfiguredForStart()) {
            matchState = READY;
        }
    }

    private void addLobbyPlayers(Set<Player> target, Collection<Player> players) {
        if (players == null) return;
        for (Player player : players) {
            if (player != null && player.isOnline()) target.add(player);
        }
    }

    private void enableSpectatorFlight(Player player) {
        if (!PlayerStateCache.has(player)) PlayerStateCache.save(player);
        player.setAllowFlight(true);
        player.setFlying(true);
    }

    public void checkGoal(Location ballLocation) {
        if (matchState == IN_PROGRESS || matchState == OVERTIME) {
            if (data.isInBlueTeamGoal(ballLocation)) {
                goal(Team.RED);
                return;
            }

            if (data.isInRedTeamGoal(ballLocation)) {
                goal(Team.BLUE);
            }
        }
    }

    public void checkGoal(Entity ball) {
        if (ball == null) return;
        if (matchState == IN_PROGRESS || matchState == OVERTIME) {
            if (data.intersectsBlueTeamGoal(ball.getWorld(), ball.getBoundingBox()) || data.isInBlueTeamGoal(ball.getLocation())) {
                goal(Team.RED);
                return;
            }

            if (data.intersectsRedTeamGoal(ball.getWorld(), ball.getBoundingBox()) || data.isInRedTeamGoal(ball.getLocation())) {
                goal(Team.BLUE);
            }
        }
    }

    private void goal(Team team) {
        rsSuspendedPlayers.clear();
        lastGoalTeam = team;
        lastGoalPlayer = lastTouchPlayer;
        lastGoalVoidable = true;
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
        restorePlayerScales();
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
        blueTechnicalPauses = 0;
        redTechnicalPauses = 0;
        blueRsRequests = 0;
        redRsRequests = 0;
        pendingTechnicalPause = false;
        technicalPauseActive = false;
        roundCountdownActive = false;
        clearUnpauseVote();
        pendingRsRequest = null;
        lastGoalTeam = null;
        lastGoalPlayer = null;
        lastGoalVoidable = false;
        rsSuspendedPlayers.clear();
        canceled = false;
    }

    public void cancel() {
        invalidateRoundSequence();
        removeBall();
        restorePlayerScales();
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

    private void sendTextToAllPlayer(String message) {
        forEachPlayer(true, player -> player.sendMessage(message));
    }

    private void sendActionBarToAllPlayer(String message) {
        forEachPlayer(true, player -> player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message)));
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
        Location location = team.equals(Team.BLUE) ? data.getRedTeamGoalCenter() : data.getBlueTeamGoalCenter();
        if (location == null || location.getWorld() == null) return;
        FoliaScheduler.runRegion(location, () -> {
            Location blockLocation = location.getBlock().getLocation();
            Objects.requireNonNull(blockLocation.getWorld()).spawnEntity(blockLocation, EntityType.FIREWORK_ROCKET);
            Objects.requireNonNull(blockLocation.getWorld()).playEffect(blockLocation, Effect.VILLAGER_PLANT_GROW, 3);
        });
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

    public boolean containsPlayer(UUID playerId) {
        if (playerId == null) return false;
        return blueTeam.stream().anyMatch(player -> player != null && playerId.equals(player.getUniqueId()))
                || redTeam.stream().anyMatch(player -> player != null && playerId.equals(player.getUniqueId()));
    }

    public Team getPlayingTeam(Player player) {
        if (player == null) return Team.SPECTATOR;
        if (blueTeam.contains(player)) return Team.BLUE;
        if (redTeam.contains(player)) return Team.RED;
        return Team.SPECTATOR;
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

    public boolean canUseDash() {
        return matchState == IN_PROGRESS && !roundCountdownActive;
    }

    public boolean requestTechnicalPause(Player player) {
        Team team = getPlayingTeam(player);
        if (team == Team.SPECTATOR || !isInProgress()) return false;
        if (pendingTechnicalPause || technicalPauseActive) {
            sendPlayerMessage(player, "[CCB] 已有技术暂停等待执行。");
            return true;
        }
        int used = getTechnicalPauseCount(team);
        if (used >= 2) {
            sendPlayerMessage(player, "[CCB] 本局本队技术暂停次数已用完。");
            return true;
        }
        setTechnicalPauseCount(team, used + 1);
        if (roundCountdownActive) {
            sendTextToAllPlayer("[CCB] " + teamName(team) + "申请技术暂停，立即暂停60秒。剩余 " + (1 - used) + " 次。");
            startTechnicalPause();
            return true;
        }
        pendingTechnicalPause = true;
        sendTextToAllPlayer("[CCB] " + teamName(team) + "申请技术暂停，将在下次开球前暂停60秒。剩余 " + (1 - used) + " 次。");
        return true;
    }

    public boolean requestUnpauseVote(Player player) {
        Team team = getPlayingTeam(player);
        if (team == Team.SPECTATOR) return false;
        if (!technicalPauseActive || matchState != PAUSED || pendingRsRequest != null) {
            sendPlayerMessage(player, "[CCB] 当前没有可取消的技术暂停。");
            return true;
        }
        int total = getActivePlayerCount();
        if (total <= 1) {
            resumeFromTechnicalPause("[CCB] 技术暂停已由唯一参赛玩家取消，准备开球。");
            return true;
        }
        unpauseVoteActive = true;
        unpauseVoteStarter = player.getUniqueId();
        unpauseAgreeVotes.clear();
        unpauseDenyVotes.clear();
        unpauseAgreeVotes.add(player.getUniqueId());
        sendTextToAllPlayer("[CCB] " + player.getName() + " 发起取消技术暂停投票。输入 .agree 同意，.deny 反对。需要 " + requiredUnpauseVotes(total) + "/" + total + " 票同意。");
        tryFinishUnpauseVote();
        return true;
    }

    public boolean voteUnpause(Player player, boolean agree) {
        Team team = getPlayingTeam(player);
        if (team == Team.SPECTATOR) return false;
        if (!technicalPauseActive || matchState != PAUSED || pendingRsRequest != null) {
            sendPlayerMessage(player, "[CCB] 当前没有可取消的技术暂停。");
            return true;
        }
        if (!unpauseVoteActive) {
            sendPlayerMessage(player, "[CCB] 当前没有取消技术暂停投票，请先输入 .un 发起。");
            return true;
        }
        UUID uuid = player.getUniqueId();
        if (agree) {
            unpauseDenyVotes.remove(uuid);
            unpauseAgreeVotes.add(uuid);
        } else {
            unpauseAgreeVotes.remove(uuid);
            unpauseDenyVotes.add(uuid);
        }
        int total = getActivePlayerCount();
        sendTextToAllPlayer("[CCB] 取消技术暂停投票: 同意 " + currentAgreeVotes() + "/" + requiredUnpauseVotes(total) + "，反对 " + currentDenyVotes() + "。");
        tryFinishUnpauseVote();
        return true;
    }

    public String forceUnpause() {
        if (!technicalPauseActive || matchState != PAUSED) return "当前没有技术暂停";
        resumeFromTechnicalPause("[CCB] 管理员已强制取消技术暂停，准备开球。");
        return "已强制取消技术暂停";
    }

    public boolean hasPlayer(Player player) {
        if (player == null) return false;
        return blueTeam.contains(player) || redTeam.contains(player) || spectatorTeam.contains(player);
    }

    public boolean isSpectator(Player player) {
        return player != null && spectatorTeam.contains(player);
    }

    public boolean removeSpectator(Player player) {
        if (!isSpectator(player)) return false;
        spectatorTeam.remove(player);
        runForPlayer(player, PlayerStateCache::restore);
        return true;
    }

    private void normalizePlayerForMatch(Player player) {
        Attribute attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("scale"));
        if (attribute != null) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null) {
                originalScales.putIfAbsent(player.getUniqueId(), instance.getBaseValue());
                instance.setBaseValue(1.0D);
            }
        }
        EmotecraftHook.stopEmote(player.getUniqueId());
    }

    private void restorePlayerScales() {
        for (Map.Entry<UUID, Double> entry : originalScales.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) continue;
            double scale = entry.getValue();
            runForPlayer(player, target -> {
                Attribute attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("scale"));
                AttributeInstance instance = attribute == null ? null : target.getAttribute(attribute);
                if (instance != null) instance.setBaseValue(scale);
            });
        }
        originalScales.clear();
    }

    private void tryFinishUnpauseVote() {
        if (!unpauseVoteActive || !technicalPauseActive || matchState != PAUSED) return;
        int total = getActivePlayerCount();
        int required = requiredUnpauseVotes(total);
        if (currentAgreeVotes() >= required) {
            resumeFromTechnicalPause("[CCB] 取消技术暂停投票通过，准备开球。");
        }
    }

    private void resumeFromTechnicalPause(String message) {
        technicalPauseActive = false;
        clearUnpauseVote();
        matchState = matchTimer > 0 ? IN_PROGRESS : OVERTIME;
        sendTextToAllPlayer(message);
        startDelayedRound(200L);
    }

    private void clearUnpauseVote() {
        unpauseVoteActive = false;
        unpauseVoteStarter = null;
        unpauseAgreeVotes.clear();
        unpauseDenyVotes.clear();
    }

    private int getActivePlayerCount() {
        return (int) getAllPlayer(false).stream()
                .filter(player -> player != null && player.isOnline())
                .count();
    }

    private int currentAgreeVotes() {
        return countCurrentVotes(unpauseAgreeVotes);
    }

    private int currentDenyVotes() {
        return countCurrentVotes(unpauseDenyVotes);
    }

    private int countCurrentVotes(Set<UUID> votes) {
        int count = 0;
        for (Player player : getAllPlayer(false)) {
            if (player != null && player.isOnline() && votes.contains(player.getUniqueId())) count++;
        }
        return count;
    }

    private int requiredUnpauseVotes(int total) {
        if (total <= 1) return 1;
        if (total <= 4) return total;
        return (int) Math.ceil(total * 0.8D);
    }

    public boolean requestRs(Player player, String reason) {
        Team team = getPlayingTeam(player);
        if (team == Team.SPECTATOR || !isInProgress()) return false;
        if (reason == null || reason.isBlank()) {
            sendPlayerMessage(player, "[CCB] 用法: .rs 原因");
            return true;
        }
        if (pendingRsRequest != null) {
            sendPlayerMessage(player, "[CCB] 已有rs请求等待管理员审核。");
            return true;
        }
        int used = getRsRequestCount(team);
        if (used >= 2) {
            sendPlayerMessage(player, "[CCB] 本局本队rs次数已用完。");
            return true;
        }
        setRsRequestCount(team, used + 1);
        MatchState requestState = matchState;
        boolean canRollbackGoal = requestState == GOAL && lastGoalVoidable && lastGoalTeam != null;
        int requestId = ++rsRequestSequence;
        pendingRsRequest = new RsRequest(requestId, player.getUniqueId(), player.getName(), team, reason.trim(), canRollbackGoal);
        invalidateRoundSequence();
        removeBall();
        matchState = PAUSED;
        sendTextToAllPlayer("[CCB] " + player.getName() + " 提出了rs请求，对局进入开局等待并暂停审核。理由: " + reason.trim());
        notifyAdminsRsRequest(pendingRsRequest);
        return true;
    }

    private void suspendRsPlayer(Player player) {
        rsSuspendedPlayers.add(player.getUniqueId());
        blueTeam.remove(player);
        redTeam.remove(player);
        spectatorTeam.add(player);
        sendPlayerMessage(player, "[CCB] 滥用rs，下一次有人进球后才能继续参赛。");
    }

    private void notifyAdminsRsRequest(RsRequest request) {
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (!admin.hasPermission("cubeball.admin")) continue;
            TextComponent message = new TextComponent("[CCB] 新的rs请求: 比赛=" + name + " 玩家=" + request.playerName
                    + " 队伍=" + teamName(request.team) + " 理由=" + request.reason
                    + (request.rollbackGoal ? " 操作=同意后回档上次比分 " : " 操作=同意后进入开局等待 "));
            TextComponent approve = new TextComponent("[✅]");
            approve.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ccb rsreview " + name + " " + request.id + " approve"));
            approve.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("同意并撤销最近一次进球").create()));
            TextComponent deny = new TextComponent(" [❌]");
            deny.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ccb rsreview " + name + " " + request.id + " deny"));
            deny.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("不同意，继续比赛").create()));
            message.addExtra(approve);
            message.addExtra(deny);
            runForPlayer(admin, p -> p.spigot().sendMessage(message));
        }
    }

    public String reviewRs(Player admin, int requestId, boolean approve) {
        if (pendingRsRequest == null || pendingRsRequest.id != requestId) return "rs请求不存在或已处理";
        RsRequest request = pendingRsRequest;
        pendingRsRequest = null;
        if (approve) {
            if (request.rollbackGoal) {
                if (!voidLastGoal()) {
                    resumeAfterReview();
                    return "最近进球已无法作废，比赛已继续";
                }
                sendTextToAllPlayer("[CCB] 管理员同意rs，已撤销最近一次进球。理由: " + request.reason);
            } else {
                sendTextToAllPlayer("[CCB] 管理员同意rs，当前球权作废，进入开局等待。理由: " + request.reason);
            }
        } else {
            suspendRsPlayer(request.playerId, request.playerName);
            sendTextToAllPlayer("[CCB] 管理员拒绝rs，判定为滥用rs，比赛继续。理由: " + request.reason);
        }
        resumeAfterReview();
        return approve ? "已同意rs" : "已拒绝rs";
    }

    private void resumeAfterReview() {
        technicalPauseActive = false;
        matchState = matchTimer > 0 ? IN_PROGRESS : OVERTIME;
        startDelayedRound();
    }

    private boolean voidLastGoal() {
        if (!lastGoalVoidable || lastGoalTeam == null) return false;
        if (lastGoalTeam == Team.BLUE) blueScore = Math.max(0, blueScore - 1);
        if (lastGoalTeam == Team.RED) redScore = Math.max(0, redScore - 1);
        if (lastGoalPlayer != null) {
            goals.computeIfPresent(lastGoalPlayer, (uuid, count) -> count <= 1 ? null : count - 1);
        }
        lastGoalVoidable = false;
        lastGoalTeam = null;
        lastGoalPlayer = null;
        return true;
    }

    private void suspendRsPlayer(UUID playerId, String playerName) {
        rsSuspendedPlayers.add(playerId);
        blueTeam.removeIf(player -> player != null && player.getUniqueId().equals(playerId));
        redTeam.removeIf(player -> player != null && player.getUniqueId().equals(playerId));
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            spectatorTeam.add(player);
            sendPlayerMessage(player, "[CCB] 滥用rs，下一次有人进球后才能继续参赛。");
        } else {
            spectatorTeam.removeIf(p -> p != null && p.getUniqueId().equals(playerId));
        }
    }

    private int getTechnicalPauseCount(Team team) {
        return team == Team.BLUE ? blueTechnicalPauses : redTechnicalPauses;
    }

    private void setTechnicalPauseCount(Team team, int count) {
        if (team == Team.BLUE) blueTechnicalPauses = count;
        else redTechnicalPauses = count;
    }

    private int getRsRequestCount(Team team) {
        return team == Team.BLUE ? blueRsRequests : redRsRequests;
    }

    private void setRsRequestCount(Team team, int count) {
        if (team == Team.BLUE) blueRsRequests = count;
        else redRsRequests = count;
    }

    private String teamName(Team team) {
        if (team == Team.BLUE) return "蓝队";
        if (team == Team.RED) return "红队";
        return "观众";
    }

    public void applyTeamKit(Player player, Team team) {
        if (player == null) return;
        if (team == Team.SPECTATOR) {
            ItemStack chestplate = player.getInventory().getChestplate();
            if (isTeamKit(chestplate)) {
                player.getInventory().setChestplate(null);
            }
            player.updateInventory();
            return;
        }

        ItemStack chest = new ItemStack(Material.LEATHER_CHESTPLATE);
        org.bukkit.Color color = team == Team.BLUE ? org.bukkit.Color.BLUE : org.bukkit.Color.RED;
        if (chest.getItemMeta() instanceof org.bukkit.inventory.meta.LeatherArmorMeta) {
            org.bukkit.inventory.meta.LeatherArmorMeta meta = (org.bukkit.inventory.meta.LeatherArmorMeta) chest.getItemMeta();
            meta.setColor(color);
            meta.getPersistentDataContainer().set(teamKitKey(), PersistentDataType.BYTE, (byte) 1);
            chest.setItemMeta(meta);
        }
        player.getInventory().setChestplate(chest);
        player.updateInventory();
    }

    public static boolean isTeamKit(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(teamKitKey(), PersistentDataType.BYTE);
    }

    private static NamespacedKey teamKitKey() {
        return new NamespacedKey(plugin, "team_kit");
    }

    private static final class RsRequest {
        final int id;
        final UUID playerId;
        final String playerName;
        final Team team;
        final String reason;
        final boolean rollbackGoal;

        RsRequest(int id, UUID playerId, String playerName, Team team, String reason, boolean rollbackGoal) {
            this.id = id;
            this.playerId = playerId;
            this.playerName = playerName;
            this.team = team;
            this.reason = reason;
            this.rollbackGoal = rollbackGoal;
        }
    }
}
