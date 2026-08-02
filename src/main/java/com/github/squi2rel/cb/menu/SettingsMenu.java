package com.github.squi2rel.cb.menu;

import com.github.squi2rel.cb.GoalSelectionManager;
import com.github.squi2rel.cb.I18n;
import com.github.squi2rel.cb.MatchData;
import com.github.squi2rel.cb.menu.builder.DynamicMenuBuilder;
import com.github.squi2rel.cb.menu.builder.MenuContext;
import com.github.squi2rel.cb.menu.builder.MenuManager;
import com.github.squi2rel.cb.util.FoliaScheduler;
import me.crylonz.CraftEngineHook;
import me.crylonz.CubeBall;
import me.crylonz.Match;
import me.crylonz.MatchState;
import me.crylonz.ResidenceHook;
import me.crylonz.VisualEffects;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

import static org.bukkit.Material.*;

public final class SettingsMenu {
    private static final int MAX_SPAWNS = 20;
    private static final int[] LIST_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private SettingsMenu() {
    }

    public static final DynamicMenuBuilder<Match> desc = new DynamicMenuBuilder<>(I18n.get("menu_desc_title"), 6,
            (builder, player, match) -> {
                if (!canManage(player, match)) {
                    builder.setSlot(4, 2, BARRIER, I18n.get("command_no_permission"), I18n.get("menu_no_access_lore"));
                    builder.setSlot(0, 5, ARROW, I18n.get("menu_back"), I18n.get("menu_back_lore"))
                            .setAction((p, view) -> view.openParent(p));
                    return;
                }

                MatchData data = match.getData();
                builder.setLorePrefix(ChatColor.GRAY.toString());
                builder.setSlot(0, 0, teamAccent(TeamColor.RED), "§c红队配置", "§7设置出生点与球门区域");
                builder.setSlot(8, 0, teamAccent(TeamColor.BLUE), "§9蓝队配置", "§7设置出生点与球门区域");
                builder.setSlot(4, 0, stateIcon(match.getMatchState()), "§f" + match.getName(), matchSummary(match))
                        .setGlowing(match.isInProgress());

                if (match.isInProgress()) {
                    boolean paused = match.getMatchState() == MatchState.PAUSED;
                    builder.setSlot(4, 2, paused ? CLOCK : FILLED_MAP,
                            paused ? "§e比赛已暂停" : "§a比赛正在进行",
                            "§7当前比分: §9" + match.getBlueScore() + " §7- §c" + match.getRedScore()
                                    + "\n§7剩余时间: §f" + Math.max(0, match.matchTimer) + " 秒"
                                    + "\n\n§7比赛期间场地配置已锁定").setGlowing(!paused);
                    builder.setSlot(4, 3, paused ? LIME_CONCRETE : YELLOW_CONCRETE,
                                    paused ? "§a恢复比赛" : "§e技术暂停",
                                    paused ? "§7结束暂停并进行 3 秒开球倒计时\n§e左键 §7恢复比赛"
                                            : "§7立即停止计时并收回足球\n§e左键 §7暂停比赛")
                            .setLeftClickAction((p, view) -> runPauseAction(p, view, match, paused));
                    builder.setSlot(6, 3, REDSTONE_BLOCK, "§c终止当前比赛",
                                    "§7立即按当前比分结束比赛\n§c此操作不可撤销")
                            .setLeftClickAction((p, view) -> {
                                p.sendMessage(match.forceEndMatch());
                                builder.refresh();
                            });
                    builder.setSlot(0, 5, ARROW, I18n.get("menu_back"), I18n.get("menu_back_lore"))
                            .setAction((p, view) -> view.openParent(p));
                    builder.setAutoClose(false);
                    return;
                }

                builder.setSlot(1, 1, RED_BED, I18n.get("menu_desc_redspawn"), spawnLore(data.redTeamSpawns.size()))
                        .setLeftClickAction((p, view) -> addSpawn(p, data.redTeamSpawns, builder))
                        .setRightClickAction((p, view) -> removeSpawn(p, data.redTeamSpawns, builder))
                        .setRightShiftClickAction((p, view) -> clearSpawns(p, data.redTeamSpawns, builder));
                builder.setSlot(7, 1, BLUE_BED, I18n.get("menu_desc_bluespawn"), spawnLore(data.blueTeamSpawns.size()))
                        .setLeftClickAction((p, view) -> addSpawn(p, data.blueTeamSpawns, builder))
                        .setRightClickAction((p, view) -> removeSpawn(p, data.blueTeamSpawns, builder))
                        .setRightShiftClickAction((p, view) -> clearSpawns(p, data.blueTeamSpawns, builder));

                builder.setSlot(1, 2, RED_BANNER, I18n.get("menu_desc_redgoal"), goalLore(data.getRedTeamGoalSize()))
                        .setLeftClickAction((p, view) -> beginGoalSelection(p, data, true))
                        .setRightClickAction((p, view) -> clearGoal(p, data, true, builder));
                builder.setSlot(7, 2, BLUE_BANNER, I18n.get("menu_desc_bluegoal"), goalLore(data.getBlueTeamGoalSize()))
                        .setLeftClickAction((p, view) -> beginGoalSelection(p, data, false))
                        .setRightClickAction((p, view) -> clearGoal(p, data, false, builder));

                Location ballSpawn = data.ballSpawn;
                builder.setSlot(4, 1, RESPAWN_ANCHOR, I18n.get("menu_desc_ballspawn"), ballSpawnLore(ballSpawn))
                        .setLeftClickAction((p, view) -> {
                            if (!checkResidence(p)) return;
                            data.ballSpawn = entityToBlock(p.getLocation().add(0, 2, 0));
                            VisualEffects.setupSuccess(p);
                            builder.refresh();
                        });
                builder.setSlot(4, 2, SPYGLASS, I18n.get("menu_desc_scanplayer"), scanLore(match))
                        .setLeftClickAction((p, view) -> {
                            if (match.isInProgress()) {
                                p.sendMessage(I18n.get("match_not_ready"));
                                return;
                            }
                            if (!match.isConfiguredForStart()) {
                                p.sendMessage(I18n.get("menu_setup_incomplete"));
                                return;
                            }
                            p.sendMessage(I18n.get("match_scan_started"));
                            match.scanPlayer(() -> FoliaScheduler.runEntity(p, () -> {
                                if (!p.isOnline()) return;
                                p.sendMessage(I18n.get("match_ready"));
                                VisualEffects.setupSuccess(p);
                                view.sendTo(p, match);
                            }));
                        });

                builder.setSlot(2, 3, data.cubeBallBlock, I18n.get("menu_desc_ballblock"),
                                "§7当前方块: §f" + data.cubeBallBlock.name() + "\n§e左键 §7输入新的原版方块")
                        .setLeftClickAction((p, view) -> requestMaterial(p, view, data));
                builder.setSlot(4, 3, getBallCustomIcon(data), I18n.get("menu_desc_ballcustom"), getBallCustomDesc(data))
                        .setLeftClickAction((p, view) -> setCustomFromHand(p, match, data, builder))
                        .setLeftShiftClickAction((p, view) -> requestCustomId(p, view, data))
                        .setRightClickAction((p, view) -> {
                            data.ballCustomId = null;
                            data.ballCustomItem = null;
                            p.sendMessage(I18n.get("menu_desc_ballcustom_cleared"));
                            VisualEffects.setupSuccess(p);
                            builder.refresh();
                        });
                builder.setSlot(6, 3, FEATHER, I18n.get("menu_desc_dashcooldown"), dashLore(data.dashCooldown))
                        .setLeftClickAction((p, view) -> requestNumber(p, view, I18n.get("menu_desc_dashcooldown"), 0, 300,
                                value -> data.dashCooldown = value));

                builder.setSlot(2, 4, CLOCK, I18n.get("menu_desc_settime"), timeLore(data.matchDuration))
                        .setLeftClickAction((p, view) -> requestNumber(p, view, I18n.get("menu_desc_settime"), 30, 1800,
                                value -> data.matchDuration = value));
                builder.setSlot(4, 4, TARGET, I18n.get("menu_desc_settarget"), targetLore(data.maxGoal))
                        .setLeftClickAction((p, view) -> requestNumber(p, view, I18n.get("menu_desc_settarget"), 0, 999,
                                value -> data.maxGoal = value));

                if (match.isInProgress()) {
                    builder.setSlot(6, 4, REDSTONE_BLOCK, "§c终止当前比赛", "§7立即按当前比分结束比赛\n§c此操作不可撤销")
                            .setLeftClickAction((p, view) -> {
                                p.sendMessage(match.forceEndMatch());
                                builder.refresh();
                            });
                } else {
                    boolean ready = match.isConfiguredForStart() && match.getMatchState() == MatchState.READY;
                    builder.setSlot(6, 4, ready ? LIME_CONCRETE : GRAY_CONCRETE,
                                    ready ? "§a开始比赛" : "§7开始比赛", startLore(data, match))
                            .setGlowing(ready)
                            .setLeftClickAction((p, view) -> {
                                if (!ready) {
                                    p.sendMessage(I18n.get("menu_setup_incomplete"));
                                    return;
                                }
                                match.start(p);
                                p.closeInventory();
                            });
                }

                builder.setSlot(0, 5, ARROW, I18n.get("menu_back"), I18n.get("menu_back_lore"))
                        .setAction((p, view) -> {
                            CubeBall.saveAsync();
                            view.openParent(p);
                        });
                builder.setSlot(4, 5, WRITABLE_BOOK, "§a保存场地", "§7保存当前场地的全部配置\n§e左键 §7保存并刷新")
                        .setLeftClickAction((p, view) -> {
                            CubeBall.saveAsync();
                            p.sendMessage(I18n.get("menu_saved"));
                            VisualEffects.setupSuccess(p);
                            builder.refresh();
                        });
                builder.setSlot(8, 5, BARRIER, I18n.get("menu_desc_delete"), "§7删除场地及其全部配置\n§c需要再次确认")
                        .setLeftClickAction((p, view) -> {
                            DynamicMenuBuilder.DynamicMenuContext<Match> confirm = deleteConfirmMenu().build();
                            confirm.setParent(view);
                            confirm.sendTo(p, match);
                        });
                builder.setAutoClose(false);
            });

