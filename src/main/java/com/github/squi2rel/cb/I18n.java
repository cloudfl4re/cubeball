package com.github.squi2rel.cb;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class I18n {
    private static volatile YamlConfiguration messages;

    public static void init(Plugin plugin, String language) {
        String resourceName = "messages." + language + ".yml";
        YamlConfiguration defaults = loadResource(plugin, resourceName);
        if (defaults == null) {
            resourceName = "messages.en.yml";
            defaults = loadResource(plugin, resourceName);
        }

        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        File file = new File(plugin.getDataFolder(), resourceName);
        if (!file.exists()) {
            try {
                InputStream in = plugin.getResource(resourceName);
                if (in != null) {
                    Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    in.close();
                }
            } catch (IOException ignored) {
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        messages = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        if (defaults != null) {
            messages.setDefaults(defaults);
            if (copyMissing(defaults, messages)) {
                try {
                    messages.save(file);
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static String get(String key) {
        return messages.getString(key, key);
    }

    public static String format(String key, Object... args) {
        String msg = get(key);
        for (int i = 0; i < args.length; i += 2) {
            msg = msg.replace("{" + args[i] + "}", String.valueOf(args[i + 1]));
        }
        return msg;
    }

    private static YamlConfiguration loadResource(Plugin plugin, String resourceName) {
        InputStream in = plugin.getResource(resourceName);
        if (in == null) return null;
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static boolean copyMissing(YamlConfiguration defaults, YamlConfiguration target) {
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || target.contains(key, true)) continue;
            target.set(key, defaults.get(key));
            changed = true;
        }
        return changed;
    }
}
