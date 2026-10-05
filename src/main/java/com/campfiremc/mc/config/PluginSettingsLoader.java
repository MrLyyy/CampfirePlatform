package com.campfiremc.mc.config;

import java.time.Duration;
import org.bukkit.configuration.file.FileConfiguration;

public final class PluginSettingsLoader {
    private PluginSettingsLoader() {}

    public static PluginSettings load(FileConfiguration config) {
        return new PluginSettings(
                new PluginSettings.Backend(
                        config.getString("backend.base-url", "http://127.0.0.1:3000"),
                        config.getString("backend.account-uuid", ""),
                        config.getBoolean("backend.connect-on-start", true),
                        Duration.ofSeconds(Math.max(1, config.getLong("backend.request-timeout-seconds", 10))),
                        config.getInt("backend.max-frame-bytes", 1048576),
                        config.getInt("backend.reconnect-delay-seconds", 5),
                        config.getInt("backend.max-reconnect-delay-seconds", 60)),
                commands(config));
    }

    public static PluginSettings.Commands commands(FileConfiguration config) {
        return new PluginSettings.Commands(
                config.getString("commands.permission", "campfire.admin"),
                config.getString("commands.console-player-uuid", ""));
    }
}