    public static final DynamicMenuBuilder<Integer> list = new DynamicMenuBuilder<>(I18n.get("menu_list_title"), 6,
            (builder, player, requestedPage) -> {
                List<Match> matches = visibleMatches(player);
                int pages = Math.max(1, (matches.size() + LIST_SLOTS.length - 1) / LIST_SLOTS.length);
                int page = Math.max(0, Math.min(requestedPage == null ? 0 : requestedPage, pages - 1));

                if (matches.isEmpty()) {
                    builder.setSlot(4, 2, LIGHT_GRAY_STAINED_GLASS_PANE, I18n.get("menu_list_empty"), I18n.get("menu_list_empty_lore"));
                } else {
                    int start = page * LIST_SLOTS.length;
                    int end = Math.min(start + LIST_SLOTS.length, matches.size());
                    for (int index = start; index < end; index++) {
                        Match match = matches.get(index);
                        int slot = LIST_SLOTS[index - start];
                        builder.setSlot(slot % 9, slot / 9, stateIcon(match.getMatchState()),
                                        stateColor(match.getMatchState()) + match.getName(), matchListLore(match))
                                .setGlowing(match.isInProgress())
                                .setLeftClickAction((p, view) -> {
                                    DynamicMenuBuilder.DynamicMenuContext<Match> details = desc.build();
                                    details.setParent(view);
                                    details.sendTo(p, match);
                                });
                    }
                }

                builder.setSlot(0, 5, ARROW, I18n.get("menu_back"), I18n.get("menu_back_lore"))
                        .setAction((p, view) -> view.openParent(p));
                if (page > 0) {
                    builder.setSlot(3, 5, SPECTRAL_ARROW, "§e上一页", "§7前往第 §f" + page + " §7页")
                            .setLeftClickAction((p, view) -> view.sendTo(p, page - 1));
                }
                builder.setSlot(4, 5, MAP, "§f第 " + (page + 1) + " / " + pages + " 页",
                        "§7场地数量: §f" + matches.size());
                if (page + 1 < pages) {
                    builder.setSlot(5, 5, SPECTRAL_ARROW, "§e下一页", "§7前往第 §f" + (page + 2) + " §7页")
                            .setLeftClickAction((p, view) -> view.sendTo(p, page + 1));
                }
                builder.setSlot(8, 5, LIME_DYE, I18n.get("menu_new"), "§7创建一座新的足球场地\n§e左键 §7开始输入")
                        .setLeftClickAction((p, view) -> beginCreate(p, view));
                builder.setAutoClose(false);
            });

