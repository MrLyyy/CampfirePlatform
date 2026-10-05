package com.campfiremc.mc.event;

import com.campfiremc.mc.service.CampfireService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public final class PlayerChatListener implements Listener {
    private final CampfireService service;

    public PlayerChatListener(CampfireService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onPlayerChat(AsyncChatEvent event) {
        service.chat(event.getPlayer().getUniqueId().toString(),
                PlainTextComponentSerializer.plainText().serialize(event.message()));
    }
}
