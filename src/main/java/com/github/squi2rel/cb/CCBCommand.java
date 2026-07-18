package com.github.squi2rel.cb;

import com.github.squi2rel.cb.menu.SettingsMenu;
import com.github.squi2rel.cb.menu.builder.MenuManager;
import com.github.squi2rel.cb.util.FoliaScheduler;
import me.crylonz.CubeBall;
import me.crylonz.CraftEngineHook;
import me.crylonz.Match;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class CCBCommand implements CommandExecutor, TabCompleter {
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("debug")) {
            if (!sender.hasPermission("cubeball.admin")) {
                sender.sendMessage("You do not have permission to do this!");
                return true;
            }
            boolean enabled = args.length == 1 ? !CubeBall.debugMode : parseToggle(args[1], CubeBall.debugMode);
            CubeBall.setDebugMode(enabled);
            sender.sendMessage("[CCB] Debug mode " + (enabled ? "enabled" : "disabled"));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("glow")) {
            if (!sender.hasPermission("cubeball.admin")) {
                sender.sendMessage("You do not have permission to do this!");
                return true;
            }
            boolean enabled = args.length == 1 ? !CubeBall.ballGlow : parseToggle(args[1], CubeBall.ballGlow);
            CubeBall.setBallGlow(enabled);
            sender.sendMessage("[CCB] Ball glow " + (enabled ? "enabled" : "disabled"));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("roll")) {
            if (!sender.hasPermission("cubeball.admin")) {
                sender.sendMessage("You do not have permission to do this!");
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("speed")) {
                if (args.length < 3) {
                    sender.sendMessage("[CCB] Usage: /ccb roll speed <number>");
                    return true;
                }
                double speed = tryParseDouble(args[2], CubeBall.ballRollSpeed);
                CubeBall.setBallRollSpeed(speed);
                sender.sendMessage("[CCB] Ball roll speed set to " + CubeBall.ballRollSpeed);
                return true;
            }
            boolean enabled = args.length == 1 ? !CubeBall.ballRollEnabled : parseToggle(args[1], CubeBall.ballRollEnabled);
            CubeBall.setBallRollEnabled(enabled);
            sender.sendMessage("[CCB] Ball roll " + (enabled ? "enabled" : "disabled"));
            return true;
        }
        if (args.length > 0 && (args[0].equalsIgnoreCase("redteam") || args[0].equalsIgnoreCase("blueteam"))) {
            if (!sender.hasPermission("cubeball.admin")) {
                sender.sendMessage("You do not have permission to do this!");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage("[CCB] Usage: /ccb " + args[0].toLowerCase() + " <name>");
                return true;
            }
            String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            if (args[0].equalsIgnoreCase("redteam")) {
                CubeBall.setBossBarRedTeam(name);
                sender.sendMessage("[CCB] BossBar red team set to " + CubeBall.getBossBarRedTeam());
            } else {
                CubeBall.setBossBarBlueTeam(name);
                sender.sendMessage("[CCB] BossBar blue team set to " + CubeBall.getBossBarBlueTeam());
            }
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("setballhand")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("[CCB] This command can only be used by a player.");
                return true;
            }
            if (!sender.hasPermission("cubeball.admin")) {
                sender.sendMessage("You do not have permission to do this!");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage("[CCB] Usage: /ccb setballhand <match>");
                return true;
            }
            Match match = CubeBall.matches.get(args[1]);
            if (match == null) {
                sender.sendMessage("[CCB] Match not found: " + args[1]);
                return true;
            }
            Player player = (Player) sender;
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand == null || hand.getType().isAir()) {
                sender.sendMessage("[CCB] Hold an item in your main hand first.");
                return true;
            }
            ItemStack snapshot = hand.clone();
            snapshot.setAmount(1);
            String id = CraftEngineHook.getCustomItemId(hand);
            match.getData().ballCustomId = id;
            match.getData().ballCustomItem = snapshot;
            CubeBall.save();
            CubeBall.debug("command setballhand match=" + match.getName()
                    + " id=" + id
                    + " item=" + CubeBall.describeItem(snapshot));
            sender.sendMessage("[CCB] Ball item snapshot saved for " + match.getName()
                    + " (" + (id == null ? snapshot.getType().name() : id) + ")");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("setballce")) {
            if (!sender.hasPermission("cubeball.admin")) {
                sender.sendMessage("You do not have permission to do this!");
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage("[CCB] Usage: /ccb setballce <match> <namespace:id|clear>");
                return true;
            }
            Match match = CubeBall.matches.get(args[1]);
            if (match == null) {
                sender.sendMessage("[CCB] Match not found: " + args[1]);
                return true;
            }

            String id = args[2].trim();
            if (id.equalsIgnoreCase("clear") || id.equalsIgnoreCase("none") || id.equalsIgnoreCase("off")) {
                match.getData().ballCustomId = null;
                match.getData().ballCustomItem = null;
                CubeBall.save();
                CubeBall.debug("command setballce cleared match=" + match.getName());
                sender.sendMessage("[CCB] CraftEngine ball cleared for " + match.getName());
                return true;
            }

            if (!looksLikeCustomId(id)) {
                sender.sendMessage("[CCB] Invalid CraftEngine id. Use namespace:id, for example daoju:jiangbei");
                return true;
            }

            boolean found = false;
            if (CraftEngineHook.isAvailable()) {
                found = CraftEngineHook.hasCustomContent(id);
                if (!found) {
                    sender.sendMessage("[CCB] Warning: CraftEngine did not resolve " + id + "; saved anyway for runtime fallback/debug.");
                }
            } else {
                sender.sendMessage("[CCB] Warning: CraftEngine is not installed; saved id anyway.");
            }

            match.getData().ballCustomId = id;
            match.getData().ballCustomItem = null;
            CubeBall.save();
            CubeBall.debug("command setballce match=" + match.getName()
                    + " id=" + id
                    + " found=" + found
                    + " itemSnapshotCleared=true");
            sender.sendMessage("[CCB] CraftEngine ball set for " + match.getName() + ": " + id);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("votepause")) {
            if (!sender.hasPermission("cubeball.timeout")) {
                sender.sendMessage(I18n.get("command_no_permission"));
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(I18n.get("command_player_only"));
                return true;
            }
            if (args.length != 2) {
                sender.sendMessage(I18n.get("votepause_usage"));
                return true;
            }
            String value = args[1];
            if (value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("no")) {
                Match match = findPauseVoteMatch(player);
                if (match == null) {
                    sender.sendMessage(I18n.get("pause_vote_no_active"));
                    return true;
                }
                boolean agree = value.equalsIgnoreCase("yes");
                FoliaScheduler.runGlobal(() -> match.castPauseVote(player, agree));
                return true;
            }
            Match match = findActivePlayerMatch(player);
            if (match == null) {
                sender.sendMessage(I18n.get("pause_vote_not_eligible"));
                return true;
            }
            int minutes = tryParseInt(value, -1);
            if (minutes != 5 && minutes != 10) {
                sender.sendMessage(I18n.get("pause_vote_invalid_duration"));
                return true;
            }
            FoliaScheduler.runGlobal(() -> match.requestPauseVote(player, minutes));
            return true;
        }
        if (args.length > 0 && (args[0].equalsIgnoreCase("pause")
                || args[0].equalsIgnoreCase("resume")
                || args[0].equalsIgnoreCase("end"))) {
            if (!sender.hasPermission("cubeball.admin")) {
                sender.sendMessage(I18n.get("command_no_permission"));
                return true;
            }
            String matchName = args.length == 1 ? null : String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            Match match = resolveActiveMatch(sender, matchName);
            if (match == null) return true;
            FoliaScheduler.runGlobal(() -> {
                String result;
                if (args[0].equalsIgnoreCase("pause")) result = match.adminPause();
                else if (args[0].equalsIgnoreCase("resume")) result = match.adminResume();
                else result = match.forceEndMatch();
                boolean alreadyBroadcast = result.equals(I18n.get("admin_pause_started"))
                        || result.equals(I18n.get("team_pause_upgrade"))
                        || result.equals(I18n.get("pause_resumed"));
                if (!alreadyBroadcast || !(sender instanceof Player player) || !match.hasPlayer(player)) {
                    sendCommandMessage(sender, result);
                }
            });
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("spawn")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("[CCB] This command can only be used by a player.");
                return true;
            }
            if (!sender.hasPermission("cubeball.admin")) {
                sender.sendMessage("You do not have permission to do this!");
                return true;
            }
            CubeBall.setLobbySpawn(player.getLocation());
            sender.sendMessage(I18n.get("lobby_spawn_set"));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("exitspawn")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("[CCB] This command can only be used by a player.");
                return true;
            }
            if (!sender.hasPermission("cubeball.admin")) {
                sender.sendMessage("You do not have permission to do this!");
                return true;
            }
            CubeBall.setExitSpawn(player.getLocation());
            sender.sendMessage(I18n.get("exit_spawn_set"));
            return true;
        }
        if (!(sender instanceof Player)) return true;
        if (!sender.hasPermission("cubeball.manage")) {
            sender.sendMessage(I18n.get("command_no_permission"));
            return true;
        }
        Player player = (Player) sender;
        SettingsMenu.settings.sendTo(player);
        return true;
    }

    private boolean parseToggle(String value, boolean fallback) {
        if (value.equalsIgnoreCase("on") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes")) return true;
        if (value.equalsIgnoreCase("off") || value.equalsIgnoreCase("false") || value.equalsIgnoreCase("no")) return false;
        return fallback;
    }

    private double tryParseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int tryParseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean looksLikeCustomId(String id) {
        return id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
    }

    private Match findActivePlayerMatch(Player player) {
        Match found = null;
        for (Match match : CubeBall.matches.values()) {
            if (!match.isInProgress() || !match.containsPlayer(player)) continue;
            if (found != null) return null;
            found = match;
        }
        return found;
    }

    private Match findPauseVoteMatch(Player player) {
        Match found = null;
        for (Match match : CubeBall.matches.values()) {
            if (!match.canCastPauseVote(player.getUniqueId())) continue;
            if (found != null) return null;
            found = match;
        }
        return found;
    }

    private Match resolveActiveMatch(CommandSender sender, String name) {
        if (name != null && !name.isBlank()) {
            Match match = CubeBall.matches.get(name);
            if (match == null) {
                sendCommandMessage(sender, I18n.format("match_not_found", "match", name));
                return null;
            }
            if (!match.isInProgress()) {
                sendCommandMessage(sender, I18n.get("match_not_active"));
                return null;
            }
            return match;
        }
        Match found = null;
        for (Match match : CubeBall.matches.values()) {
            if (!match.isInProgress()) continue;
            if (found != null) {
                sendCommandMessage(sender, I18n.get("match_name_required"));
                return null;
            }
            found = match;
        }
        if (found == null) sendCommandMessage(sender, I18n.get("match_not_active"));
        return found;
    }

    private void sendCommandMessage(CommandSender sender, String message) {
        if (sender instanceof Player player) {
            FoliaScheduler.runEntity(player, () -> player.sendMessage(message));
        } else {
            sender.sendMessage(message);
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            ArrayList<String> values = new ArrayList<>();
            if (sender.hasPermission("cubeball.timeout")
                    && sender instanceof Player player
                    && (findActivePlayerMatch(player) != null || findPauseVoteMatch(player) != null)) values.add("votepause");
            if (sender.hasPermission("cubeball.admin")) {
                values.addAll(List.of("debug", "glow", "roll", "redteam", "blueteam", "setballhand", "setballce", "spawn", "exitspawn", "pause", "resume", "end"));
            }
            return filter(args[0], values);
        }
        if (args.length == 2) {
            if ((args[0].equalsIgnoreCase("debug") || args[0].equalsIgnoreCase("glow"))
                    && sender.hasPermission("cubeball.admin")) {
                return filter(args[1], List.of("on", "off"));
            }
            if (args[0].equalsIgnoreCase("roll") && sender.hasPermission("cubeball.admin")) {
                return filter(args[1], List.of("on", "off", "speed"));
            }
            if ((args[0].equalsIgnoreCase("setballhand") || args[0].equalsIgnoreCase("setballce"))
                    && sender.hasPermission("cubeball.admin")) {
                return filter(args[1], new ArrayList<>(CubeBall.matches.keySet()));
            }
            if ((args[0].equalsIgnoreCase("pause") || args[0].equalsIgnoreCase("resume") || args[0].equalsIgnoreCase("end"))
                    && sender.hasPermission("cubeball.admin")) {
                return filter(args[1], activeMatchNames());
            }
            if (args[0].equalsIgnoreCase("votepause")
                    && sender.hasPermission("cubeball.timeout")
                    && sender instanceof Player player
                    && (findActivePlayerMatch(player) != null || findPauseVoteMatch(player) != null)) {
                return filter(args[1], List.of("5", "10", "yes", "no"));
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("setballce") && sender.hasPermission("cubeball.admin")) {
            return filter(args[2], List.of("clear", "namespace:id"));
        }
        return List.of();
    }

    private List<String> activeMatchNames() {
        return CubeBall.matches.values().stream()
                .filter(Match::isInProgress)
                .map(Match::getName)
                .toList();
    }

    private List<String> filter(String input, List<String> values) {
        String lower = input.toLowerCase();
        return values.stream().filter(value -> value.toLowerCase().startsWith(lower)).toList();
    }
}