    private static final DynamicMenuBuilder<Match> deleteConfirm = new DynamicMenuBuilder<>(I18n.get("menu_delete_title"), 3,
            (builder, player, match) -> {
                builder.setSlot(4, 0, stateIcon(match.getMatchState()), "§f" + match.getName(), "§7即将永久删除此场地");
                builder.setSlot(2, 1, LIME_CONCRETE, "§a返回", "§7取消删除并返回场地配置")
                        .setLeftClickAction((p, view) -> view.openParent(p));
                builder.setSlot(6, 1, RED_CONCRETE, "§c确认删除", "§7场地、出生点与球门配置都会删除\n§c此操作不可撤销")
                        .setLeftClickAction((p, view) -> {
                            if (match.isInProgress()) match.forceEndMatch();
                            else match.cancel();
                            CubeBall.matches.remove(match.getName(), match);
                            CubeBall.saveAsync();
                            p.sendMessage(I18n.get("match_removed"));
                            DynamicMenuBuilder.DynamicMenuContext<Integer> matches = list.build();
                            matches.setParent(settingsMenu());
                            matches.sendTo(p, 0);
                        });
                builder.setAutoClose(false);
            });

    private static final DynamicMenuBuilder<Void> settingsBuilder = new DynamicMenuBuilder<>(I18n.get("menu_title"), 6,
            (builder, player, ignored) -> {
        builder.setSlot(4, 0, NETHER_STAR, "§b§l足球系统 §f管理中心",
                "§7创建、配置并管理足球比赛"
                        + "\n§7场地总数: §f" + CubeBall.matches.size()
                        + "\n§7玩家识别: §f" + identityName()
                        + "\n§8版本 " + CubeBall.plugin.getPluginMeta().getVersion()).setGlowing(true);
        builder.setSlot(2, 2, LIME_CONCRETE, I18n.get("menu_new"),
                        "§7创建一座新的足球场地\n§e左键 §7开始输入")
                .setLeftClickAction(SettingsMenu::beginCreate);
        builder.setSlot(4, 2, BOOK, I18n.get("menu_list"),
                        "§7查看可管理的全部场地\n§7当前共 §f" + CubeBall.matches.size() + " §7座")
                .setLeftClickAction((p, view) -> {
                    DynamicMenuBuilder.DynamicMenuContext<Integer> matches = list.build();
                    matches.setParent(view);
                    matches.sendTo(p, 0);
                });
        builder.setSlot(6, 2, COMPASS, "§e设置等待大厅", locationLore(CubeBall.getLobbySpawn()))
                .setLeftClickAction((p, view) -> {
                    if (!requireAdmin(p)) return;
                    CubeBall.setLobbySpawn(p.getLocation());
                    p.sendMessage(I18n.get("lobby_spawn_set"));
                    VisualEffects.setupSuccess(p);
                    view.sendTo(p);
                });
        builder.setSlot(2, 4, ENDER_PEARL, "§d设置退出位置", locationLore(CubeBall.getExitSpawn()))
                .setLeftClickAction((p, view) -> {
                    if (!requireAdmin(p)) return;
                    CubeBall.setExitSpawn(p.getLocation());
                    p.sendMessage(I18n.get("exit_spawn_set"));
                    VisualEffects.setupSuccess(p);
                    view.sendTo(p);
                });
        builder.setSlot(4, 4, CubeBall.ballGlow ? GLOWSTONE_DUST : GUNPOWDER, "§f足球发光",
                        toggleLore(CubeBall.ballGlow))
                .setGlowing(CubeBall.ballGlow)
                .setLeftClickAction((p, view) -> {
                    if (!requireAdmin(p)) return;
                    CubeBall.setBallGlow(!CubeBall.ballGlow);
                    view.sendTo(p);
                });
        builder.setSlot(6, 4, MINECART, "§f足球滚动动画", toggleLore(CubeBall.ballRollEnabled))
                .setGlowing(CubeBall.ballRollEnabled)
                .setLeftClickAction((p, view) -> {
                    if (!requireAdmin(p)) return;
                    CubeBall.setBallRollEnabled(!CubeBall.ballRollEnabled);
                    view.sendTo(p);
                });
        builder.setSlot(8, 5, NAME_TAG, "§b玩家识别方式",
                        "§7当前模式: §f" + identityName()
                                + "\n§7name: §f离线服按名字识别"
                                + "\n§7uuid: §f正版服按 UUID 识别"
                                + "\n\n§e左键 §7切换模式")
                .setLeftClickAction((p, view) -> {
                    if (!requireAdmin(p)) return;
                    CubeBall.setPlayerIdentityMode(CubeBall.usesNameIdentity() ? "uuid" : "name");
                    p.sendMessage(I18n.format("identity_mode_changed", "mode", identityName()));
                    VisualEffects.setupSuccess(p);
                    view.sendTo(p);
                });
            builder.setAutoClose(false);
    });

