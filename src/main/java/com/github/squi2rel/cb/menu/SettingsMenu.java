package com.github.squi2rel.cb.menu;

import com.github.squi2rel.cb.I18n;
import com.github.squi2rel.cb.MatchData;
import com.github.squi2rel.cb.menu.builder.DynamicMenuBuilder;
import com.github.squi2rel.cb.menu.builder.MenuBuilder;
import com.github.squi2rel.cb.menu.builder.MenuContext;
import com.github.squi2rel.cb.menu.builder.MenuManager;
import com.github.squi2rel.cb.util.BlockScanUtil;
import me.crylonz.CubeBall;
import me.crylonz.Match;
import me.crylonz.MatchState;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;

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
            match.removeBall();
            CubeBall.matches.remove(match.getName());
            CubeBall.save();
            v.openParent(p);
            p.sendMessage(I18n.get("match_removed"));
        });
        builder.setSlot(1, 1, RED_WOOL, I18n.get("menu_desc_redspawn"), I18n.format("menu_desc_addspawn_desc", "c", c.redTeamSpawns.size())).setAction((p, v) -> {
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
        builder.setSlot(1, 3, RED_CONCRETE, I18n.get("menu_desc_redgoal"), I18n.format("menu_desc_addgoal_desc", "c", c.redTeamGoalBlocks.size())).setAction((p, v) -> {
            c.redTeamGoalBlocks = BlockScanUtil.scanXZ(entityToBlock(p.getLocation().add(0, -0.5, 0)), 128);
            builder.refresh();
        }).setRightClickAction((p, v) -> {
            c.redTeamGoalBlocks.clear();
            builder.refresh();
        });
        builder.setSlot(3, 3, BLUE_CONCRETE, I18n.get("menu_desc_bluegoal"), I18n.format("menu_desc_addgoal_desc", "c", c.blueTeamGoalBlocks.size())).setAction((p, v) -> {
            c.blueTeamGoalBlocks = BlockScanUtil.scanXZ(entityToBlock(p.getLocation().add(0, -0.5, 0)), 128);
            builder.refresh();
        }).setRightClickAction((p, v) -> {
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
                }
                c.cubeBallBlock = m;
                v.sendTo(p, v.getArgument());
            });
        });
        String customId = c.ballCustomId;
        builder.setSlot(6, 1, PAPER, I18n.get("menu_desc_ballcustom"),
                customId != null && !customId.isEmpty()
                        ? I18n.format("menu_desc_ballcustom_desc", "id", customId)
                        : I18n.format("menu_desc_ballcustom_desc", "id", I18n.get("menu_desc_ballcustom_none"))
        ).setAction((p, v) -> {
            if (!p.hasPermission("cubeball.admin")) {
                p.sendMessage(ChatColor.RED + "You do not have permission to do this!");
                return;
            }
            p.sendMessage(I18n.get("menu_desc_ballcustom_input"));
            p.closeInventory();
            MenuManager.registerChatHandler(p, s -> {
                c.ballCustomId = s == null || s.trim().isEmpty() ? null : s.trim();
                v.sendTo(p, v.getArgument());
            });
        }).setRightClickAction((p, v) -> {
            c.ballCustomId = null;
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
            match.scanPlayer();
            p.sendMessage(I18n.get("match_ready"));
            builder.refresh();
        });
        if (state == MatchState.IN_PROGRESS || state == MatchState.GOAL) {
            builder.setSlot(6, 2, RED_WOOL, I18n.get("menu_desc_stop"), null).setAction((p, v) -> {
                match.cancel();
                builder.refresh();
            }).setPrefix(ChatColor.RED.toString());
        } else {
            if (c.ballSpawn != null &&
                    !c.blueTeamSpawns.isEmpty() &&
                    !c.redTeamSpawns.isEmpty() &&
                    !c.blueTeamGoalBlocks.isEmpty() &&
                    !c.redTeamGoalBlocks.isEmpty() &&
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

    private static Material getState(MatchState state) {
        return state == MatchState.CREATED ? GRAY_CONCRETE : state == MatchState.READY ? YELLOW_CONCRETE : LIME_CONCRETE;
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
