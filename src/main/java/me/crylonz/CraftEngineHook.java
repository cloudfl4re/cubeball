package me.crylonz;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Isolates CraftEngine API calls. Reflection is used here because CraftEngine
 * has changed public method return types between releases.
 */
public final class CraftEngineHook {
    private static final String CE_PLUGIN = "CraftEngine";
    private static final String KEY_CLASS = "net.momirealms.craftengine.core.util.Key";
    private static final String BLOCKS_CLASS = "net.momirealms.craftengine.bukkit.api.CraftEngineBlocks";
    private static final String ITEMS_CLASS = "net.momirealms.craftengine.bukkit.api.CraftEngineItems";
    private static final String ITEM_MANAGER_CLASS = "net.momirealms.craftengine.bukkit.item.BukkitItemManager";
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private CraftEngineHook() {
    }

    public static boolean isAvailable() {
        return Bukkit.getPluginManager().getPlugin(CE_PLUGIN) != null;
    }

    public static BallAppearance resolve(String id, Material fallbackCarrier) {
        Object key = parseKey(id);
        if (key == null) return null;

        BlockData blockData = resolveBlockData(key);
        if (blockData != null) {
            return new BallAppearance(blockData, null, true);
        }

        ItemStack item = buildCustomItem(key);
        if (item != null) {
            item.setAmount(1);
            BlockData carrier = Bukkit.createBlockData(validCarrier(fallbackCarrier));
            return new BallAppearance(carrier, item, true);
        }

        warnOnce("resolve:" + id, "CraftEngine custom content could not be resolved: " + id);
        return null;
    }

    public static boolean hasCustomContent(String id) {
        Object key = parseKey(id);
        if (key == null) return false;
        return resolveBlockData(key) != null || buildCustomItem(key) != null;
    }

    public static String getCustomItemId(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;

        Object key = invokeStatic(ITEMS_CLASS, "getCustomItemId", item);
        key = unwrapOptional(key);
        String id = keyToString(key);
        if (id != null) return id;

        Object itemDef = unwrapOptional(invokeStatic(ITEMS_CLASS, "byItemStack", item));
        id = customItemIdToString(itemDef);
        if (id != null) return id;

        Object manager = itemManager();
        key = manager == null ? null : unwrapOptional(invoke(manager, "customItemId", item));
        id = keyToString(key);
        if (id != null) return id;

        Object wrapped = manager == null ? null : unwrapOptional(invoke(manager, "wrap", item));
        key = wrapped == null ? null : unwrapOptional(invoke(wrapped, "customId"));
        id = keyToString(key);
        if (id != null) return id;

        Object customItem = wrapped == null ? null : unwrapOptional(invoke(wrapped, "getCustomItem"));
        return customItemIdToString(customItem);
    }

    public static ItemStack buildCustomItemIcon(String id) {
        Object key = parseKey(id);
        if (key == null) return null;
        ItemStack item = buildCustomItem(key);
        if (item != null) item.setAmount(1);
        return item;
    }

    private static BlockData resolveBlockData(Object key) {
        Object blockDef = invokeStatic(BLOCKS_CLASS, "byId", key);
        blockDef = unwrapOptional(blockDef);
        if (blockDef == null) return null;

        Object state = invoke(blockDef, "defaultState");
        if (state == null) return null;

        Object blockData = invokeStatic(BLOCKS_CLASS, "getBukkitBlockData", state);
        return blockData instanceof BlockData ? (BlockData) blockData : null;
    }

