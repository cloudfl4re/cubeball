package me.crylonz;

import com.github.squi2rel.cb.I18n;
import com.github.squi2rel.cb.util.FoliaScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class JoinSignManager {
    private static final String SIGN_HEADER = "[CubeBall]";
    private static final int START_DELAY_SECONDS = 60;
    private static final int[] COUNTDOWN_TITLE_SECONDS = {50, 40, 30, 20, 10, 5, 4, 3, 2, 1};
    private static final Map<String, Lobby> lobbies = new ConcurrentHashMap<>();
    private static final Map<UUID, String> playerLobby = new ConcurrentHashMap<>();

    private JoinSignManager() {
    }

    public static boolean handleSignInteract(PlayerInteractEvent event) {
        if (!isMainHand(event)) return false;

        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) return false;

        Block block = event.getClickedBlock();
        if (block == null || !(block.getState() instanceof Sign sign)) return false;

        String header = cleanSignLine(sign.getLine(0));
        if (!SIGN_HEADER.equalsIgnoreCase(header)) return false;

        event.setCancelled(true);
        join(event.getPlayer(), cleanSignLine(sign.getLine(1)));
        return true;
    }

    public static boolean handleSelectorInteract(PlayerInteractEvent event) {
        if (!isMainHand(event)) return false;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return false;

        ItemStack item = event.getItem();
        if (!isSelectorItem(item)) return false;

        event.setCancelled(true);
        Choice choice = selectorChoice(item);
        if (choice == null || choice == Choice.NONE) return true;

        Player player = event.getPlayer();
        if (choice == Choice.LEAVE) {
            if (isWaiting(player)) {
                removeWaitingPlayer(player, true);
                sendPlayerMessage(player, I18n.get("lobby_left"));
            } else {
                Match match = getSpectatingMatch(player);
                if (match != null && match.removeSpectator(player)) {
                    sendPlayerMessage(player, I18n.get("spectator_left"));
                }
            }
            return true;
        }

        Lobby lobby = getLobby(player);
        if (lobby == null) return true;

        Match match = CubeBall.matches.get(lobby.matchName);
        if (match == null || match.isInProgress()) {
            removeWaitingPlayer(player, true);
            sendPlayerMessage(player, I18n.get("lobby_match_unavailable"));
            return true;
        }

        synchronized (lobby) {
            LobbyEntry entry = lobby.players.get(player.getUniqueId());
            if (entry == null) return true;
            entry.choice = choice;
        }

        if (choice == Choice.RED) {
            match.applyTeamKit(player, Team.RED);
            sendPlayerMessage(player, I18n.get("lobby_selected_red"));
        } else if (choice == Choice.BLUE) {
            match.applyTeamKit(player, Team.BLUE);
            sendPlayerMessage(player, I18n.get("lobby_selected_blue"));
        } else {
            match.applyTeamKit(player, Team.SPECTATOR);
            sendPlayerMessage(player, I18n.get("lobby_selected_spectator"));
        }

        evaluateCountdown(lobby);
        return true;
    }

    public static boolean isSelectorItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(selectorKey(), PersistentDataType.STRING);
    }

    public static boolean isWaiting(Player player) {
        return player != null && playerLobby.containsKey(player.getUniqueId());
    }

    public static void lockWaitingState(Player player) {
        if (player == null) return;
        player.setGameMode(GameMode.SURVIVAL);
        player.setFlying(false);
        player.setAllowFlight(false);
    }

    public static void removeWaitingPlayer(Player player, boolean restore) {
        if (player == null) return;

        UUID uuid = player.getUniqueId();
        String matchName = playerLobby.remove(uuid);
        if (matchName == null) return;

        Lobby lobby = lobbies.get(matchName);
        if (lobby != null) {
            synchronized (lobby) {
                lobby.players.remove(uuid);
                if (lobby.players.isEmpty()) {
                    cancelCountdown(lobby, false);
                    lobbies.remove(matchName, lobby);
                } else {
                    evaluateCountdown(lobby);
                }
            }
        }

        if (restore) {
            PlayerStateCache.restore(player);
        }
    }

    public static void shutdown() {
        for (Lobby lobby : new ArrayList<>(lobbies.values())) {
            synchronized (lobby) {
                cancelCountdown(lobby, false);
                for (UUID uuid : new ArrayList<>(lobby.players.keySet())) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null) {
                        PlayerStateCache.restore(player);
                    }
                    playerLobby.remove(uuid, lobby.matchName);
                }
                lobby.players.clear();
                lobbies.remove(lobby.matchName, lobby);
            }
        }
        playerLobby.clear();
    }

    private static void join(Player player, String matchName) {
        if (matchName == null || matchName.isBlank()) {
            sendPlayerMessage(player, I18n.get("lobby_match_not_found"));
            return;
        }

        Match match = CubeBall.matches.get(matchName);
        if (match == null) {
            sendPlayerMessage(player, I18n.format("lobby_match_not_found_name", "match", matchName));
            return;
        }
        if (!match.isConfiguredForStart()) {
            sendPlayerMessage(player, I18n.format("lobby_match_not_configured", "match", match.getName()));
            return;
        }
        if (match.isInProgress()) {
            if (!isPlayingAnotherMatch(player)) {
                if (isWaiting(player)) removeWaitingPlayer(player, true);
                PlayerStateCache.save(player);
                match.addPlayerToTeam(player, Team.SPECTATOR);
                PlayerStateCache.clear(player);
                match.applyTeamKit(player, Team.SPECTATOR);
                giveActiveSpectatorItem(player);
                return;
            }
            sendPlayerMessage(player, I18n.format("lobby_match_in_progress", "match", match.getName()));
            return;
        }
        if (isPlayingAnotherMatch(player)) {
            sendPlayerMessage(player, I18n.format("lobby_match_in_progress", "match", match.getName()));
            return;
        }

        String oldMatch = playerLobby.get(player.getUniqueId());
        if (oldMatch != null && !oldMatch.equals(match.getName())) {
            removeWaitingPlayer(player, true);
        }

        Lobby lobby = lobbies.computeIfAbsent(match.getName(), Lobby::new);
        boolean countdownAlreadyRunning;
        synchronized (lobby) {
            countdownAlreadyRunning = lobby.countdownTask != null;
            lobby.players.computeIfAbsent(player.getUniqueId(), LobbyEntry::new);
            playerLobby.put(player.getUniqueId(), lobby.matchName);
        }

        PlayerStateCache.save(player);
        Location lobbySpawn = CubeBall.getLobbySpawn();
        if (lobbySpawn != null) player.teleportAsync(lobbySpawn);
        giveLobbyItems(player, getChoice(player));
        sendPlayerMessage(player, I18n.format("lobby_joined", "match", match.getName()));
        sendPlayerMessage(player, I18n.get("lobby_waiting_state_locked"));

        evaluateCountdown(lobby);
        if (countdownAlreadyRunning && lobby.countdownTask != null) {
            sendPlayerMessage(player, I18n.format("lobby_countdown_running", "seconds", remainingSeconds(lobby)));
        }
    }

    private static boolean isPlayingAnotherMatch(Player player) {
        for (Match match : CubeBall.matches.values()) {
            if (match.isInProgress() && match.hasPlayer(player)) return true;
        }
        return false;
    }

    private static Lobby getLobby(Player player) {
        String matchName = playerLobby.get(player.getUniqueId());
        return matchName == null ? null : lobbies.get(matchName);
    }

    private static Choice getChoice(Player player) {
        Lobby lobby = getLobby(player);
        if (lobby == null) return Choice.NONE;
        LobbyEntry entry = lobby.players.get(player.getUniqueId());
        return entry == null ? Choice.NONE : entry.choice;
    }

    private static void giveLobbyItems(Player player, Choice choice) {
        PlayerStateCache.clear(player);
        lockWaitingState(player);
        PlayerInventory inventory = player.getInventory();
        inventory.setItem(0, selector(Material.RED_WOOL, Choice.RED, I18n.get("lobby_selector_red")));
        inventory.setItem(1, selector(Material.BLUE_WOOL, Choice.BLUE, I18n.get("lobby_selector_blue")));
        inventory.setItem(2, selector(Material.WHITE_WOOL, Choice.SPECTATOR, I18n.get("lobby_selector_spectator")));
        inventory.setItem(8, selector(Material.BARRIER, Choice.LEAVE, I18n.get("lobby_selector_leave")));

        Match match = currentMatch(player);
        if (match != null) {
            if (choice == Choice.RED) {
                match.applyTeamKit(player, Team.RED);
            } else if (choice == Choice.BLUE) {
                match.applyTeamKit(player, Team.BLUE);
            } else {
                match.applyTeamKit(player, Team.SPECTATOR);
            }
        }
        player.updateInventory();
    }

    private static Match currentMatch(Player player) {
        Lobby lobby = getLobby(player);
        return lobby == null ? null : CubeBall.matches.get(lobby.matchName);
    }

    private static Match getSpectatingMatch(Player player) {
        for (Match match : CubeBall.matches.values()) {
            if (match.isInProgress() && match.isSpectator(player)) return match;
        }
        return null;
    }

    static void giveActiveSpectatorItem(Player player) {
        player.getInventory().setItem(8, selector(Material.BARRIER, Choice.LEAVE, I18n.get("lobby_selector_leave")));
        player.updateInventory();
    }

    private static ItemStack selector(Material material, Choice choice, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.getPersistentDataContainer().set(selectorKey(), PersistentDataType.STRING, choice.name());
            item.setItemMeta(meta);
        }
        return item;
    }

    private static Choice selectorChoice(ItemStack item) {
        if (!isSelectorItem(item)) return null;
        String value = item.getItemMeta().getPersistentDataContainer().get(selectorKey(), PersistentDataType.STRING);
        if (value == null) return null;
        try {
            return Choice.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void evaluateCountdown(Lobby lobby) {
        synchronized (lobby) {
            int eligible = countEligiblePlayers(lobby);
            if (eligible >= 2) {
                if (lobby.countdownTask == null) {
                    lobby.countdownEndsAtMillis = System.currentTimeMillis() + START_DELAY_SECONDS * 1000L;
                    lobby.countdownTask = FoliaScheduler.runGlobalLater(() -> finishCountdown(lobby), START_DELAY_SECONDS * 20L);
                    for (int seconds : COUNTDOWN_TITLE_SECONDS) {
                        long delay = (START_DELAY_SECONDS - seconds) * 20L;
                        lobby.countdownTitleTasks.add(FoliaScheduler.runGlobalLater(() -> sendLobbyTitle(lobby, seconds), delay));
                    }
                    sendLobbyMessage(lobby, I18n.format("lobby_countdown_start", "seconds", START_DELAY_SECONDS));
                }
                return;
            }

            if (lobby.countdownTask != null) {
                cancelCountdown(lobby, true);
            }
        }
    }

    private static void finishCountdown(Lobby lobby) {
        Match match = CubeBall.matches.get(lobby.matchName);
        if (match == null || match.isInProgress() || !match.isConfiguredForStart()) {
            failLobby(lobby, I18n.get("lobby_auto_start_failed"));
            return;
        }

        Assignment assignment;
        synchronized (lobby) {
            lobby.countdownTask = null;
            lobby.countdownEndsAtMillis = 0L;
            assignment = buildAssignment(lobby);
            if (assignment.participantCount() < 2) {
                sendLobbyMessage(lobby, I18n.get("lobby_countdown_cancel"));
                evaluateCountdown(lobby);
                return;
            }
            clearLobby(lobby, false);
        }

        match.prepareLobbyTeams(assignment.redPlayers, assignment.bluePlayers, assignment.spectatorPlayers);
        match.start(assignment.starter());
    }

    private static Assignment buildAssignment(Lobby lobby) {
        List<Player> participants = new ArrayList<>();
        List<Player> spectators = new ArrayList<>();
        Map<Choice, List<Player>> choices = new EnumMap<>(Choice.class);
        choices.put(Choice.RED, new ArrayList<>());
        choices.put(Choice.BLUE, new ArrayList<>());
        choices.put(Choice.NONE, new ArrayList<>());

        for (LobbyEntry entry : lobby.players.values()) {
            Player player = Bukkit.getPlayer(entry.playerId);
            if (player == null || !player.isOnline()) continue;
            if (entry.choice == Choice.SPECTATOR) {
                spectators.add(player);
            } else {
                participants.add(player);
                choices.get(entry.choice).add(player);
            }
        }

        Collections.shuffle(participants);
        if (participants.size() % 2 == 1) {
            Player moved = participants.remove(0);
            removeFromChoiceLists(choices.values(), moved);
            spectators.add(moved);
            sendPlayerMessage(moved, I18n.get("lobby_odd_spectator"));
        }

        int target = participants.size() / 2;
        List<Player> red = new ArrayList<>();
        List<Player> blue = new ArrayList<>();
        List<Player> pool = new ArrayList<>();
        fillPreferred(red, pool, choices.get(Choice.RED), target);
        fillPreferred(blue, pool, choices.get(Choice.BLUE), target);
        pool.addAll(choices.get(Choice.NONE));
        Collections.shuffle(pool);

        for (Player player : pool) {
            if (red.size() < target) {
                red.add(player);
            } else if (blue.size() < target) {
                blue.add(player);
            }
        }

        return new Assignment(red, blue, spectators);
    }

    private static void fillPreferred(List<Player> target, List<Player> overflow, List<Player> players, int max) {
        Collections.shuffle(players);
        for (Player player : players) {
            if (target.size() < max) {
                target.add(player);
            } else {
                overflow.add(player);
            }
        }
    }

    private static void removeFromChoiceLists(Collection<List<Player>> lists, Player player) {
        for (List<Player> list : lists) {
            list.remove(player);
        }
    }

    private static int countEligiblePlayers(Lobby lobby) {
        int count = 0;
        for (LobbyEntry entry : lobby.players.values()) {
            Player player = Bukkit.getPlayer(entry.playerId);
            if (player != null && player.isOnline() && entry.choice != Choice.SPECTATOR) {
                count++;
            }
        }
        return count;
    }

    private static void failLobby(Lobby lobby, String message) {
        synchronized (lobby) {
            sendLobbyMessage(lobby, message);
            for (UUID uuid : new ArrayList<>(lobby.players.keySet())) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    FoliaScheduler.runEntity(player, () -> PlayerStateCache.restore(player));
                }
            }
            clearLobby(lobby, true);
        }
    }

    private static void clearLobby(Lobby lobby, boolean cancelTask) {
        if (cancelTask) cancelCountdown(lobby, false);
        for (UUID uuid : lobby.players.keySet()) {
            playerLobby.remove(uuid, lobby.matchName);
        }
        lobby.players.clear();
        lobbies.remove(lobby.matchName, lobby);
    }

    private static void cancelCountdown(Lobby lobby, boolean notify) {
        boolean wasRunning = lobby.countdownTask != null;
        if (wasRunning) {
            lobby.countdownTask.cancel();
            lobby.countdownTask = null;
            lobby.countdownEndsAtMillis = 0L;
        }
        for (ScheduledTask task : lobby.countdownTitleTasks) {
            task.cancel();
        }
        lobby.countdownTitleTasks.clear();
        if (wasRunning && notify) {
            sendLobbyMessage(lobby, I18n.get("lobby_countdown_cancel"));
        }
    }

    private static int remainingSeconds(Lobby lobby) {
        long remaining = lobby.countdownEndsAtMillis - System.currentTimeMillis();
        return (int) Math.max(1L, (remaining + 999L) / 1000L);
    }

    private static void sendLobbyMessage(Lobby lobby, String message) {
        for (UUID uuid : lobby.players.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                sendPlayerMessage(player, message);
            }
        }
    }

    private static void sendLobbyTitle(Lobby lobby, int seconds) {
        for (UUID uuid : lobby.players.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                FoliaScheduler.runEntity(player, () -> player.sendTitle("§e" + seconds, "", 0, 20, 0));
            }
        }
    }

    private static void sendPlayerMessage(Player player, String message) {
        if (player == null || !player.isOnline()) return;
        FoliaScheduler.runEntity(player, () -> player.sendMessage(message));
    }

    private static boolean isMainHand(PlayerInteractEvent event) {
        return event.getHand() == null || event.getHand() == EquipmentSlot.HAND;
    }

    private static String cleanSignLine(String line) {
        String stripped = ChatColor.stripColor(line == null ? "" : line);
        return stripped == null ? "" : stripped.trim();
    }

    private static NamespacedKey selectorKey() {
        return new NamespacedKey(CubeBall.plugin, "lobby_selector");
    }

    private enum Choice {
        NONE,
        RED,
        BLUE,
        SPECTATOR,
        LEAVE
    }

    private static final class Lobby {
        private final String matchName;
        private final Map<UUID, LobbyEntry> players = new ConcurrentHashMap<>();
        private final List<ScheduledTask> countdownTitleTasks = new ArrayList<>();
        private ScheduledTask countdownTask;
        private long countdownEndsAtMillis;

        private Lobby(String matchName) {
            this.matchName = matchName;
        }
    }

    private static final class LobbyEntry {
        private final UUID playerId;
        private Choice choice = Choice.NONE;

        private LobbyEntry(UUID playerId) {
            this.playerId = playerId;
        }
    }

    private static final class Assignment {
        private final List<Player> redPlayers;
        private final List<Player> bluePlayers;
        private final List<Player> spectatorPlayers;

        private Assignment(List<Player> redPlayers, List<Player> bluePlayers, List<Player> spectatorPlayers) {
            this.redPlayers = redPlayers;
            this.bluePlayers = bluePlayers;
            this.spectatorPlayers = spectatorPlayers;
        }

        private int participantCount() {
            return redPlayers.size() + bluePlayers.size();
        }

        private Player starter() {
            if (!redPlayers.isEmpty()) return redPlayers.get(0);
            if (!bluePlayers.isEmpty()) return bluePlayers.get(0);
            if (!spectatorPlayers.isEmpty()) return spectatorPlayers.get(0);
            return null;
        }
    }
}
