package com.campfiremc.mc.command;

import com.campfiremc.mc.platform.BukkitBackendEventHandler;
import com.campfiremc.mc.service.CampfireService;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Player-only, single-attempt account binding. Codes must never enter chat or logs. */
public final class BindCommand implements CommandExecutor {
    private final CampfireService service;
    private final BukkitBackendEventHandler presentation;

    public BindCommand(CampfireService service, BukkitBackendEventHandler presentation) {
        this.service = service;
        this.presentation = presentation;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use /bind.");
            return true;
        }
        if (!player.hasPermission("campfire.bind")) {
            player.sendMessage(ChatColor.RED + "You do not have permission to bind.");
            return true;
        }
        if (args.length != 1 || !CampfireService.isValidBindCode(args[0])) {
            player.sendMessage(ChatColor.RED + "Usage: /bind <six-digit code (100000-999999)>");
            return true;
        }

        CampfireService.BindSubmission submission = presentation.submitBind(player, service, args[0]);
        switch (submission) {
            case SUBMITTED -> player.sendMessage(ChatColor.YELLOW + "Binding request submitted. Waiting for backend confirmation.");
            case ALREADY_PENDING -> player.sendMessage(ChatColor.YELLOW + "A binding request is already pending.");
            case NOT_READY -> player.sendMessage(ChatColor.RED + "Binding is temporarily unavailable. Please try again shortly.");
            case UNCONFIGURED -> player.sendMessage(ChatColor.RED + "Binding is not configured on this server.");
            case INVALID_CODE -> player.sendMessage(ChatColor.RED + "Usage: /bind <six-digit code (100000-999999)>");
        }
        return true;
    }
}