    private static ItemStack buildCustomItem(Object key) {
        CubeBall.debug("CraftEngine buildCustomItem key=" + key);
        Object itemDef = invokeStatic(ITEMS_CLASS, "byId", key);
        itemDef = unwrapOptional(itemDef);
        CubeBall.debug("CraftEngine byId result=" + describeObject(itemDef));
        debugMethodsOnce(itemDef);
        ItemStack stack = buildItemStack(itemDef);
        if (stack != null) return stack;

        Object manager = itemManager();
        CubeBall.debug("CraftEngine itemManager=" + describeObject(manager));
        debugMethodsOnce(manager);
        stack = asItemStack(manager == null ? null : unwrapOptional(invoke(manager, "buildCustomItemStack", key, null)));
        if (stack != null) return stack;

        stack = asItemStack(manager == null ? null : unwrapOptional(invoke(manager, "buildItemStack", key, null)));
        if (stack != null) return stack;

        Object wrapped = manager == null ? null : unwrapOptional(invoke(manager, "createCustomWrappedItem", key, null));
        stack = asItemStack(wrapped == null ? null : unwrapOptional(invoke(wrapped, "getItem")));
        if (stack != null) return stack;

        wrapped = manager == null ? null : unwrapOptional(invoke(manager, "createWrappedItem", key, null));
        stack = asItemStack(wrapped == null ? null : unwrapOptional(invoke(wrapped, "getItem")));
        if (stack != null) return stack;

        return buildItemStackByDiscovery(itemDef, key);
    }

    private static ItemStack buildItemStack(Object itemDef) {
        if (itemDef == null) return null;

        ItemStack stack = asItemStack(invoke(itemDef, "buildItemStack"));
        if (stack != null) return stack;

        stack = asItemStack(invoke(itemDef, "buildItemStack", 1));
        if (stack != null) return stack;

        Object wrapped = unwrapOptional(invoke(itemDef, "buildItem"));
        stack = asItemStack(wrapped == null ? null : unwrapOptional(invoke(wrapped, "getItem")));
        if (stack != null) return stack;

        return buildItemStackByDiscovery(itemDef, null);
    }

    private static ItemStack asItemStack(Object value) {
        value = unwrapOptional(value);
        return value instanceof ItemStack ? (ItemStack) value : null;
    }

