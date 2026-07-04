package com.github.squi2rel.cb.menu.builder;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public class MenuBuilder<T> extends MenuBuilderBase {
    private final List<MenuItem<T>> items;

    @SuppressWarnings("unchecked")
    public MenuBuilder(String title, int row, Consumer<MenuBuilder<T>> builder) {
        this.title = title;
        this.row = row;
        items = Arrays.asList(new MenuItem[row * 9]);
        builder.accept(this);
    }

    public StaticMenuContext<T> build() {
        Inventory inventory = Bukkit.createInventory(null, row * 9, title);
        for (int i = 0; i < items.size(); i++) setMenuItem(items.get(i), inventory, i);
        return new StaticMenuContext<>(inventory, items, autoClose);
    }

    public MenuItem<T> setSlot(int column, int row, Material item, String name, String desc) {
        MenuItem<T> element = new MenuItem<>(item, name, desc, prefix, lorePrefix);
        items.set(row * 9 + column, element);
        return element;
    }

    public MenuItem<T> setSlot(int column, int row, ItemStack item, String name, String desc) {
        MenuItem<T> element = new MenuItem<>(item, name, desc, prefix, lorePrefix);
        items.set(row * 9 + column, element);
        return element;
    }

    public static class StaticMenuContext<T> extends MenuContext<T> {
        private final Inventory inventory;
        private final List<MenuItem<T>> items;

        public StaticMenuContext(Inventory inventory, List<MenuItem<T>> items, boolean autoClose) {
            this.inventory = inventory;
            this.items = items;
            this.autoClose = autoClose;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void handleClick(Player player, int slot, int hotbar, Object argument, ClickType clickType) {
            if (slot < 0 || slot >= items.size()) return;
            MenuItem<T> item = items.get(slot);
            if (item == null) return;
            setArgument((T) argument);
            if (item.handleClick(this, player, hotbar, clickType)) return;
            if (this.argument != argument) MenuManager.updateArgument(player, this.argument);
        }

        public void sendTo(Player player, T argument) {
            player.openInventory(inventory);
            MenuManager.registerMenu(player, this, argument);
        }
    }
}
