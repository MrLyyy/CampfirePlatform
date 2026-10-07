package com.campfiremc.mc;

import com.campfiremc.mc.server.CampfireCommands;
import com.campfiremc.mc.server.CampfireRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod(Constants.MOD_ID)
public final class CampfirePlatform {
    public CampfirePlatform() {
        var bus = MinecraftForge.EVENT_BUS;
        bus.addListener((ServerStartedEvent event) ->
                CampfireRuntime.start(event.getServer(), FMLPaths.CONFIGDIR.get()));
        bus.addListener((ServerStoppingEvent event) -> CampfireRuntime.stop(event.getServer()));
        bus.addListener((RegisterCommandsEvent event) -> CampfireCommands.register(event.getDispatcher()));
        bus.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                CampfireRuntime.playerJoined(player);
            }
        });
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                CampfireRuntime.playerLeft(player);
            }
        });
        // ServerChatEvent is dispatched after the vanilla chat allow/cancellation stage.
        bus.addListener(EventPriority.LOWEST, false, ServerChatEvent.class,
                event -> CampfireRuntime.chat(event.getPlayer(), event.getMessage().getString()));
    }
}