    public static void open(Player player) {
        settingsBuilder.build().sendTo(player, null);
    }

    private static void beginCreate(Player player, MenuContext<?> parent) {
        if (!player.hasPermission("cubeball.admin") && ownedMatchCount(player) >= CubeBall.maxMatchPerPlayer) {
            player.sendMessage(I18n.format("menu_new_limit", "n", CubeBall.maxMatchPerPlayer));
            return;
        }
        player.sendMessage(I18n.get("menu_new_name"));
        player.sendMessage(I18n.get("menu_input_cancel"));
        player.closeInventory();
        MenuManager.registerChatHandler(player, input -> {
            if (isCancel(input)) {
                player.sendMessage(I18n.get("menu_input_cancelled"));
                parent.sendTo(player);
                return;
            }
            String name = input.trim();
            if (!validMatchName(name)) {
                player.sendMessage(I18n.get("menu_new_invalid"));
                parent.sendTo(player);
                return;
            }
            if (CubeBall.matches.keySet().stream().anyMatch(existing -> existing.equalsIgnoreCase(name))) {
                player.sendMessage(I18n.get("menu_new_existed"));
                parent.sendTo(player);
                return;
            }
            Match match = new Match(name, player);
            CubeBall.matches.put(name, match);
            CubeBall.saveAsync();
            player.sendMessage(I18n.get("menu_new_success"));
            VisualEffects.setupSuccess(player);
            DynamicMenuBuilder.DynamicMenuContext<Match> details = desc.build();
            details.setParent(parent);
            details.sendTo(player, match);
        });
    }