    private static ItemStack buildItemStackByDiscovery(Object target, Object key) {
        if (target == null) return null;
        for (Method method : target.getClass().getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) continue;
            if (!looksLikeItemBuilder(method)) continue;

            ItemStack stack = tryBuildViaMethod(target, method);
            if (stack != null) return stack;

            stack = tryBuildViaMethod(target, method, 1);
            if (stack != null) return stack;

            stack = tryBuildViaMethod(target, method, (Object) null);
            if (stack != null) return stack;

            if (key != null) {
                stack = tryBuildViaMethod(target, method, key);
                if (stack != null) return stack;

                stack = tryBuildViaMethod(target, method, key, null);
                if (stack != null) return stack;

                stack = tryBuildViaMethod(target, method, key, 1);
                if (stack != null) return stack;
            }

            stack = tryBuildViaMethod(target, method, null, 1);
            if (stack != null) return stack;
        }
        return null;
    }

    private static boolean looksLikeItemBuilder(Method method) {
        if (method.getReturnType() == Void.TYPE) return false;
        String name = method.getName().toLowerCase();
        return name.contains("itemstack")
                || name.equals("item")
                || name.equals("getitem")
                || name.contains("build")
                || name.contains("create");
    }

    private static ItemStack tryBuildViaMethod(Object target, Method method, Object... args) {
        if (!parametersMatch(method.getParameterTypes(), args)) return null;
        try {
            method.setAccessible(true);
            Object result = unwrapOptional(method.invoke(target, args));
            ItemStack stack = asItemStack(result);
            if (stack != null) {
                CubeBall.debug("CraftEngine built ItemStack via " + target.getClass().getName()
                        + "#" + method.getName() + "(" + args.length + ")");
                return stack;
            }
            stack = asItemStack(result == null ? null : unwrapOptional(invoke(result, "getItem")));
            if (stack != null) {
                CubeBall.debug("CraftEngine built wrapped ItemStack via " + target.getClass().getName()
                        + "#" + method.getName() + "(" + args.length + ") -> getItem");
            }
            return stack;
        } catch (IllegalAccessException | InvocationTargetException | LinkageError ignored) {
            return null;
        }
    }

    private static Object parseKey(String id) {
        if (id == null || id.trim().isEmpty()) return null;
        String trimmed = id.trim();
        Object key = invokeStatic(KEY_CLASS, "of", trimmed);
        if (key != null) return key;
        key = invokeStatic(KEY_CLASS, "from", trimmed);
        if (key != null) return key;
        key = invokeStatic(KEY_CLASS, "withDefaultNamespace", trimmed);
        if (key != null) return key;
        String[] parts = trimmed.split(":", 2);
        if (parts.length != 2) return null;
        key = invokeStatic(KEY_CLASS, "of", parts[0], parts[1]);
        if (key != null) return key;
        return invokeStatic(KEY_CLASS, "fromNamespaceAndPath", parts[0], parts[1]);
    }

    private static Object itemManager() {
        return invokeStatic(ITEM_MANAGER_CLASS, "instance");
    }

    private static Object invokeStatic(String className, String methodName, Object... args) {
        try {
            Class<?> type = Class.forName(className);
            Method method = findMethod(type, methodName, true, args);
            if (method == null) return null;
            method.setAccessible(true);
            return method.invoke(null, args);
        } catch (ClassNotFoundException | IllegalAccessException | LinkageError ignored) {
            return null;
        } catch (InvocationTargetException ignored) {
            return null;
        }
    }

    private static Object invoke(Object target, String methodName, Object... args) {
        if (target == null) return null;
        try {
            Method method = findMethod(target.getClass(), methodName, false, args);
            if (method == null) return null;
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (IllegalAccessException | LinkageError ignored) {
            return null;
        } catch (InvocationTargetException ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name, boolean requireStatic, Object... args) {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name)) continue;
            if (Modifier.isStatic(method.getModifiers()) != requireStatic) continue;
            if (!parametersMatch(method.getParameterTypes(), args)) continue;
            return method;
        }
        return null;
    }

    private static boolean parametersMatch(Class<?>[] actual, Object[] args) {
        if (actual.length != args.length) return false;
        for (int i = 0; i < actual.length; i++) {
            if (args[i] != null && !wrap(actual[i]).isAssignableFrom(args[i].getClass())) return false;
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static Object unwrapOptional(Object value) {
        return value instanceof Optional<?> ? ((Optional<?>) value).orElse(null) : value;
    }

    private static String keyToString(Object key) {
        if (key == null) return null;
        Object asString = invoke(key, "asString");
        return asString instanceof String ? (String) asString : key.toString();
    }

    private static String customItemIdToString(Object customItem) {
        if (customItem == null) return null;
        Object key = unwrapOptional(invoke(customItem, "id"));
        return keyToString(key);
    }

    private static String describeObject(Object object) {
        if (object == null) return "null";
        return object.getClass().getName();
    }

    private static void debugMethodsOnce(Object object) {
        if (!CubeBall.debugMode || object == null) return;
        String key = "methods:" + object.getClass().getName();
        if (!WARNED.add(key)) return;
        for (Method method : object.getClass().getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) continue;
            String lower = method.getName().toLowerCase();
            if (!looksLikeItemBuilder(method) && !lower.contains("id")) continue;
            CubeBall.debug("CraftEngine method " + object.getClass().getName()
                    + "#" + method.getName()
                    + Arrays.toString(method.getParameterTypes())
                    + " -> " + method.getReturnType().getName());
        }
    }

    private static void warnOnce(String key, String message) {
        if (CubeBall.plugin != null && WARNED.add(key)) {
            CubeBall.plugin.getLogger().warning(message);
        }
    }

    private static Material validCarrier(Material material) {
        if (material == null || !material.isBlock() || material.isAir()) return Material.IRON_BLOCK;
        return material;
    }
}
