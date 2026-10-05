package com.campfiremc.mc.event;

import com.campfiremc.mc.service.CampfireService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class PlayerJoinListener implements Listener {
    private final CampfireService service;

    public PlayerJoinListener(CampfireService service) {
        this.service = service;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        service.playerJoined(event.getPlayer().getUniqueId().toString());
    }
}
