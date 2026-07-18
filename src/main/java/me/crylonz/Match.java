package me.crylonz;

import com.github.squi2rel.cb.I18n;
import com.github.squi2rel.cb.MatchData;
import com.github.squi2rel.cb.util.FoliaScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
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
    private volatile int blueScore = 0;
    private volatile int redScore = 0;
    private volatile boolean canceled;
    private volatile boolean roundCountdownActive;
    private final AtomicInteger roundGeneration = new AtomicInteger();
    private final AtomicInteger scanGeneration = new AtomicInteger();
    private final Set<ScheduledTask> roundTasks = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pauseGeneration = new AtomicInteger();
    private final AtomicInteger voteGeneration = new AtomicInteger();
    private volatile PauseType pauseType = PauseType.NONE;
    private volatile ScheduledTask pauseExpiryTask;
    private volatile ScheduledTask pauseVoteTask;
    private volatile PauseVote pauseVote;
    private volatile Team pauseTeam;
    private volatile boolean blueTimeoutUsed;
    private volatile boolean redTimeoutUsed;
    private final MatchData data;

    public Match(String name, Player player) {
        this(name, MatchData.create(player.getName(), player.getUniqueId()));
    }
    public Match(String name, MatchData config) {
        this.name = name;
        this.data = config;
        matchState = CREATED;
    }

    public synchronized void scanPlayer() {
        if (isInProgress()) return;
        int scanToken = scanGeneration.incrementAndGet();
        canceled = false;
        matchState = CREATED;
        List<Player> previousPlayers = getAllPlayer(true);
        blueTeam.clear();
        redTeam.clear();
        spectatorTeam.clear();
        for (Player previous : previousPlayers) {
            runForPlayer(previous, player -> {
                if (hasPlayer(player) || isPlayerInOtherActiveMatch(player.getUniqueId())) return;
                PotionEffect effect = player.getPotionEffect(PotionEffectType.SLOWNESS);
                if (effect != null && effect.getAmplifier() >= 255) player.removePotionEffect(PotionEffectType.SLOWNESS);
                disableSpectatorState(player);
                PlayerStateCache.restore(player);
            });
        }

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
            scanNearPlayers(spawn, Team.BLUE, remaining, scanToken);
        }
        for (Location spawn : redSpawns) {
            scanNearPlayers(spawn, Team.RED, remaining, scanToken);
        }

        World world = data.ballSpawn.getWorld();
        for (Player player : onlinePlayers) {
            FoliaScheduler.runEntity(player, () -> {
                try {
                    synchronized (Match.this) {
                        if (scanGeneration.get() != scanToken || matchState != CREATED || canceled) return;
                        if (player.getWorld() != world) return;
                        boolean nearGoal = data.isNearAnyGoal(player.getLocation(), 100.0);
                        boolean nearField = player.getLocation().distance(data.ballSpawn) < 256 || nearGoal;
                        if (nearField && !isExiting(player.getUniqueId()) && !isPlayerInOtherActiveMatch(player.getUniqueId())
                                && !blueTeam.contains(player) && !redTeam.contains(player) && !spectatorTeam.contains(player)) {
                            addPlayerToTeam(player, Team.SPECTATOR);
                            if (nearGoal) sendPlayerMessage(player, I18n.get("spectator_auto_join"));
                        }
                    }
                } finally {
                    finishScan(remaining, scanToken);
                }
            }, () -> finishScan(remaining, scanToken));
        }
    }

    private void scanNearPlayers(Location spawn, Team team, AtomicInteger remaining, int scanToken) {
        if (spawn == null) {
            finishScan(remaining, scanToken);
            return;
        }
        FoliaScheduler.runRegion(spawn, () -> {
            try {
                synchronized (Match.this) {
                    if (scanGeneration.get() != scanToken || matchState != CREATED || canceled) return;
                    World world = Objects.requireNonNull(spawn.getWorld());
                    for (Entity entity : world.getNearbyEntities(spawn, 1, 1, 1)) {
                        if (entity instanceof Player) {
                            Player player = (Player) entity;
                            if (player.getVehicle() == null && !isExiting(player.getUniqueId())
                                    && !isPlayerInOtherActiveMatch(player.getUniqueId())) addPlayerToTeam(player, team);
                        }
                    }
                }
            } finally {
                finishScan(remaining, scanToken);
            }
        });
    }

    private synchronized void finishScan(AtomicInteger remaining, int scanToken) {
        if (remaining.decrementAndGet() == 0 && scanGeneration.get() == scanToken && matchState == CREATED && !canceled) {
            matchState = READY;
        }
    }

    public synchronized void start(Player p) {
        if (matchState == READY) {
            if (getAllPlayer(true).stream().anyMatch(player -> player != null && isExiting(player.getUniqueId()))) {
                sendPlayerMessage(p, I18n.get("match_not_ready"));
                return;
            }
            reset();
            blueTeam.removeIf(player -> player == null || isPlayerInOtherActiveMatch(player.getUniqueId()));
            redTeam.removeIf(player -> player == null || isPlayerInOtherActiveMatch(player.getUniqueId()));
            spectatorTeam.removeIf(player -> player == null || isPlayerInOtherActiveMatch(player.getUniqueId()));
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

    private synchronized void startDelayedRound() {
        startDelayedRound(80L, false);
    }

    private synchronized void startDelayedRound(long goDelayTicks) {
        startDelayedRound(goDelayTicks, false);
    }

    private synchronized void startDelayedRound(long goDelayTicks, boolean resumeCountdown) {
        int roundToken = beginRoundSequence();
        roundCountdownActive = true;
        teleportTeam(blueTeam, data.blueTeamSpawns);
        teleportTeam(redTeam, data.redTeamSpawns);

        PotionEffect effect = new PotionEffect(PotionEffectType.SLOWNESS, 60, 255);
        List<Location> allSpawns = getAllSpawns();
        for (Location spawn : allSpawns) setSurrounding(spawn, Material.BARRIER);
        forEachPlayer(false, player -> {
            if (!PlayerStateCache.has(player)) PlayerStateCache.save(player);
            normalizePlayerForMatch(player);
            PlayerStateCache.clear(player);
            applyTeamKit(player, getPlayingTeam(player));
            player.setInvisible(false);
            player.setAllowFlight(false);
            player.setFlying(false);
            setMatchHunger(player);
            player.setVelocity(new Vector(0, 0, 0));
            player.addPotionEffect(effect);
        });
        if (resumeCountdown) {
            sendMessageToAllPlayer(I18n.format("pause_resume_countdown", "seconds", 3), "", 1, Sound.BLOCK_NOTE_BLOCK_BELL, 1);
            scheduleRoundTask(roundToken, () -> sendMessageToAllPlayer(I18n.format("pause_resume_countdown", "seconds", 2), "", 1, Sound.BLOCK_NOTE_BLOCK_BELL, 1), 20);
            scheduleRoundTask(roundToken, () -> sendMessageToAllPlayer(I18n.format("pause_resume_countdown", "seconds", 1), "", 1, Sound.BLOCK_NOTE_BLOCK_BELL, 1), 40);
        } else if (goDelayTicks > 80L) {
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

    private synchronized void startRound(int roundToken) {
        if (!isRoundActive(roundToken)) return;
        roundCountdownActive = false;
        lastTouchPlayer = null;
        matchState = matchTimer > 0 ? IN_PROGRESS : OVERTIME;
        removeBall();
        generateBall(data, name, data.ballSpawn, null);
    }

    private int beginRoundSequence() {
        cancelRoundTasks(false);
        return roundGeneration.incrementAndGet();
    }

    private synchronized void scheduleGoalRestart() {
        cancelRoundTasks(false);
        int roundToken = roundGeneration.incrementAndGet();
        ScheduledTask task = FoliaScheduler.runGlobalLater(() -> {
            synchronized (Match.this) {
                if (roundGeneration.get() != roundToken || canceled || matchState != GOAL) return;
                startDelayedRound();
            }
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
        boolean participant = replacePlayer(blueTeam, player);
        participant = replacePlayer(redTeam, player) || participant;
        if (participant && isInProgress()) refreshParticipantState(player);
        if (replacePlayer(spectatorTeam, player) && isInProgress()) {
            refreshSpectatorState(player);
        }
    }

    private void refreshParticipantState(Player player) {
        runForPlayer(player, target -> {
            if (!isInProgress() || !containsPlayer(target)) return;
            if (!PlayerStateCache.has(target)) PlayerStateCache.save(target);
            normalizePlayerForMatch(target);
            PlayerStateCache.clear(target);
            applyTeamKit(target, getPlayingTeam(target));
            target.setInvisible(false);
            target.setAllowFlight(false);
            target.setFlying(false);
            setMatchHunger(target);
        });
    }

    public void refreshSpectatorState(Player player) {
        if (isInProgress() && isSpectator(player)) {
            runForPlayer(player, target -> {
                if (isInProgress() && isSpectator(target)) enableSpectatorState(target);
            });
        }
    }

    private static boolean replacePlayer(Set<Player> players, Player newPlayer) {
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
            return true;
        }
        return false;
    }


    public void addPlayerToTeam(Player p, Team team) {
        if (p != null) {
            if (team.equals(Team.BLUE)) {
                blueTeam.add(p);
                redTeam.remove(p);
                if (spectatorTeam.remove(p)) runForPlayer(p, this::disableSpectatorState);
            } else if (team.equals(Team.RED)) {
                redTeam.add(p);
                blueTeam.remove(p);
                if (spectatorTeam.remove(p)) runForPlayer(p, this::disableSpectatorState);
            } else {
                spectatorTeam.add(p);
                blueTeam.remove(p);
                redTeam.remove(p);
                runForPlayer(p, this::enableSpectatorState);
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
                enableSpectatorState(player);
                PlayerStateCache.clear(player);
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

    private void enableSpectatorState(Player player) {
        if (!PlayerStateCache.has(player)) PlayerStateCache.save(player);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setInvisible(true);
        setMatchHunger(player);
    }

    private void disableSpectatorState(Player player) {
        player.setInvisible(false);
        player.setFlying(false);
        player.setAllowFlight(false);
    }

    private void setMatchHunger(Player player) {
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        player.setExhaustion(0.0f);
    }

    public synchronized void checkGoal(Location ballLocation) {
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

    public synchronized void checkGoal(Entity ball) {
        if (ball == null) return;
        Ball currentBall = balls.get(name);
        if (currentBall == null || currentBall.getBall() != ball) return;
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

    private synchronized void goal(Team team) {
        if (Team.BLUE.equals(team)) {
            blueScore++;
            triggerGoalAnimation(Team.BLUE);

        } else {
            redScore++;
            triggerGoalAnimation(Team.RED);
        }
        if (lastTouchPlayer != null) goals.merge(lastTouchPlayer, 1, Integer::sum);

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
        Set<Player> players = team == Team.BLUE ? blueTeam : redTeam;
        for (Player player : players) {
            runForPlayer(player, target -> {
                Location location = target.getLocation().clone();
                for (int i = 0; i < 3; i++) {
                    int offset = i * 30;
                    scheduleFirework(location, offset + 5);
                    scheduleFirework(location, offset + 10);
                    scheduleFirework(location, offset + 15);
                }
            });
        }
    }

    public void spawnFireworkFor(Team team) {
        Set<Player> players = team == Team.BLUE ? blueTeam : redTeam;
        for (Player player : players) {
            runForPlayer(player, p -> p.getWorld().spawnEntity(p.getLocation(), EntityType.FIREWORK_ROCKET));
        }
    }

    private void scheduleFirework(Location location, long delayTicks) {
        FoliaScheduler.runRegionLater(location, () -> {
            World world = location.getWorld();
            if (world != null) world.spawnEntity(location.clone(), EntityType.FIREWORK_ROCKET);
        }, delayTicks);
    }

    public synchronized void endMatch() {
        if (!isPlayableForPause()) return;
        boolean restartOvertimeRound = roundCountdownActive || matchState == GOAL || !balls.containsKey(name);
        invalidateRoundSequence();
        if (getBlueScore() > getRedScore()) {
            finishMatch(I18n.get("blue_win"), Team.BLUE);
        } else if (getBlueScore() < getRedScore()) {
            finishMatch(I18n.get("red_win"), Team.RED);
        } else {
            String score = ChatColor.BLUE.toString() + getBlueScore() + ChatColor.WHITE + " - " + ChatColor.RED + getRedScore();
            setMatchState(OVERTIME);
            sendMessageToAllPlayer(I18n.get("overtime"), score, 3, Sound.ENTITY_RABBIT_DEATH, 0.5f);
            if (restartOvertimeRound) startDelayedRound();
        }
    }

    public synchronized String forceEndMatch() {
        if (!isInProgress()) return I18n.get("force_end_not_active");
        invalidateRoundSequence();
        Team winner = null;
        String title;
        if (blueScore > redScore) {
            winner = Team.BLUE;
            title = I18n.format("force_end_blue_win", "blue", blueScore, "red", redScore);
        } else if (redScore > blueScore) {
            winner = Team.RED;
            title = I18n.format("force_end_red_win", "blue", blueScore, "red", redScore);
        } else {
            title = I18n.format("force_end_draw", "blue", blueScore, "red", redScore);
        }
        finishMatch(title, winner);
        return I18n.get("force_end_success");
    }

    private void finishMatch(String title, Team winner) {
        clearPauseState();
        if (winner != null) spawnFirework(winner);
        String score = ChatColor.BLUE.toString() + getBlueScore() + ChatColor.WHITE + " - " + ChatColor.RED + getRedScore();
        sendMessageToAllPlayer(title, score, 3, Sound.ENTITY_RABBIT_DEATH, 0.5f);
        ArrayList<Map.Entry<UUID, Integer>> list = new ArrayList<>(goals.entrySet());
        list.sort((e1, e2) -> Integer.compare(e2.getValue(), e1.getValue()));
        int totalGoals = redScore + blueScore;
        forEachPlayer(true, player -> {
            player.sendMessage(I18n.get("game_over"));
            player.sendMessage(I18n.get("goal_rank"));
            player.sendMessage(I18n.format("total_goals", "total", totalGoals));
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
        restoreAndExitPlayers();
        reset();
    }

    private static String getName(UUID uuid) {
        if (uuid == null) return "null";
        OfflinePlayer p = Bukkit.getOfflinePlayer(uuid);
        String name = p.getName();
        return name != null ? name : uuid.toString();
    }

    public synchronized void reset() {
        invalidateRoundSequence();
        scanGeneration.incrementAndGet();
        clearPauseState();
        setMatchState(READY);
        blueScore = 0;
        redScore = 0;
        lastTouchPlayer = null;
        goals.clear();
        blueTimeoutUsed = false;
        redTimeoutUsed = false;
        roundCountdownActive = false;
        canceled = false;
    }

    public synchronized void cancel() {
        invalidateRoundSequence();
        clearPauseState();
        removeBall();
        restorePlayerScales();
        restoreAndExitPlayers();
        reset();
        canceled = true;
        forEachPlayer(true, p -> {
            p.sendMessage(I18n.get("match_canceled"));
        });
    }

    private void restoreAndExitPlayers() {
        for (Player player : getAllPlayer(true)) CubeBall.reservePlayerExit(player);
        forEachPlayer(true, player -> {
            PotionEffect effect = player.getPotionEffect(PotionEffectType.SLOWNESS);
            if (effect != null && effect.getAmplifier() >= 255) player.removePotionEffect(PotionEffectType.SLOWNESS);
            CubeBall.restorePlayerAndExit(player);
        });
    }

    public void sendScoreToPlayer() {
        String title = I18n.format("score_title", "blue", blueScore, "red", redScore);
        String subtitle = I18n.format("score_subtitle", "name", getName(lastTouchPlayer).toUpperCase(), "speed", computeSpeedGoal());
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

    public synchronized void setLastTouchPlayer(Player lastTouchPlayer) {
        this.lastTouchPlayer = lastTouchPlayer == null ? null : lastTouchPlayer.getUniqueId();
    }

    public synchronized void setLastTouchPlayer(Entity ball, Player lastTouchPlayer) {
        Ball currentBall = balls.get(name);
        if (currentBall == null || currentBall.getBall() != ball || !canProcessBallPhysics()) return;
        setLastTouchPlayer(lastTouchPlayer);
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

    public boolean containsAnyPlayer(UUID playerId) {
        if (playerId == null) return false;
        return blueTeam.stream().anyMatch(player -> player != null && playerId.equals(player.getUniqueId()))
                || redTeam.stream().anyMatch(player -> player != null && playerId.equals(player.getUniqueId()))
                || spectatorTeam.stream().anyMatch(player -> player != null && playerId.equals(player.getUniqueId()));
    }

    private boolean isPlayerInOtherActiveMatch(UUID playerId) {
        for (Match other : matches.values()) {
            if (other != this && other.isInProgress() && other.containsAnyPlayer(playerId)) return true;
        }
        return false;
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

    public synchronized void setMatchState(MatchState matchState) {
        this.matchState = matchState;
    }

    public MatchData getData() {
        return data;
    }

    public synchronized void removeBall() {
        destroyBall(name);
    }

    public boolean isInProgress() {
        return matchState == IN_PROGRESS || matchState == OVERTIME || matchState == GOAL || matchState == PAUSED;
    }

    public boolean canUseDash() {
        return (matchState == IN_PROGRESS || matchState == OVERTIME) && !roundCountdownActive;
    }

    public synchronized boolean canProcessBallPhysics() {
        return (matchState == IN_PROGRESS || matchState == OVERTIME) && !roundCountdownActive && !canceled;
    }

    public synchronized Integer tickMatchTimer() {
        if (matchState != IN_PROGRESS || roundCountdownActive) return null;
        return --matchTimer;
    }

    public synchronized String adminPause() {
        if (!isInProgress()) return I18n.get("match_not_active");
        if (pauseType == PauseType.ADMIN) return I18n.get("admin_pause_already");
        if (pauseType == PauseType.TEAM) {
            cancelPauseExpiry();
            pauseType = PauseType.ADMIN;
            pauseTeam = null;
            sendTextToAllPlayer(I18n.get("team_pause_upgrade"));
            return I18n.get("team_pause_upgrade");
        }
        clearPauseVote(false);
        enterPause(PauseType.ADMIN, null, 0);
        sendTextToAllPlayer(I18n.get("admin_pause_started"));
        return I18n.get("admin_pause_started");
    }

    public synchronized String adminResume() {
        if (matchState != PAUSED || pauseType == PauseType.NONE) return I18n.get("resume_required");
        cancelPauseExpiry();
        pauseType = PauseType.NONE;
        pauseTeam = null;
        matchState = matchTimer > 0 ? IN_PROGRESS : OVERTIME;
        sendTextToAllPlayer(I18n.get("pause_resumed"));
        startDelayedRound(60L, true);
        return I18n.get("pause_resumed");
    }

    public synchronized boolean requestPauseVote(Player player, int minutes) {
        Team team = getPlayingTeam(player);
        if (team == Team.SPECTATOR || !isPlayableForPause()) {
            sendPlayerMessage(player, I18n.get("pause_vote_not_eligible"));
            return false;
        }
        if (minutes != 5 && minutes != 10) {
            sendPlayerMessage(player, I18n.get("pause_vote_invalid_duration"));
            return false;
        }
        if (pauseType != PauseType.NONE) {
            sendPlayerMessage(player, I18n.get("pause_vote_already"));
            return false;
        }
        if (pauseVote != null) {
            sendPlayerMessage(player, I18n.get("pause_vote_already"));
            return false;
        }
        if (isTimeoutUsed(team)) {
            sendPlayerMessage(player, I18n.get("team_timeout_used"));
            return false;
        }

        Set<UUID> eligible = new HashSet<>();
        for (Player participant : getAllPlayer(false)) {
            if (participant != null && participant.isOnline()) eligible.add(participant.getUniqueId());
        }
        if (eligible.isEmpty()) {
            sendPlayerMessage(player, I18n.get("pause_vote_not_eligible"));
            return false;
        }

        PauseVote vote = new PauseVote(team, minutes, eligible);
        vote.ballots.put(player.getUniqueId(), true);
        pauseVote = vote;
        int token = voteGeneration.incrementAndGet();
        pauseVoteTask = FoliaScheduler.runGlobalLater(() -> resolvePauseVote(token, true), 20L * 30L);
        sendTextToAllPlayer(I18n.format("pause_vote_started", "team", teamName(team), "minutes", minutes));
        sendPauseVoteStatus(vote);
        if (vote.ballots.size() >= vote.eligible.size()) resolvePauseVote(token, false);
        return true;
    }

    public synchronized boolean castPauseVote(Player player, boolean agree) {
        PauseVote vote = pauseVote;
        if (vote == null || pauseType != PauseType.NONE || !isPlayableForPause()) {
            sendPlayerMessage(player, I18n.get("pause_vote_no_active"));
            return false;
        }
        UUID uuid = player.getUniqueId();
        if (!vote.eligible.contains(uuid)) {
            sendPlayerMessage(player, I18n.get("pause_vote_not_eligible"));
            return false;
        }
        vote.ballots.put(uuid, agree);
        sendPlayerMessage(player, I18n.format("pause_vote_cast", "vote", I18n.get(agree ? "pause_vote_yes" : "pause_vote_no")));
        sendPauseVoteStatus(vote);
        if (vote.ballots.size() >= vote.eligible.size()) {
            resolvePauseVote(voteGeneration.get(), false);
        }
        return true;
    }

    private boolean isPlayableForPause() {
        return matchState == IN_PROGRESS || matchState == GOAL || matchState == OVERTIME;
    }

    private boolean isTimeoutUsed(Team team) {
        return team == Team.BLUE ? blueTimeoutUsed : redTimeoutUsed;
    }

    private void setTimeoutUsed(Team team) {
        if (team == Team.BLUE) blueTimeoutUsed = true;
        if (team == Team.RED) redTimeoutUsed = true;
    }

    private synchronized void enterPause(PauseType type, Team team, int minutes) {
        cancelPauseExpiry();
        invalidateRoundSequence();
        removeBall();
        pauseType = type;
        pauseTeam = team;
        matchState = PAUSED;
        if (type == PauseType.TEAM) {
            int token = pauseGeneration.incrementAndGet();
            pauseExpiryTask = FoliaScheduler.runGlobalLater(() -> {
                synchronized (Match.this) {
                    if (pauseGeneration.get() != token || pauseType != PauseType.TEAM || matchState != PAUSED) return;
                    pauseExpiryTask = null;
                    Team expiredTeam = pauseTeam;
                    pauseType = PauseType.NONE;
                    pauseTeam = null;
                    matchState = matchTimer > 0 ? IN_PROGRESS : OVERTIME;
                    sendTextToAllPlayer(I18n.format("team_pause_expired", "team", teamName(expiredTeam)));
                    startDelayedRound(60L, true);
                }
            }, minutes * 60L * 20L);
        }
    }

    private synchronized void resolvePauseVote(int token, boolean expired) {
        if (voteGeneration.get() != token || pauseVote == null) return;
        PauseVote vote = pauseVote;
        pauseVote = null;
        voteGeneration.incrementAndGet();
        if (pauseVoteTask != null) {
            if (!expired) pauseVoteTask.cancel();
            pauseVoteTask = null;
        }
        int yes = vote.yesCount();
        int no = vote.noCount();
        if (pauseType != PauseType.NONE || !isPlayableForPause()) return;
        if (yes > no) {
            setTimeoutUsed(vote.team);
            sendTextToAllPlayer(I18n.format("pause_vote_passed", "team", teamName(vote.team), "minutes", vote.minutes));
            enterPause(PauseType.TEAM, vote.team, vote.minutes);
            sendTextToAllPlayer(I18n.format("team_pause_started", "team", teamName(vote.team), "minutes", vote.minutes));
        } else if (expired) {
            sendTextToAllPlayer(I18n.get("pause_vote_expired"));
        } else {
            sendTextToAllPlayer(I18n.get("pause_vote_failed"));
        }
    }

    private void sendPauseVoteStatus(PauseVote vote) {
        sendTextToAllPlayer(I18n.format("pause_vote_status",
                "yes", vote.yesCount(),
                "no", vote.noCount(),
                "remaining", vote.eligible.size() - vote.ballots.size()));
    }

    private synchronized void cancelPauseExpiry() {
        pauseGeneration.incrementAndGet();
        if (pauseExpiryTask != null) pauseExpiryTask.cancel();
        pauseExpiryTask = null;
    }

    private synchronized void clearPauseVote(boolean announce) {
        voteGeneration.incrementAndGet();
        if (pauseVoteTask != null) pauseVoteTask.cancel();
        pauseVoteTask = null;
        pauseVote = null;
        if (announce) sendTextToAllPlayer(I18n.get("pause_vote_expired"));
    }

    private synchronized void clearPauseState() {
        cancelPauseExpiry();
        clearPauseVote(false);
        pauseType = PauseType.NONE;
        pauseTeam = null;
    }

    public boolean hasPlayer(Player player) {
        if (player == null) return false;
        return blueTeam.contains(player) || redTeam.contains(player) || spectatorTeam.contains(player);
    }

    public synchronized boolean canCastPauseVote(UUID playerId) {
        return playerId != null && pauseVote != null && pauseType == PauseType.NONE
                && isPlayableForPause() && pauseVote.eligible.contains(playerId);
    }

    public boolean isSpectator(Player player) {
        return player != null && spectatorTeam.contains(player);
    }

    public boolean removeSpectator(Player player) {
        if (!isSpectator(player)) return false;
        spectatorTeam.remove(player);
        runForPlayer(player, p -> {
            disableSpectatorState(p);
            PlayerStateCache.restore(p);
        });
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

    private String teamName(Team team) {
        if (team == Team.BLUE) return I18n.get("blue_name");
        if (team == Team.RED) return I18n.get("red_name");
        return I18n.get("spectator_name");
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

    private enum PauseType {
        NONE, ADMIN, TEAM
    }

    private static final class PauseVote {
        final Team team;
        final int minutes;
        final Set<UUID> eligible;
        final Map<UUID, Boolean> ballots = new ConcurrentHashMap<>();

        PauseVote(Team team, int minutes, Set<UUID> eligible) {
            this.team = team;
            this.minutes = minutes;
            this.eligible = Set.copyOf(eligible);
        }

        int yesCount() {
            return (int) ballots.values().stream().filter(Boolean::booleanValue).count();
        }

        int noCount() {
            return (int) ballots.values().stream().filter(value -> !value).count();
        }
    }
}
