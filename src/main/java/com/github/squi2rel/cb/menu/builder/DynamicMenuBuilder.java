package com.github.squi2rel.cb.menu.builder;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

@SuppressWarnings("unused")
public class DynamicMenuBuilder<T> extends MenuBuilderBase {
    private DynamicMenuBuilderHandler<T> builder;
    private final ThreadLocal<DynamicMenuContext<T>> context = new ThreadLocal<>();

    public DynamicMenuBuilder(String title, int row, DynamicMenuBuilderHandler<T> builder) {
        this.title = title;
        this.row = row;
        this.builder = builder;
    }

    public MenuItem<T> setSlot(int column, int row, Material item, String name, String desc) {
        MenuItem<T> element = new MenuItem<>(item, name, desc, prefix, lorePrefix);
        currentContext().items.set(row * 9 + column, element);
        return element;
    }

    public MenuItem<T> setSlot(int column, int row, ItemStack item, String name, String desc) {
        MenuItem<T> element = new MenuItem<>(item, name, desc, prefix, lorePrefix);
        currentContext().items.set(row * 9 + column, element);
        return element;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }

    public void setRow(int row) {
        this.row = row;
        clear();
    }

    public int getRow() {
        return row;
    }

    public void setBuilder(DynamicMenuBuilderHandler<T> builder) {
        this.builder = builder;
    }

    public DynamicMenuBuilderHandler<T> getBuilder() {
        return builder;
    }

    public <R> R withContext(DynamicMenuContext<T> context, Supplier<R> runnable) {
        this.context.set(context);
        try {
            return runnable.get();
        } finally {
            this.context.remove();
        }
    }

    public void withContext(DynamicMenuContext<T> context, Runnable runnable) {
        this.context.set(context);
        try {
            runnable.run();
        } finally {
            this.context.remove();
        }
    }

    @Override
    public void setAutoClose(boolean autoClose) {
        currentContext().autoClose = autoClose;
    }

    @SuppressWarnings("unchecked")
    public void clear() {
        currentContext().items = Arrays.asList(new MenuItem[row * 9]);
    }

    public void refresh() {
        throw new Refresh();
    }

    public DynamicMenuContext<T> build() {
        return new DynamicMenuContext<>(this);
    }

    private DynamicMenuContext<T> currentContext() {
        return Objects.requireNonNull(context.get(), "Menu builder used outside its player context");
    }

    public static class DynamicMenuContext<T> extends MenuContext<T> {
        private final DynamicMenuBuilder<T> builder;
        private Inventory inventory;
        private List<MenuItem<T>> items;

        public DynamicMenuContext(DynamicMenuBuilder<T> builder) {
            this.builder = builder;
        }

        public void refresh(Player player, T argument) {
            builder.withContext(this, () -> {
                builder.clear();
                builder.builder.handle(builder, player, argument);
            });
            Inventory inventory = Bukkit.createInventory(null, builder.row * 9, builder.title);
            for (int i = 0; i < items.size(); i++) setMenuItem(items.get(i), inventory, i);
            this.inventory = inventory;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void handleClick(Player player, int slot, int hotbar, Object argument, ClickType clickType) {
            if (slot < 0 || slot >= items.size()) return;
            MenuItem<T> item = items.get(slot);
            if (item == null) return;
            setArgument((T) argument);
            boolean refresh = false;
            try {
                if (builder.withContext(this, () -> item.handleClick(this, player, hotbar, clickType))) return;
            } catch (Refresh e) {
                refresh = true;
            }
            if (this.argument != argument) MenuManager.updateArgument(player, this.argument);
            if (refresh) sendTo(player, this.argument);
        }

        @Override
        public void sendTo(Player player, T argument) {
            refresh(player, argument);
            player.openInventory(inventory);
            MenuManager.registerMenu(player, this, argument);
        }
    }

    public interface DynamicMenuBuilderHandler<T> {
        void handle(DynamicMenuBuilder<T> builder, Player player, T argument);
    }

    private static class Refresh extends RuntimeException {
        public Refresh() {
            super("Only use in menu context!");
        }
    }
}
