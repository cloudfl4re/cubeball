package com.github.squi2rel.cb.menu;

import com.github.squi2rel.cb.I18n;
import com.github.squi2rel.cb.GoalSelectionManager;
import com.github.squi2rel.cb.MatchData;
import com.github.squi2rel.cb.menu.builder.DynamicMenuBuilder;
import com.github.squi2rel.cb.menu.builder.MenuBuilder;
import com.github.squi2rel.cb.menu.builder.MenuContext;
import com.github.squi2rel.cb.menu.builder.MenuManager;
import me.crylonz.CraftEngineHook;
import me.crylonz.CubeBall;
import me.crylonz.Match;
import me.crylonz.MatchState;
import me.crylonz.ResidenceHook;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.bukkit.Material.*;

public class SettingsMenu {
    public static DynamicMenuBuilder<Match> desc = new DynamicMenuBuilder<>(I18n.get("menu_desc_title"), 6, (builder, player, match) -> {
        MatchData c = match.getData();
        builder.setLorePrefix(ChatColor.GRAY.toString());
        MatchState state = match.getMatchState();
        builder.setSlot(0, 5, getState(state), match.getName(), null);
        builder.setSlot(8, 5, BARRIER, I18n.get("menu_desc_delete"), null).setAction((p, v) -> {
            if (match.isInProgress()) match.forceEndMatch();
            else match.cancel();
            CubeBall.matches.remove(match.getName());
            CubeBall.save();
            v.openParent(p);
            p.sendMessage(I18n.get("match_removed"));
        });
        builder.setSlot(1, 1, RED_WOOL, I18n.get("menu_desc_redspawn"), I18n.format("menu_desc_addspawn_desc", "c", c.redTeamSpawns.size())).setAction((p, v) -> {
            if (!checkResidence(p)) return;
            if (c.redTeamSpawns.size() > 20) builder.refresh();
            c.redTeamSpawns.add(entityToBlock(p.getLocation()));
            builder.refresh();
        }).setRightClickAction((p, v) -> {
            if (!c.redTeamSpawns.isEmpty()) c.redTeamSpawns.removeLast();
            builder.refresh();
        }).setRightShiftClickAction((p, v) -> {
            c.redTeamSpawns.clear();
            builder.refresh();
        });
        builder.setSlot(3, 1, BLUE_WOOL, I18n.get("menu_desc_bluespawn"), I18n.format("menu_desc_addspawn_desc", "c", c.blueTeamSpawns.size())).setAction((p, v) -> {
            if (!checkResidence(p)) return;
            if (c.blueTeamSpawns.size() > 20) builder.refresh();
            c.blueTeamSpawns.add(entityToBlock(p.getLocation()));
            builder.refresh();
        }).setRightClickAction((p, v) -> {
            if (!c.blueTeamSpawns.isEmpty()) c.blueTeamSpawns.removeLast();
            builder.refresh();
        }).setRightShiftClickAction((p, v) -> {
            c.blueTeamSpawns.clear();
            builder.refresh();
        });
        builder.setSlot(1, 3, RED_CONCRETE, I18n.get("menu_desc_redgoal"), I18n.format("menu_desc_addgoal_desc", "c", c.getRedTeamGoalSize())).setAction((p, v) -> {
            GoalSelectionManager.begin(p, c, true);
            p.closeInventory();
        }).setRightClickAction((p, v) -> {
            c.redTeamGoalPos1 = null;
            c.redTeamGoalPos2 = null;
            c.redTeamGoalBlocks.clear();
            builder.refresh();
        });
        builder.setSlot(3, 3, BLUE_CONCRETE, I18n.get("menu_desc_bluegoal"), I18n.format("menu_desc_addgoal_desc", "c", c.getBlueTeamGoalSize())).setAction((p, v) -> {
            GoalSelectionManager.begin(p, c, false);
            p.closeInventory();
        }).setRightClickAction((p, v) -> {
            c.blueTeamGoalPos1 = null;
            c.blueTeamGoalPos2 = null;
            c.blueTeamGoalBlocks.clear();
            builder.refresh();
        });
        builder.setSlot(4, 2, TNT, I18n.get("menu_desc_dashcooldown"), c.dashCooldown > 0 ? I18n.format("menu_desc_dashcooldown_desc", "s", c.dashCooldown) : I18n.get("menu_desc_dashcooldown_desc_d")).setAction((p, v) -> {
            p.sendMessage(I18n.get("menu_sendnumber"));
            p.closeInventory();
            MenuManager.registerChatHandler(p, s -> {
                c.dashCooldown = tryParseInt(s);
                v.sendTo(p);
            });
        });
        Location bs = c.ballSpawn;
        builder.setSlot(5, 1, EMERALD_BLOCK, I18n.get("menu_desc_ballspawn"), bs == null ? null : I18n.format("menu_desc_ballspawn_desc", "x", bs.getBlockX(), "y", bs.getBlockY() - 2, "z", bs.getBlockZ())).setAction((p, v) -> {
            if (!checkResidence(p)) return;
            c.ballSpawn = entityToBlock(p.getLocation().add(0, 2, 0));
            builder.refresh();
        });
        builder.setSlot(7, 1, c.cubeBallBlock, I18n.get("menu_desc_ballblock"), null).setAction((p, v) -> {
            if (!p.hasPermission("cubeball.admin")) {
                p.sendMessage(ChatColor.RED + "You do not have permission to do this!");
                return;
            }
            p.sendMessage(I18n.get("menu_desc_material_name"));
            p.closeInventory();
            MenuManager.registerChatHandler(p, s -> {
                Material m = Material.matchMaterial(s.toUpperCase());
                if (m == null || !m.isBlock() || m.isAir() || !m.isItem()) {
                    p.sendMessage(I18n.get("menu_desc_invalid_material"));
                    v.sendTo(p, v.getArgument());
                    return;
                }
                c.cubeBallBlock = m;
                v.sendTo(p, v.getArgument());
            });
        });
        String customId = c.ballCustomId;
        builder.setSlot(6, 1, getBallCustomIcon(c), I18n.get("menu_desc_ballcustom"),
                getBallCustomDesc(c)
        ).setLeftClickAction((p, v) -> {
            if (!p.hasPermission("cubeball.admin")) {
                p.sendMessage(ChatColor.RED + "You do not have permission to do this!");
                return;
            }
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand == null || hand.getType().isAir()) {
                p.sendMessage(I18n.get("menu_desc_ballcustom_not_item"));
                return;
            }
            String id = CraftEngineHook.getCustomItemId(hand);
            ItemStack snapshot = hand.clone();
            snapshot.setAmount(1);
            c.ballCustomId = id;
            c.ballCustomItem = snapshot;
            CubeBall.save();
            CubeBall.debug("menu custom item set match=" + match.getName()
                    + " id=" + id
                    + " item=" + CubeBall.describeItem(snapshot));
            p.sendMessage(I18n.format("menu_desc_ballcustom_set", "id", id == null ? snapshot.getType().name() : id));
            builder.refresh();
        }).setLeftShiftClickAction((p, v) -> {
            if (!p.hasPermission("cubeball.admin")) {
                p.sendMessage(ChatColor.RED + "You do not have permission to do this!");
                return;
            }
            p.sendMessage(I18n.get("menu_desc_ballcustom_input"));
            p.closeInventory();
            MenuManager.registerChatHandler(p, s -> {
                if (!setBallCustomId(c, p, s)) {
                    v.sendTo(p, v.getArgument());
                    return;
                }
                CubeBall.save();
                v.sendTo(p, v.getArgument());
            });
        }).setRightClickAction((p, v) -> {
            c.ballCustomId = null;
            c.ballCustomItem = null;
            CubeBall.save();
            CubeBall.debug("menu custom item cleared match=" + match.getName());
            p.sendMessage(I18n.get("menu_desc_ballcustom_cleared"));
            builder.refresh();
        });
        builder.setSlot(5, 3, SAND, I18n.get("menu_desc_settime"), I18n.format("menu_desc_settime_desc", "s", c.matchDuration)).setAction((p, v) -> {
            p.sendMessage(I18n.get("menu_sendnumber"));
            p.closeInventory();
            MenuManager.registerChatHandler(p, s -> {
                int num = tryParseInt(s);
                if (num < 30 || num > 1800) {
                    p.sendMessage(I18n.get("menu_numberinvalid"));
                    return;
                }
                c.matchDuration = num;
                v.sendTo(p);
            });
        });
        builder.setSlot(7, 3, TARGET, I18n.get("menu_desc_settarget"), c.maxGoal <= 0 ? I18n.get("menu_desc_settarget_desc_u") : I18n.format("menu_desc_settarget_desc", "s", c.maxGoal)).setAction((p, v) -> {
            p.sendMessage(I18n.get("menu_sendnumber"));
            p.closeInventory();
            MenuManager.registerChatHandler(p, s -> {
                int num = tryParseInt(s);
                if (num < 0) {
                    p.sendMessage(I18n.get("menu_numberinvalid"));
                    return;
                }
                c.maxGoal = num;
                v.sendTo(p);
            });
        });
        builder.setSlot(2, 2, OBSERVER, I18n.get("menu_desc_scanplayer"), match.buildTeam()).setAction((p, v) -> {
            if (match.isInProgress()) {
                builder.refresh();
                return;
            }
            match.scanPlayer();
            p.sendMessage(I18n.get("match_ready"));
            builder.refresh();
        });
        if (match.isInProgress()) {
            builder.setSlot(6, 2, RED_WOOL, I18n.get("menu_desc_stop"), null).setAction((p, v) -> {
                match.forceEndMatch();
                builder.refresh();
            }).setPrefix(ChatColor.RED.toString());
        } else {
            if (c.ballSpawn != null &&
                    !c.blueTeamSpawns.isEmpty() &&
                    !c.redTeamSpawns.isEmpty() &&
                    c.hasBlueTeamGoalArea() &&
                    c.hasRedTeamGoalArea() &&
                    match.getMatchState() == MatchState.READY) {
                builder.setSlot(6, 2, LIME_WOOL, I18n.get("menu_desc_start"), null).setAction((p, v) -> {
                    match.start(p);
                    p.closeInventory();
                }).setPrefix(ChatColor.GREEN.toString());
            } else {
                builder.setSlot(6, 2, GRAY_WOOL, I18n.get("menu_desc_start"), null).setAction((p, v) -> builder.refresh()).setPrefix(ChatColor.DARK_GRAY.toString());
            }
        }
        builder.setSlot(4, 5, ARROW, I18n.get("menu_back"), null).setAction((p, v) -> {
            CubeBall.save();
            v.openParent(p);
        });
    });

    public static DynamicMenuBuilder<Integer> list = new DynamicMenuBuilder<>(I18n.get("menu_list_title"), 6, (builder, player, page) -> {
        int col = 0, row = 0;
        int maxPerPage = 5 * 9;
        List<Match> values;
        if (player.hasPermission("cubeball.admin")) {
            values = new ArrayList<>(CubeBall.matches.values());
        } else {
            long most = player.getUniqueId().getMostSignificantBits();
            long least = player.getUniqueId().getLeastSignificantBits();
            values = CubeBall.matches.values().stream().filter(m -> {
                MatchData data = m.getData();
                return data.creatorIdMost == most && data.creatorIdLeast == least;
            }).collect(Collectors.toList());
        }
        builder.setLorePrefix(ChatColor.GRAY.toString());
        for (Match match : values.subList(page * maxPerPage, Math.min((page + 1) * maxPerPage, values.size()))) {
            MatchState state = match.getMatchState();
            builder.setSlot(col, row, getState(state), match.getName(), I18n.format("menu_list_creator", "n", match.getData().creator)).setAction((p, v) -> {
                DynamicMenuBuilder.DynamicMenuContext<Match> menu = desc.build();
                menu.setParent(v);
                menu.sendTo(p, match);
            });
            if (++col == 8) {
                col = 0;
                row += 1;
            }
        }
        builder.setSlot(4, 5, ARROW, I18n.get("menu_back"), null).setAction((p, v) -> v.openParent(p));
        builder.setAutoClose(false);
    });

    public static MenuContext<Void> settings = new MenuBuilder<Void>(I18n.get("menu_title"), 6, builder -> {
        builder.setSlot(0, 0, GREEN_CONCRETE, I18n.get("menu_new"), null).setAction((p, v) -> {
            if (!p.hasPermission("cubeball.admin")) {
                long most = p.getUniqueId().getMostSignificantBits();
                long least = p.getUniqueId().getLeastSignificantBits();
                if (CubeBall.matches.values().stream().filter(m -> {
                    MatchData data = m.getData();
                    return data.creatorIdMost == most && data.creatorIdLeast == least;
                }).count() >= CubeBall.maxMatchPerPlayer) {
                    p.sendMessage(I18n.format("menu_new_limit", "n", CubeBall.maxMatchPerPlayer));
                    return;
                }
            }
            p.sendMessage(I18n.get("menu_new_name"));
            p.closeInventory();
            MenuManager.registerChatHandler(p, s -> {
                if (CubeBall.matches.get(s) != null) {
                    p.sendMessage(I18n.get("menu_new_existed"));
                    return;
                }
                Match m = new Match(s, p);
                CubeBall.matches.put(s, m);
                CubeBall.save();
                p.sendMessage(I18n.get("menu_new_success"));
                DynamicMenuBuilder.DynamicMenuContext<Match> menu = desc.build();
                menu.setParent(v);
                menu.sendTo(p, m);
            });
        });
        builder.setSlot(0, 1, BOOK, I18n.get("menu_list"), null).setAction((p, v) -> {
            DynamicMenuBuilder.DynamicMenuContext<Integer> menu = list.build();
            menu.setParent(v);
            menu.sendTo(p, 0);
        });
    }).build(); // TODO pause & resume, personal ball, static or random spawn ...

    private static ItemStack getBallCustomIcon(MatchData data) {
        if (data.ballCustomItem != null && !data.ballCustomItem.getType().isAir()) {
            ItemStack icon = data.ballCustomItem.clone();
            icon.setAmount(1);
            return icon;
        }
        if (data.ballCustomId != null && !data.ballCustomId.isEmpty() && CraftEngineHook.isAvailable()) {
            ItemStack icon = CraftEngineHook.buildCustomItemIcon(data.ballCustomId);
            if (icon != null) return icon;
        }
        return new ItemStack(PAPER);
    }

    private static String getBallCustomDesc(MatchData data) {
        String current = data.ballCustomId;
        if ((current == null || current.isEmpty()) && data.ballCustomItem != null) {
            current = data.ballCustomItem.getType().name();
        }
        return I18n.format("menu_desc_ballcustom_desc", "id",
                current != null && !current.isEmpty() ? current : I18n.get("menu_desc_ballcustom_none"));
    }

    private static boolean setBallCustomId(MatchData data, Player player, String input) {
        String id = input == null ? "" : input.trim();
        if (id.isEmpty()) {
            data.ballCustomId = null;
            data.ballCustomItem = null;
            player.sendMessage(I18n.get("menu_desc_ballcustom_cleared"));
            return true;
        }
        if (CraftEngineHook.isAvailable()) {
            if (!CraftEngineHook.hasCustomContent(id)) {
                player.sendMessage(I18n.get("menu_desc_ballcustom_invalid"));
                return false;
            }
        } else if (!looksLikeCustomId(id)) {
            player.sendMessage(I18n.get("menu_desc_ballcustom_invalid"));
            return false;
        } else {
            player.sendMessage(I18n.get("menu_desc_ballcustom_ce_missing"));
        }
        data.ballCustomId = id;
        data.ballCustomItem = null;
        CubeBall.debug("menu custom id set id=" + id + " item snapshot cleared");
        player.sendMessage(I18n.format("menu_desc_ballcustom_set", "id", id));
        return true;
    }

    private static boolean setGoalRegion(MatchData data, Player player, String input, boolean redTeamGoal) {
        Location[] corners = parseGoalCorners(player, input);
        if (corners == null) {
            player.sendMessage(I18n.get("menu_desc_goal_invalid"));
            return false;
        }
        if (redTeamGoal) {
            data.redTeamGoalPos1 = corners[0];
            data.redTeamGoalPos2 = corners[1];
            data.redTeamGoalBlocks.clear();
        } else {
            data.blueTeamGoalPos1 = corners[0];
            data.blueTeamGoalPos2 = corners[1];
            data.blueTeamGoalBlocks.clear();
        }
        CubeBall.save();
        player.sendMessage(I18n.get("menu_desc_goal_set"));
        return true;
    }

    private static Location[] parseGoalCorners(Player player, String input) {
        if (input == null) return null;
        String[] parts = input.trim().split("[,;\\s]+", -1);
        if (parts.length != 6) return null;
        int[] coords = new int[6];
        for (int i = 0; i < parts.length; i++) {
            try {
                coords[i] = (int) Math.floor(Double.parseDouble(parts[i]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return new Location[]{
                new Location(player.getWorld(), coords[0], coords[1], coords[2]),
                new Location(player.getWorld(), coords[3], coords[4], coords[5])
        };
    }

    private static boolean looksLikeCustomId(String id) {
        return id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
    }

    private static Material getState(MatchState state) {
        return state == MatchState.CREATED ? GRAY_CONCRETE : state == MatchState.READY ? YELLOW_CONCRETE : LIME_CONCRETE;
    }

    /**
     * 检查玩家所在位置是否在领地内；不在时发消息并返回 false。
     * 若领地插件未安装则放行（返回 true），避免无领地环境无法使用。
     */
    private static boolean checkResidence(Player player) {
        if (!ResidenceHook.isAvailable()) return true; // 领地插件未安装，不做限制
        if (ResidenceHook.isInAnyResidence(player.getLocation())) return true;
        player.sendMessage(I18n.get("setup_requires_residence"));
        return false;
    }

    private static Location entityToBlock(Location l) {
        return new Location(l.getWorld(), l.getBlockX() + 0.5, l.getBlockY() + 0.5, l.getBlockZ() + 0.5, l.getYaw(), l.getPitch());
    }

    private static int tryParseInt(String s) {
        int num;
        try {
            num = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            num = -1;
        }
        return num;
    }
}
