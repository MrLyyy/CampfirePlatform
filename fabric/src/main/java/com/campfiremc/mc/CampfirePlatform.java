package com.campfiremc.mc;

import com.campfiremc.mc.server.CampfireCommands;
import com.campfiremc.mc.server.CampfireRuntime;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

public final class CampfirePlatform implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                CampfireRuntime.start(server, FabricLoader.getInstance().getConfigDir()));
        ServerLifecycleEvents.SERVER_STOPPING.register(CampfireRuntime::stop);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                CampfireCommands.register(dispatcher));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                CampfireRuntime.playerJoined(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                CampfireRuntime.playerLeft(handler.getPlayer()));
        // Observe accepted chat; do not register COMMAND_MESSAGE as well.
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
                CampfireRuntime.chat(sender, message.decoratedContent().getString()));
    }
}
