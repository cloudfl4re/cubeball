package com.github.squi2rel.cb.menu.builder;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
class MenuBuilderBase {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    protected String title;
    protected int row;
    protected String prefix = "", lorePrefix = "";
    protected boolean autoClose = true;

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public void setLorePrefix(String prefix) {
        this.lorePrefix = prefix;
    }

    public void setAutoClose(boolean autoClose) {
        this.autoClose = autoClose;
    }

    protected static <T> void setMenuItem(MenuItem<T> item, Inventory inventory, int i) {
        if (item == null) return;
        ItemStack stack = item.getItemStack();
        if (stack == null) return;
        Material type = stack.getType();
        if (type == null || type.isAir()) return;
        ItemMeta meta = Objects.requireNonNull(stack.getItemMeta());
        meta.displayName(component(item.getPrefix() + item.getName()));
        String desc = item.getDesc();
        if (desc != null) {
            meta.lore(Arrays.stream(desc.split("\n", -1))
                    .map(s -> component(item.getLorePrefix() + s))
                    .collect(Collectors.toList()));
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        if (item.isGlowing()) meta.setEnchantmentGlintOverride(true);
        stack.setItemMeta(meta);
        inventory.setItem(i, stack);
    }

    private static Component component(String input) {
        String value = input == null ? "" : input.replace('&', '\u00a7');
        return LEGACY.deserialize(value).decoration(TextDecoration.ITALIC, false);
    }
}
