package com.campfiremc.mc.command;

import com.campfiremc.mc.config.PluginSettings;
import com.campfiremc.mc.service.CampfireService;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public final class CampfireCommand implements CommandExecutor, TabCompleter {
    private final CampfireService service;
    private final Supplier<PluginSettings.Commands> commands;
    private final PlayerIdentityResolver identities;

    public CampfireCommand(CampfireService service, Supplier<PluginSettings.Commands> commands) {
        this.service = service;
        this.commands = commands;
        this.identities = new PlayerIdentityResolver(commands);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(commands.get().permission())) {
            sender.sendMessage(ChatColor.RED + "You do not have permission.");
            return true;
        }
        if (!service.isConfigured()) {
            sender.sendMessage(ChatColor.RED + "Backend client is not configured.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            var status = service.status();
            sender.sendMessage(ChatColor.YELLOW + "State: " + status.state() + ", last error: " + status.lastError());
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "connect" -> { service.connect(); sender.sendMessage(ChatColor.GREEN + "Connecting..."); }
            case "disconnect" -> { service.disconnect(); sender.sendMessage(ChatColor.YELLOW + "Disconnected."); }
            case "join", "leave" -> {
                String uuid = identities.resolveUuid(sender, args.length > 1 ? args[1] : null);
                if (uuid == null) { sender.sendMessage(ChatColor.RED + "Provide a player name or UUID."); return true; }
                if (args[0].equalsIgnoreCase("join")) service.join(uuid); else service.leave(uuid);
                sender.sendMessage(ChatColor.GREEN + args[0] + " sent for " + uuid);
            }
            case "send" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /campfire send <message>"); return true; }
                String uuid = identities.resolveUuid(sender, null);
                if (uuid == null) { sender.sendMessage(ChatColor.RED + "Use this command as a player or configure commands.console-player-uuid."); return true; }
                if (service.chat(uuid, String.join(" ", Arrays.copyOfRange(args, 1, args.length)))) {
                    sender.sendMessage(ChatColor.GREEN + "Message submitted.");
                } else {
                    sender.sendMessage(ChatColor.RED + "Message not sent: player has not been authorized by the backend.");
                }
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "/campfire <status|connect|disconnect|join|leave|send>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("status", "connect", "disconnect", "join", "leave", "send");
        return List.of();
    }
}
