package com.github.squi2rel.cb;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class MatchData {
    public String creator;
    public long creatorIdMost, creatorIdLeast;

    public Material cubeBallBlock = Material.IRON_BLOCK;
    /** CraftEngine 自定义内容 id（namespace:path），null 时使用原版 cubeBallBlock。 */
    public String ballCustomId = null;
    public int matchDuration = 300;
    public int maxGoal = 0;
    public int dashCooldown = 15;

    public Location ballSpawn;

    public List<Location> blueTeamGoalBlocks = new ArrayList<>();
    public List<Location> redTeamGoalBlocks = new ArrayList<>();
    public List<Location> blueTeamSpawns = new ArrayList<>();
    public List<Location> redTeamSpawns = new ArrayList<>();

    public void write(ConfigurationSection config) {
        config.set("creator", creator);
        config.set("creatorIdMost", creatorIdMost);
        config.set("creatorIdLeast", creatorIdLeast);

        config.set("cubeBallBlock", cubeBallBlock.name());
        config.set("ballCustomId", ballCustomId);
        config.set("matchDuration", matchDuration);
        config.set("maxGoal", maxGoal);
        config.set("dashCooldown", dashCooldown);

        config.set("ballSpawn", ballSpawn);
        config.set("blueTeamGoalBlocks", blueTeamGoalBlocks);
        config.set("redTeamGoalBlocks", redTeamGoalBlocks);
        config.set("blueTeamSpawns", blueTeamSpawns);
        config.set("redTeamSpawns", redTeamSpawns);
    }

    public void read(ConfigurationSection config) {
        creator = config.getString("creator");
        creatorIdMost = config.getLong("creatorIdMost");
        creatorIdLeast = config.getLong("creatorIdLeast");

        cubeBallBlock = getMaterial(config.getString("cubeBallBlock"));
        ballCustomId = config.getString("ballCustomId");
        matchDuration = config.getInt("matchDuration");
        maxGoal = config.getInt("maxGoal");
        dashCooldown = config.getInt("dashCooldown");

        ballSpawn = config.getSerializable("ballSpawn", Location.class);
        blueTeamGoalBlocks = getLocations(config, "blueTeamGoalBlocks");
        redTeamGoalBlocks = getLocations(config, "redTeamGoalBlocks");
        blueTeamSpawns = getLocations(config, "blueTeamSpawns");
        redTeamSpawns = getLocations(config, "redTeamSpawns");
    }

    public static MatchData create(String name, UUID uuid) {
        MatchData instance = new MatchData();
        instance.creator = name;
        instance.creatorIdMost = uuid.getMostSignificantBits();
        instance.creatorIdLeast = uuid.getLeastSignificantBits();
        return instance;
    }

    public static MatchData from(ConfigurationSection config) {
        MatchData instance = new MatchData();
        instance.read(config);
        return instance;
    }

    public static Material getMaterial(String material) {
        return material == null ? null : Material.matchMaterial(material);
    }

    @SuppressWarnings("unchecked")
    public static ArrayList<Location> getLocations(ConfigurationSection config, String path) {
        return new ArrayList<>((List<Location>) Objects.requireNonNull(config.getList(path)));
    }
}
