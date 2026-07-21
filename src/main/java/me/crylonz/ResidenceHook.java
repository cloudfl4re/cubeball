package me.crylonz;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Locale;

public final class ResidenceHook {
    private static volatile boolean initialized;
    private static volatile boolean available;
    private static volatile Object residenceManager;
    private static volatile Method getByLocation;
    private static volatile Method getResidenceName;
    private static volatile String failure;

    private ResidenceHook() {
    }

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        available = false;
        failure = null;

        Plugin residence = Bukkit.getPluginManager().getPlugin("Residence");
        if (residence == null || !residence.isEnabled()) {
            failure = "Residence is not installed or enabled";
            return;
        }

        try {
            residenceManager = resolveResidenceManager(residence);
            getByLocation = residenceManager.getClass().getMethod("getByLoc", Location.class);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            failure = "Residence API could not be resolved";
            residenceManager = null;
            getByLocation = null;
            return;
        }
        available = true;
    }

    public static synchronized void retry() {
        initialized = false;
        available = false;
        residenceManager = null;
        getByLocation = null;
        getResidenceName = null;
        failure = null;
        init();
    }

    public static State getState(Location location, String configuredName) {
        if (configuredName == null || configuredName.isBlank() || location == null) return State.UNAVAILABLE;
        if (!initialized) init();
        if (!available || residenceManager == null || getByLocation == null) return State.UNAVAILABLE;

        try {
            Object residence = getByLocation.invoke(residenceManager, location);
            if (residence == null) return State.OUTSIDE;
            Method nameMethod = residenceNameMethod(residence.getClass());
            Object value = nameMethod.invoke(residence);
            if (value == null) return State.OUTSIDE;
            String actual = value.toString().trim().toLowerCase(Locale.ROOT);
            String expected = configuredName.trim().toLowerCase(Locale.ROOT);
            return actual.equals(expected) || actual.startsWith(expected + ".") ? State.INSIDE : State.OUTSIDE;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            failure = "Residence lookup failed";
            return State.UNAVAILABLE;
        }
    }

    private static Object resolveResidenceManager(Plugin residence) throws ReflectiveOperationException {
        ReflectiveOperationException failure = null;
        try {
            Class<?> api = Class.forName(
                    "com.bekvon.bukkit.residence.api.ResidenceApi",
                    true,
                    residence.getClass().getClassLoader()
            );
            Object manager = api.getMethod("getResidenceManager").invoke(null);
            if (manager != null) return manager;
        } catch (ReflectiveOperationException exception) {
            failure = exception;
        } catch (LinkageError ignored) {
        }

        for (String methodName : new String[]{"getResidenceManager", "getResidenceManagerAPI"}) {
            try {
                Object manager = residence.getClass().getMethod(methodName).invoke(residence);
                if (manager != null) return manager;
            } catch (ReflectiveOperationException exception) {
                failure = exception;
            }
        }

        if (failure != null) throw failure;
        throw new NoSuchMethodException("Residence manager is unavailable");
    }

    private static Method residenceNameMethod(Class<?> residenceClass) throws NoSuchMethodException {
        Method method = getResidenceName;
        if (method != null && method.getDeclaringClass().isAssignableFrom(residenceClass)) return method;
        try {
            method = residenceClass.getMethod("getTopParentName");
        } catch (NoSuchMethodException ignored) {
            method = residenceClass.getMethod("getName");
        }
        getResidenceName = method;
        return method;
    }

    /**
     * 检查坐标是否在任意领地内（不要求特定领地名称）。
     * 仅用于设置场地坐标时的前置检查，不涉及玩家权限。
     *
     * @return true=在某个领地内；false=不在任何领地内；领地插件不可用时返回 false（不阻止设置）
     */
    public static boolean isInAnyResidence(Location location) {
        if (location == null) return false;
        if (!initialized) init();
        if (!available || residenceManager == null || getByLocation == null) return false;
        try {
            Object residence = getByLocation.invoke(residenceManager, location);
            return residence != null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * 领地插件是否已成功加载（可用于前置检查：不可用时跳过领地限制）。
     */
    public static String getFailure() {
        return failure;
    }

    public static boolean isAvailable() {
        return available;
    }

    public enum State {
        INSIDE,
        OUTSIDE,
        UNAVAILABLE
    }
}
