package me.crylonz;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.block.CustomBlock;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.item.CustomItem;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

/**
 * CraftEngine 调用隔离类。本类引用了 CE 的 api 类型，只有当 CE 在场时才应被类加载，
 * 调用方（CubeBall）必须先用 {@link #isAvailable()} 判定后再触碰本类，避免 NoClassDefFoundError。
 *
 * 解析顺序：先按 id 找 CE 自定义方块（命中则 FallingBlock 直接用其 BlockData 渲染），
 * 再按 id 找 CE 自定义物品（命中则用隐形载体 + ItemDisplay 显示物品）。
 */
public final class CraftEngineHook {

    private CraftEngineHook() {
    }

    public static boolean isAvailable() {
        return Bukkit.getPluginManager().getPlugin("CraftEngine") != null;
    }

    /**
     * @param id              CE 自定义内容 id，形如 namespace:path
     * @param fallbackCarrier 回退载体方块（CE 物品模式用作 FallingBlock 的物理载体）
     * @return 解析结果；id 未命中任何 CE 内容时返回 null（由调用方回退到原版方块）
     */
    public static BallAppearance resolve(String id, Material fallbackCarrier) {
        if (id == null || id.isEmpty()) return null;

        Key key = Key.of(id);

        // 1) CE 自定义方块：FallingBlock 直接渲染该方块
        CustomBlock blockDef = CraftEngineBlocks.byId(key);
        if (blockDef != null) {
            ImmutableBlockState state = blockDef.defaultState();
            BlockData bd = CraftEngineBlocks.getBukkitBlockData(state);
            if (bd != null) {
                return new BallAppearance(bd, null, true);
            }
        }

        // 2) CE 自定义物品：隐形载体 + ItemDisplay 显示物品
        CustomItem<ItemStack> itemDef = CraftEngineItems.byId(key);
        if (itemDef != null) {
            ItemStack item = itemDef.buildItemStack();
            if (item != null) {
                BlockData carrier = Bukkit.createBlockData(fallbackCarrier);
                return new BallAppearance(carrier, item, true);
            }
        }

        return null;
    }
}
