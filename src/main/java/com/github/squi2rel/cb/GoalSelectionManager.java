package com.github.squi2rel.cb;

import me.crylonz.CubeBall;
import me.crylonz.ResidenceHook;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GoalSelectionManager {
    private static final Map<UUID, Selection> selections = new ConcurrentHashMap<>();

    private GoalSelectionManager() {
    }

    public static void begin(Player player, MatchData data, boolean redTeamGoal) {
        selections.put(player.getUniqueId(), new Selection(data, redTeamGoal));
        player.sendMessage(I18n.get("menu_desc_goal_select_start"));
    }

    public static boolean handle(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null) return false;
        if (player.getInventory().getItemInMainHand().getType() != Material.STONE_SHOVEL) return false;

        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) return false;
        Block block = event.getClickedBlock();
        if (block == null) return false;

        event.setCancelled(true);
        Location location = block.getLocation();
        if (action == Action.LEFT_CLICK_BLOCK) {
            selection.pos1 = location;
            player.sendMessage(I18n.format("menu_desc_goal_pos1", "x", location.getBlockX(), "y", location.getBlockY(), "z", location.getBlockZ()));
        } else {
            selection.pos2 = location;
            player.sendMessage(I18n.format("menu_desc_goal_pos2", "x", location.getBlockX(), "y", location.getBlockY(), "z", location.getBlockZ()));
        }

        if (selection.pos1 != null && selection.pos2 != null) {
            if (!selection.pos1.getWorld().equals(selection.pos2.getWorld())) {
                player.sendMessage(I18n.get("menu_desc_goal_world_invalid"));
                return true;
            }
            // 要求球门两个角都在领地内（领地插件不可用时跳过此限制）
            if (ResidenceHook.isAvailable()) {
                if (!ResidenceHook.isInAnyResidence(selection.pos1) || !ResidenceHook.isInAnyResidence(selection.pos2)) {
                    player.sendMessage(I18n.get("setup_requires_residence"));
                    selections.remove(player.getUniqueId());
                    return true;
                }
            }
            save(selection);
            selections.remove(player.getUniqueId());
            player.sendMessage(I18n.format("menu_desc_goal_set", "c", goalSize(selection.pos1, selection.pos2)));
            CubeBall.save();
        }
        return true;
    }

    private static void save(Selection selection) {
        if (selection.redTeamGoal) {
            selection.data.redTeamGoalPos1 = selection.pos1;
            selection.data.redTeamGoalPos2 = selection.pos2;
            selection.data.redTeamGoalBlocks.clear();
        } else {
            selection.data.blueTeamGoalPos1 = selection.pos1;
            selection.data.blueTeamGoalPos2 = selection.pos2;
            selection.data.blueTeamGoalBlocks.clear();
        }
    }

    private static int goalSize(Location a, Location b) {
        long size = (long) (Math.abs(a.getBlockX() - b.getBlockX()) + 1)
                * (Math.abs(a.getBlockY() - b.getBlockY()) + 1)
                * (Math.abs(a.getBlockZ() - b.getBlockZ()) + 1);
        return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }

    private static final class Selection {
        private final MatchData data;
        private final boolean redTeamGoal;
        private Location pos1;
        private Location pos2;

        private Selection(MatchData data, boolean redTeamGoal) {
            this.data = data;
            this.redTeamGoal = redTeamGoal;
        }
    }
}
