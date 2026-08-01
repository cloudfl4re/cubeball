package com.github.squi2rel.cb;

import me.crylonz.CubeBall;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class MatchData {
    public String creator;
    public long creatorIdMost, creatorIdLeast;

    public Material cubeBallBlock = Material.IRON_BLOCK;
    /** CraftEngine 自定义内容 id（namespace:path），null 时使用原版 cubeBallBlock。 */
    public String ballCustomId = null;
    public ItemStack ballCustomItem = null;
    public int matchDuration = 300;
    public int maxGoal = 0;
    public int dashCooldown = 15;

    public Location ballSpawn;

    public Location blueTeamGoalPos1;
    public Location blueTeamGoalPos2;
    public Location redTeamGoalPos1;
    public Location redTeamGoalPos2;

    public List<Location> blueTeamGoalBlocks = new CopyOnWriteArrayList<>();
    public List<Location> redTeamGoalBlocks = new CopyOnWriteArrayList<>();
    public List<Location> blueTeamSpawns = new CopyOnWriteArrayList<>();
    public List<Location> redTeamSpawns = new CopyOnWriteArrayList<>();

    public void write(ConfigurationSection config) {
        config.set("creator", creator);
        config.set("creatorIdMost", creatorIdMost);
        config.set("creatorIdLeast", creatorIdLeast);

        config.set("cubeBallBlock", cubeBallBlock.name());
        config.set("ballCustomId", ballCustomId);
        config.set("ballCustomItem", ballCustomItem);
        config.set("matchDuration", matchDuration);
        config.set("maxGoal", maxGoal);
        config.set("dashCooldown", dashCooldown);

        config.set("ballSpawn", ballSpawn);
        config.set("blueTeamGoalPos1", blueTeamGoalPos1);
        config.set("blueTeamGoalPos2", blueTeamGoalPos2);
        config.set("redTeamGoalPos1", redTeamGoalPos1);
        config.set("redTeamGoalPos2", redTeamGoalPos2);
        config.set("blueTeamGoalBlocks", blueTeamGoalBlocks);
        config.set("redTeamGoalBlocks", redTeamGoalBlocks);
        config.set("blueTeamSpawns", blueTeamSpawns);
        config.set("redTeamSpawns", redTeamSpawns);
        CubeBall.debug("MatchData.write customId=" + ballCustomId + " customItem=" + CubeBall.describeItem(ballCustomItem));
    }

    public void read(ConfigurationSection config) {
        creator = config.getString("creator");
        creatorIdMost = config.getLong("creatorIdMost");
        creatorIdLeast = config.getLong("creatorIdLeast");

        Material material = getMaterial(config.getString("cubeBallBlock"));
        cubeBallBlock = material == null ? Material.IRON_BLOCK : material;
        ballCustomId = config.getString("ballCustomId");
        ballCustomItem = config.getItemStack("ballCustomItem");
        if (ballCustomItem != null) ballCustomItem.setAmount(1);
        matchDuration = config.getInt("matchDuration");
        maxGoal = config.getInt("maxGoal");
        dashCooldown = config.getInt("dashCooldown");

        ballSpawn = config.getSerializable("ballSpawn", Location.class);
        blueTeamGoalPos1 = config.getSerializable("blueTeamGoalPos1", Location.class);
        blueTeamGoalPos2 = config.getSerializable("blueTeamGoalPos2", Location.class);
        redTeamGoalPos1 = config.getSerializable("redTeamGoalPos1", Location.class);
        redTeamGoalPos2 = config.getSerializable("redTeamGoalPos2", Location.class);
        blueTeamGoalBlocks = getLocations(config, "blueTeamGoalBlocks");
        redTeamGoalBlocks = getLocations(config, "redTeamGoalBlocks");
        blueTeamSpawns = getLocations(config, "blueTeamSpawns");
        redTeamSpawns = getLocations(config, "redTeamSpawns");
        CubeBall.debug("MatchData.read customId=" + ballCustomId + " customItem=" + CubeBall.describeItem(ballCustomItem));
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

    public boolean hasBlueTeamGoalArea() {
        return hasRegion(blueTeamGoalPos1, blueTeamGoalPos2) || !blueTeamGoalBlocks.isEmpty();
    }

    public boolean hasRedTeamGoalArea() {
        return hasRegion(redTeamGoalPos1, redTeamGoalPos2) || !redTeamGoalBlocks.isEmpty();
    }

    public boolean isInBlueTeamGoal(Location location) {
        return isInRegion(location, blueTeamGoalPos1, blueTeamGoalPos2) || isInLegacyGoal(location, blueTeamGoalBlocks);
    }

    public boolean isInRedTeamGoal(Location location) {
        return isInRegion(location, redTeamGoalPos1, redTeamGoalPos2) || isInLegacyGoal(location, redTeamGoalBlocks);
    }

    public boolean intersectsBlueTeamGoal(World world, BoundingBox box) {
        return intersectsRegion(world, box, blueTeamGoalPos1, blueTeamGoalPos2);
    }

    public boolean intersectsRedTeamGoal(World world, BoundingBox box) {
        return intersectsRegion(world, box, redTeamGoalPos1, redTeamGoalPos2);
    }

    public boolean isNearBlueTeamGoal(Location location, double radius) {
        return isNearRegion(location, blueTeamGoalPos1, blueTeamGoalPos2, radius) || isNearLegacyGoal(location, blueTeamGoalBlocks, radius);
    }

    public boolean isNearRedTeamGoal(Location location, double radius) {
        return isNearRegion(location, redTeamGoalPos1, redTeamGoalPos2, radius) || isNearLegacyGoal(location, redTeamGoalBlocks, radius);
    }

    public Location getBlueTeamGoalCenter() {
        return getRegionCenter(blueTeamGoalPos1, blueTeamGoalPos2, blueTeamGoalBlocks);
    }

    public Location getRedTeamGoalCenter() {
        return getRegionCenter(redTeamGoalPos1, redTeamGoalPos2, redTeamGoalBlocks);
    }

    public Location getBlueTeamGoalPos1() {
        return blueTeamGoalPos1;
    }

    public Location getBlueTeamGoalPos2() {
        return blueTeamGoalPos2;
    }

    public Location getRedTeamGoalPos1() {
        return redTeamGoalPos1;
    }

    public Location getRedTeamGoalPos2() {
        return redTeamGoalPos2;
    }

    public boolean isNearAnyGoal(Location location, double radius) {
        return isNearBlueTeamGoal(location, radius) || isNearRedTeamGoal(location, radius);
    }

    public int getBlueTeamGoalSize() {
        return getRegionSize(blueTeamGoalPos1, blueTeamGoalPos2, blueTeamGoalBlocks);
    }

    public int getRedTeamGoalSize() {
        return getRegionSize(redTeamGoalPos1, redTeamGoalPos2, redTeamGoalBlocks);
    }

    private static boolean hasRegion(Location a, Location b) {
        return a != null && b != null && a.getWorld() != null && Objects.equals(a.getWorld(), b.getWorld());
    }

    private static boolean isInRegion(Location location, Location a, Location b) {
        if (location == null || !hasRegion(a, b) || !Objects.equals(location.getWorld(), a.getWorld())) return false;
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return x >= Math.min(a.getBlockX(), b.getBlockX()) && x <= Math.max(a.getBlockX(), b.getBlockX())
                && y >= Math.min(a.getBlockY(), b.getBlockY()) && y <= Math.max(a.getBlockY(), b.getBlockY())
                && z >= Math.min(a.getBlockZ(), b.getBlockZ()) && z <= Math.max(a.getBlockZ(), b.getBlockZ());
    }

    private static boolean intersectsRegion(World world, BoundingBox box, Location a, Location b) {
        if (world == null || box == null || !hasRegion(a, b) || !Objects.equals(world, a.getWorld())) return false;
        BoundingBox goal = new BoundingBox(
                Math.min(a.getBlockX(), b.getBlockX()),
                Math.min(a.getBlockY(), b.getBlockY()),
                Math.min(a.getBlockZ(), b.getBlockZ()),
                Math.max(a.getBlockX(), b.getBlockX()) + 1.0,
                Math.max(a.getBlockY(), b.getBlockY()) + 1.0,
                Math.max(a.getBlockZ(), b.getBlockZ()) + 1.0
        );
        return goal.overlaps(box);
    }

    private static boolean isNearRegion(Location location, Location a, Location b, double radius) {
        if (location == null || !hasRegion(a, b) || !Objects.equals(location.getWorld(), a.getWorld())) return false;
        BoundingBox expanded = new BoundingBox(
                Math.min(a.getBlockX(), b.getBlockX()) - radius,
                Math.min(a.getBlockY(), b.getBlockY()) - radius,
                Math.min(a.getBlockZ(), b.getBlockZ()) - radius,
                Math.max(a.getBlockX(), b.getBlockX()) + 1.0 + radius,
                Math.max(a.getBlockY(), b.getBlockY()) + 1.0 + radius,
                Math.max(a.getBlockZ(), b.getBlockZ()) + 1.0 + radius
        );
        return expanded.contains(location.toVector());
    }

    private static boolean isInLegacyGoal(Location location, List<Location> blocks) {
        if (location == null) return false;
        int ballX = location.getBlockX();
        int ballZ = location.getBlockZ();
        for (Location blockLocation : blocks) {
            if (Objects.equals(location.getWorld(), blockLocation.getWorld())
                    && ballX == blockLocation.getBlockX()
                    && ballZ == blockLocation.getBlockZ()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNearLegacyGoal(Location location, List<Location> blocks, double radius) {
        if (location == null) return false;
        double radiusSquared = radius * radius;
        for (Location blockLocation : blocks) {
            if (Objects.equals(location.getWorld(), blockLocation.getWorld())
                    && location.distanceSquared(blockLocation) <= radiusSquared) {
                return true;
            }
        }
        return false;
    }

    private static Location getRegionCenter(Location a, Location b, List<Location> fallback) {
        if (hasRegion(a, b)) {
            return new Location(a.getWorld(),
                    (a.getBlockX() + b.getBlockX()) / 2.0 + 0.5,
                    (a.getBlockY() + b.getBlockY()) / 2.0 + 0.5,
                    (a.getBlockZ() + b.getBlockZ()) / 2.0 + 0.5);
        }
        return fallback.isEmpty() ? null : fallback.get(0);
    }

    private static int getRegionSize(Location a, Location b, List<Location> fallback) {
        if (!hasRegion(a, b)) return fallback.size();
        long size = (long) (Math.abs(a.getBlockX() - b.getBlockX()) + 1)
                * (Math.abs(a.getBlockY() - b.getBlockY()) + 1)
                * (Math.abs(a.getBlockZ() - b.getBlockZ()) + 1);
        return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }

    @SuppressWarnings("unchecked")
    public static List<Location> getLocations(ConfigurationSection config, String path) {
        List<Location> list = (List<Location>) config.getList(path);
        return list == null ? new CopyOnWriteArrayList<>() : new CopyOnWriteArrayList<>(list);
    }
}