    private static void runPauseAction(Player player, MenuContext<Match> view, Match match, boolean resume) {
        FoliaScheduler.runGlobal(() -> {
            String result = resume ? match.adminResume() : match.adminPause();
            FoliaScheduler.runEntity(player, () -> {
                if (!player.isOnline()) return;
                player.sendMessage(result);
                view.sendTo(player, match);
            });
        });
    }

    private static void requestMaterial(Player player, MenuContext<Match> view, MatchData data) {
        if (!requireAdmin(player)) return;
        player.sendMessage(I18n.get("menu_desc_material_name"));
        player.sendMessage(I18n.get("menu_input_cancel"));
        player.closeInventory();
        MenuManager.registerChatHandler(player, input -> {
            if (isCancel(input)) {
                player.sendMessage(I18n.get("menu_input_cancelled"));
                view.sendTo(player);
                return;
            }
            Material material = Material.matchMaterial(input.trim().toUpperCase(Locale.ROOT));
            if (material == null || !material.isBlock() || material.isAir() || !material.isItem()) {
                player.sendMessage(I18n.get("menu_desc_invalid_material"));
                view.sendTo(player);
                return;
            }
            data.cubeBallBlock = material;
            VisualEffects.setupSuccess(player);
            view.sendTo(player);
        });
    }

    private static void requestCustomId(Player player, MenuContext<Match> view, MatchData data) {
        if (!requireAdmin(player)) return;
        player.sendMessage(I18n.get("menu_desc_ballcustom_input"));
        player.sendMessage(I18n.get("menu_input_cancel"));
        player.closeInventory();
        MenuManager.registerChatHandler(player, input -> {
            if (isCancel(input)) {
                player.sendMessage(I18n.get("menu_input_cancelled"));
                view.sendTo(player);
                return;
            }
            if (setBallCustomId(data, player, input)) VisualEffects.setupSuccess(player);
            view.sendTo(player);
        });
    }

