package me.crylonz;

import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

/**
 * 解析后的足球外观。carrierBlockData 是 FallingBlock 的物理载体方块；
 * displayItem 非 null 时需要额外生成 ItemDisplay 显示该物品（CE 物品模式）。
 * customMode 为 true 表示走 CE 路径（方块或物品模式），监听器据此用 BlockData 比较；
 * 否则是原版回退，监听器走原 Material 比较分支。
 * 仅持有 Bukkit 类型，可被任意类安全引用，不触发 CraftEngine 类加载。
 */
public final class BallAppearance {
    private final BlockData carrierBlockData;
    private final ItemStack displayItem;
    private final boolean customMode;

    public BallAppearance(BlockData carrierBlockData, ItemStack displayItem, boolean customMode) {
        this.carrierBlockData = carrierBlockData;
        this.displayItem = displayItem;
        this.customMode = customMode;
    }

    public BlockData getCarrierBlockData() {
        return carrierBlockData;
    }

    public ItemStack getDisplayItem() {
        return displayItem;
    }

    public boolean isItemDisplayMode() {
        return displayItem != null;
    }

    public boolean isCustomMode() {
        return customMode;
    }
}
