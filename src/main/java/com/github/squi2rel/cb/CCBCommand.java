package com.github.squi2rel.cb;

import com.github.squi2rel.cb.menu.SettingsMenu;
import com.github.squi2rel.cb.menu.builder.MenuManager;
import me.crylonz.CubeBall;
import me.crylonz.CraftEngineHook;
import me.crylonz.Match;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public class CCBCommand implements CommandExecutor {
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
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;
        MenuManager.openMenu(player, () -> SettingsMenu.settings.sendTo(player));
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

    private boolean looksLikeCustomId(String id) {
        return id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
    }
}