    private static void requestNumber(Player player, MenuContext<Match> view, String field,
                                      int minimum, int maximum, IntConsumer setter) {
        player.sendMessage(I18n.format("menu_number_prompt", "field", field, "min", minimum, "max", maximum));
        player.sendMessage(I18n.get("menu_input_cancel"));
        player.closeInventory();
        MenuManager.registerChatHandler(player, input -> {
            if (isCancel(input)) {
                player.sendMessage(I18n.get("menu_input_cancelled"));
                view.sendTo(player);
                return;
            }
            int value = tryParseInt(input.trim());
            if (value < minimum || value > maximum) {
                player.sendMessage(I18n.format("menu_number_range", "min", minimum, "max", maximum));
                view.sendTo(player);
                return;
            }
            setter.accept(value);
            VisualEffects.setupSuccess(player);
            view.sendTo(player);
        });
    }

    private static void setCustomFromHand(Player player, Match match, MatchData data, DynamicMenuBuilder<Match> builder) {
        if (!requireAdmin(player)) return;
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            player.sendMessage(I18n.get("menu_desc_ballcustom_not_item"));
            return;
        }
        ItemStack snapshot = hand.clone();
        snapshot.setAmount(1);
        String id = CraftEngineHook.getCustomItemId(hand);
        data.ballCustomId = id;
        data.ballCustomItem = snapshot;
        CubeBall.debug("menu custom item set match=" + match.getName() + " id=" + id + " item=" + CubeBall.describeItem(snapshot));
        player.sendMessage(I18n.format("menu_desc_ballcustom_set", "id", id == null ? snapshot.getType().name() : id));
        VisualEffects.setupSuccess(player);
        builder.refresh();
    }

    private static void beginGoalSelection(Player player, MatchData data, boolean red) {
        GoalSelectionManager.begin(player, data, red);
        player.closeInventory();
    }

    private static void clearGoal(Player player, MatchData data, boolean red, DynamicMenuBuilder<Match> builder) {
        if (red) {
            data.redTeamGoalPos1 = null;
            data.redTeamGoalPos2 = null;
            data.redTeamGoalBlocks.clear();
        } else {
            data.blueTeamGoalPos1 = null;
            data.blueTeamGoalPos2 = null;
            data.blueTeamGoalBlocks.clear();
        }
        VisualEffects.setupSuccess(player);
        builder.refresh();
    }

    private static void addSpawn(Player player, List<Location> spawns, DynamicMenuBuilder<Match> builder) {
        if (!checkResidence(player)) return;
        if (spawns.size() >= MAX_SPAWNS) {
            player.sendMessage(I18n.format("menu_spawn_limit", "max", MAX_SPAWNS));
            return;
        }
        spawns.add(entityToBlock(player.getLocation()));
        VisualEffects.setupSuccess(player);
        builder.refresh();
    }

    private static void removeSpawn(Player player, List<Location> spawns, DynamicMenuBuilder<Match> builder) {
        if (spawns.isEmpty()) return;
        spawns.remove(spawns.size() - 1);
        VisualEffects.setupSuccess(player);
        builder.refresh();
    }

    private static void clearSpawns(Player player, List<Location> spawns, DynamicMenuBuilder<Match> builder) {
        if (spawns.isEmpty()) return;
        spawns.clear();
        VisualEffects.setupSuccess(player);
        builder.refresh();
    }

    private static List<Match> visibleMatches(Player player) {
        List<Match> values = new ArrayList<>();
        for (Match match : CubeBall.matches.values()) {
            if (player.hasPermission("cubeball.admin") || isOwner(player, match)) values.add(match);
        }
        values.sort(Comparator.comparing(Match::getName, String.CASE_INSENSITIVE_ORDER));
        return values;
    }

    private static boolean canManage(Player player, Match match) {
        return player.hasPermission("cubeball.admin") || isOwner(player, match);
    }

    private static boolean isOwner(Player player, Match match) {
        return CubeBall.isMatchOwner(player, match.getData());
    }

    private static long ownedMatchCount(Player player) {
        return CubeBall.matches.values().stream().filter(match -> isOwner(player, match)).count();
    }

    private static boolean requireAdmin(Player player) {
        if (player.hasPermission("cubeball.admin")) return true;
        player.sendMessage(I18n.get("command_no_permission"));
        return false;
    }

    private static boolean checkResidence(Player player) {
        if (!ResidenceHook.isAvailable() || ResidenceHook.isInAnyResidence(player.getLocation())) return true;
        player.sendMessage(I18n.get("setup_requires_residence"));
        return false;
    }

    private static boolean validMatchName(String name) {
        return !name.isBlank() && name.length() <= 32 && name.matches("[\\p{L}\\p{N}_\\- ]+");
    }

    private static boolean isCancel(String input) {
        return input != null && input.trim().equalsIgnoreCase("T");
    }

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
        } else if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            player.sendMessage(I18n.get("menu_desc_ballcustom_invalid"));
            return false;
        } else {
            player.sendMessage(I18n.get("menu_desc_ballcustom_ce_missing"));
        }
        data.ballCustomId = id;
        data.ballCustomItem = null;
        player.sendMessage(I18n.format("menu_desc_ballcustom_set", "id", id));
        return true;
    }

    private static Material stateIcon(MatchState state) {
        if (state == MatchState.CREATED) return GRAY_DYE;
        if (state == MatchState.READY) return YELLOW_DYE;
        if (state == MatchState.IN_PROGRESS || state == MatchState.OVERTIME) return LIME_DYE;
        return ORANGE_DYE;
    }

    private static String stateColor(MatchState state) {
        if (state == MatchState.CREATED) return "§7";
        if (state == MatchState.READY) return "§e";
        if (state == MatchState.IN_PROGRESS || state == MatchState.OVERTIME) return "§a";
        return "§6";
    }

    private static String stateName(MatchState state) {
        if (state == MatchState.CREATED) return I18n.get("menu_state_created");
        if (state == MatchState.READY) return I18n.get("menu_state_ready");
        if (state == MatchState.IN_PROGRESS) return I18n.get("menu_state_running");
        if (state == MatchState.OVERTIME) return I18n.get("menu_state_overtime");
        if (state == MatchState.GOAL) return I18n.get("menu_state_goal");
        if (state == MatchState.PAUSED) return I18n.get("menu_state_paused");
        return state.name();
    }

    private static Material teamAccent(TeamColor color) {
        return color == TeamColor.RED ? RED_STAINED_GLASS_PANE : BLUE_STAINED_GLASS_PANE;
    }

    private static String matchSummary(Match match) {
        MatchData data = match.getData();
        return "§7状态: " + stateColor(match.getMatchState()) + stateName(match.getMatchState())
                + "\n§7配置进度: " + completionBar(data)
                + "\n§7场地世界: §f" + fieldWorld(data)
                + "\n§7当前比分: §9" + match.getBlueScore() + " §7- §c" + match.getRedScore();
    }

    private static String matchListLore(Match match) {
        MatchData data = match.getData();
        return "§7创建者: §f" + data.creator
                + "\n§7状态: " + stateColor(match.getMatchState()) + stateName(match.getMatchState())
                + "\n§7配置进度: " + completionBar(data)
                + "\n§7场地世界: §f" + fieldWorld(data)
                + "\n§7队伍: §9" + match.getBlueTeam().size() + " §7/ §c" + match.getRedTeam().size()
                + "\n\n§e左键 §7打开场地配置";
    }

    private static int completion(MatchData data) {
        int complete = 0;
        if (!data.redTeamSpawns.isEmpty()) complete++;
        if (!data.blueTeamSpawns.isEmpty()) complete++;
        if (data.hasRedTeamGoalArea()) complete++;
        if (data.hasBlueTeamGoalArea()) complete++;
        if (data.ballSpawn != null) complete++;
        return complete;
    }

    private static String completionBar(MatchData data) {
        int value = completion(data);
        return "§a" + "■".repeat(value) + "§8" + "■".repeat(5 - value) + " §f" + value + "/5";
    }

    private static String fieldWorld(MatchData data) {
        return data.ballSpawn == null || data.ballSpawn.getWorld() == null ? "未设置" : data.ballSpawn.getWorld().getName();
    }

    private static String identityName() {
        return CubeBall.usesNameIdentity() ? "name（离线服）" : "uuid（正版服）";
    }

    private static String spawnLore(int count) {
        return "§7当前出生点: §f" + count + "/" + MAX_SPAWNS
                + "\n\n§e左键 §7添加当前位置"
                + "\n§e右键 §7移除最后一个"
                + "\n§eShift + 右键 §7清空全部";
    }

    private static String goalLore(int count) {
        return "§7当前区域方块: §f" + count
                + "\n\n§e左键 §7使用石铲圈选"
                + "\n§e右键 §7清空球门区域";
    }

    private static String ballSpawnLore(Location location) {
        if (location == null) return "§c尚未设置\n\n§e左键 §7设为当前位置上方";
        return "§a已设置\n" + locationLore(location) + "\n\n§e左键 §7更新位置";
    }

    private static String scanLore(Match match) {
        return "§7红队: §c" + match.getRedTeam().size()
                + " §7| 蓝队: §9" + match.getBlueTeam().size()
                + "\n§7旁观者: §f" + match.getSpectatorTeam().size()
                + "\n\n§e左键 §7扫描出生点附近玩家";
    }

    private static String dashLore(int seconds) {
        return seconds <= 0 ? "§7当前: §c已禁用\n\n§e左键 §7输入冷却秒数"
                : "§7当前冷却: §f" + seconds + " 秒\n\n§e左键 §7修改数值";
    }

    private static String timeLore(int seconds) {
        return "§7当前时长: §f" + (seconds / 60) + " 分 " + (seconds % 60) + " 秒"
                + "\n§7可设置范围: §f30 - 1800 秒\n\n§e左键 §7修改数值";
    }

    private static String targetLore(int goals) {
        return "§7目标分数: §f" + (goals <= 0 ? "无限制" : goals)
                + "\n§70 表示不限制进球数\n\n§e左键 §7修改数值";
    }

    private static String startLore(MatchData data, Match match) {
        if (match.isConfiguredForStart() && match.getMatchState() == MatchState.READY) {
            return "§a场地与双方队伍均已就绪"
                    + "\n§7红队: §c" + match.getRedTeam().size() + " 人"
                    + "\n§7蓝队: §9" + match.getBlueTeam().size() + " 人"
                    + "\n\n§e左键 §7开始比赛";
        }
        return "§c尚未满足开赛条件"
                + readinessLine("红队出生点", !data.redTeamSpawns.isEmpty())
                + readinessLine("蓝队出生点", !data.blueTeamSpawns.isEmpty())
                + readinessLine("红队球门", data.hasRedTeamGoalArea())
                + readinessLine("蓝队球门", data.hasBlueTeamGoalArea())
                + readinessLine("足球出生点", data.ballSpawn != null)
                + readinessLine("坐标同一世界", data.isConfiguredForStart())
                + readinessLine("双方已有玩家", !match.getRedTeam().isEmpty() && !match.getBlueTeam().isEmpty())
                + "\n\n§e完成配置后重新扫描玩家";
    }

    private static String readinessLine(String label, boolean ready) {
        return "\n" + (ready ? "§a✔ " : "§c✘ ") + "§7" + label;
    }

    private static String getBallCustomDesc(MatchData data) {
        String current = data.ballCustomId;
        if ((current == null || current.isEmpty()) && data.ballCustomItem != null) current = data.ballCustomItem.getType().name();
        return "§7当前外观: §f" + (current == null || current.isEmpty() ? I18n.get("menu_desc_ballcustom_none") : current)
                + "\n\n§e左键 §7读取主手物品"
                + "\n§eShift + 左键 §7输入 CraftEngine ID"
                + "\n§e右键 §7恢复原版方块";
    }

    private static String locationLore(Location location) {
        if (location == null || location.getWorld() == null) return "§c尚未设置\n§e左键 §7设为当前位置";
        return "§7世界: §f" + location.getWorld().getName()
                + "\n§7坐标: §f" + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ()
                + "\n§e左键 §7更新位置";
    }

    private static String toggleLore(boolean enabled) {
        return "§7当前状态: " + (enabled ? "§a已开启" : "§c已关闭") + "\n§e左键 §7切换状态";
    }

    private static Location entityToBlock(Location location) {
        return new Location(location.getWorld(), location.getBlockX() + 0.5, location.getBlockY() + 0.5,
                location.getBlockZ() + 0.5, location.getYaw(), location.getPitch());
    }

    private static int tryParseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private static DynamicMenuBuilder<Match> deleteConfirmMenu() {
        return deleteConfirm;
    }

    private static MenuContext<Void> settingsMenu() {
        return settingsBuilder.build();
    }

    private enum TeamColor {
        RED, BLUE
    }
}
