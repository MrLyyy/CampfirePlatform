package com.campfiremc.mc.event;

import com.campfiremc.mc.service.CampfireService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerQuitListener implements Listener {
    private final CampfireService service;

    public PlayerQuitListener(CampfireService service) {
        this.service = service;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        service.playerQuit(event.getPlayer().getUniqueId().toString());
    }
}
