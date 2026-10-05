package com.campfiremc.mc.service;

import com.campfiremc.mc.backend.BackendClient;
import com.campfiremc.mc.backend.protocol.MinecraftProtocol;

public interface BackendEventHandler {
    void onChat(MinecraftProtocol.Chat chat);
    void onCommandResult(MinecraftProtocol.CommandResult result);
    void onStateChanged(BackendClient.Status status);
}
