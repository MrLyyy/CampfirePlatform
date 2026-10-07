package com.campfiremc.mc;

import com.campfiremc.mc.server.CampfireCommands;
import com.campfiremc.mc.server.CampfireRuntime;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(Constants.MOD_ID)
public final class CampfirePlatform {
    public CampfirePlatform(IEventBus modBus) {
        var bus = NeoForge.EVENT_BUS;
        bus.addListener((ServerStartedEvent event) ->
                CampfireRuntime.start(event.getServer(), FMLPaths.CONFIGDIR.get()));
        bus.addListener((ServerStoppingEvent event) -> CampfireRuntime.stop(event.getServer()));
        bus.addListener((RegisterCommandsEvent event) -> CampfireCommands.register(event.getDispatcher()));
        bus.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                CampfireRuntime.playerJoined(player);
            }
        });
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                CampfireRuntime.playerLeft(player);
            }
        });
        bus.addListener(EventPriority.LOWEST, false, ServerChatEvent.class,
                event -> CampfireRuntime.chat(event.getPlayer(), event.getMessage().getString()));
    }
}
