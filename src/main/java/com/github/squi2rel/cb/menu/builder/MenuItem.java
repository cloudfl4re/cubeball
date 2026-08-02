package com.github.squi2rel.cb.menu.builder;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

@SuppressWarnings({"unused", "UnusedReturnValue"})
public final class MenuItem<T> {
    private ItemStack item;
    private String name, namePrefix;
    private String desc, descPrefix;
    private MenuHandler<T> leftClickAction, rightClickAction;
    private MenuHandler<T> leftShiftClickAction, rightShiftClickAction;
    private MenuHandler<T> dropAction, swapAction;
    private MenuHandler.HotbarMenuHandler<T> hotbarAction;
    private boolean glowing;

    public MenuItem(Material item, String name, String desc, String namePrefix, String descPrefix) {
        this(item == null ? null : new ItemStack(item), name, desc, namePrefix, descPrefix);
    }

    public MenuItem(ItemStack item, String name, String desc, String namePrefix, String descPrefix) {
        this.item = item == null ? null : item.clone();
        this.name = name;
        this.desc = desc;
        this.namePrefix = namePrefix;
        this.descPrefix = descPrefix;
    }

    public Material getItem() {
        return item == null ? null : item.getType();
    }

    public ItemStack getItemStack() {
        return item == null ? null : item.clone();
    }

    public void setItem(Material item) {
        this.item = item == null ? null : new ItemStack(item);
    }

    public void setItem(ItemStack item) {
        this.item = item == null ? null : item.clone();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }

    public String getPrefix() {
        return namePrefix;
    }

    public void setPrefix(String namePrefix) {
        this.namePrefix = namePrefix;
    }

    public String getLorePrefix() {
        return descPrefix;
    }

    public void setLorePrefix(String descPrefix) {
        this.descPrefix = descPrefix;
    }

    public boolean isGlowing() {
        return glowing;
    }

    public MenuItem<T> setGlowing(boolean glowing) {
        this.glowing = glowing;
        return this;
    }

    public MenuItem<T> setAction(MenuHandler<T> action) {
        setLeftClickAction(action);
        setRightClickAction(action);
        setLeftShiftClickAction(action);
        setRightShiftClickAction(action);
        setDropAction(action);
        setSwapAction(action);
        setHotbarAction((p, a, i) -> action.handle(p, a));
        return this;
    }

    public MenuItem<T> setLeftClickAction(MenuHandler<T> leftClickAction) {
        this.leftClickAction = leftClickAction;
        return this;
    }

    public MenuItem<T> setRightClickAction(MenuHandler<T> rightClickAction) {
        this.rightClickAction = rightClickAction;
        return this;
    }

    public MenuItem<T> setLeftShiftClickAction(MenuHandler<T> leftShiftClickAction) {
        this.leftShiftClickAction = leftShiftClickAction;
        return this;
    }

    public MenuItem<T> setRightShiftClickAction(MenuHandler<T> rightShiftClickAction) {
        this.rightShiftClickAction = rightShiftClickAction;
        return this;
    }

    public MenuItem<T> setDropAction(MenuHandler<T> dropAction) {
        this.dropAction = dropAction;
        return this;
    }

    public MenuItem<T> setSwapAction(MenuHandler<T> swapAction) {
        this.swapAction = swapAction;
        return this;
    }

    public MenuItem<T> setHotbarAction(MenuHandler.HotbarMenuHandler<T> hotbarAction) {
        this.hotbarAction = hotbarAction;
        return this;
    }

    public boolean handleClick(MenuContext<T> context, Player player, int hotbar, ClickType clickType) {
        switch (clickType) {
            case LEFT:
            case DOUBLE_CLICK:
                if (leftClickAction == null) return true;
                leftClickAction.handle(player, context);
                return false;
            case SHIFT_LEFT:
                if (leftShiftClickAction == null) return true;
                leftShiftClickAction.handle(player, context);
                return false;
            case RIGHT:
                if (rightClickAction == null) return true;
                rightClickAction.handle(player, context);
                return false;
            case SHIFT_RIGHT:
                if (rightShiftClickAction == null) return true;
                rightShiftClickAction.handle(player, context);
                return false;
            case NUMBER_KEY:
                if (hotbarAction == null) return true;
                hotbarAction.handle(player, context, hotbar);
                return false;
            case DROP:
            case CONTROL_DROP:
                if (dropAction == null) return true;
                dropAction.handle(player, context);
                return false;
            case SWAP_OFFHAND:
                if (swapAction == null) return true;
                swapAction.handle(player, context);
                return false;
            default:
                return true;
        }
    }
}
