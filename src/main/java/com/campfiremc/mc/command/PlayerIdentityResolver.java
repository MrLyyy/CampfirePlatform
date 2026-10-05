package com.campfiremc.mc.command;

import com.campfiremc.mc.config.PluginSettings;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class PlayerIdentityResolver {
    private final Supplier<PluginSettings.Commands> commands;

    public PlayerIdentityResolver(Supplier<PluginSettings.Commands> commands) {
        this.commands = commands;
    }

    public String resolveUuid(CommandSender sender, String value) {
        if (value == null && sender instanceof Player player) return player.getUniqueId().toString();
        if (value == null) value = commands.get().consolePlayerUuid().trim();
        if (value.isEmpty()) return null;
        try { return UUID.fromString(value).toString(); }
        catch (IllegalArgumentException ignored) {
            Player player = Bukkit.getPlayerExact(value);
            return player == null ? null : player.getUniqueId().toString();
        }
    }
}
