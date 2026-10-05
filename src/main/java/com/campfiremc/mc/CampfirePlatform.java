package com.campfiremc.mc;

import com.campfiremc.mc.backend.BackendClient;
import com.campfiremc.mc.command.BindCommand;
import com.campfiremc.mc.command.CampfireCommand;
import com.campfiremc.mc.config.PluginSettings;
import com.campfiremc.mc.config.PluginSettingsLoader;
import com.campfiremc.mc.config.PrivateKeyStore;
import com.campfiremc.mc.event.PlayerChatListener;
import com.campfiremc.mc.event.PlayerJoinListener;
import com.campfiremc.mc.event.PlayerQuitListener;
import com.campfiremc.mc.platform.BukkitBackendEventHandler;
import com.campfiremc.mc.service.CampfireService;
import com.campfiremc.mc.service.PlayerSyncState;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class CampfirePlatform extends JavaPlugin {
    private CampfireService service;
    private BukkitBackendEventHandler eventHandler;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        PluginSettings settings = null;
        boolean configured = false;
        PlayerSyncState syncState = new PlayerSyncState();
        eventHandler = new BukkitBackendEventHandler(this, syncState);
        try {
            settings = PluginSettingsLoader.load(getConfig());
            if (settings.backend().accountUuid().isEmpty()) {
                getLogger().warning("Set backend.account-uuid in config.yml before connecting.");
            } else {
                byte[] privateSeed = PrivateKeyStore.loadOrCreate(this);
                PluginSettings.Backend backendSettings = settings.backend();
                service = new CampfireService(syncState, eventHandler,
                        events -> new BackendClient(getLogger(), backendSettings, privateSeed, events));
                configured = true;
            }
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Could not initialize backend client", exception);
        }
        if (service == null) service = new CampfireService(syncState, eventHandler, null);
        eventHandler.attachService(service);

        getServer().getPluginManager().registerEvents(new PlayerJoinListener(service), this);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(service), this);
        getServer().getPluginManager().registerEvents(new PlayerChatListener(service), this);
        PluginCommand command = getCommand("campfire");
        if (command != null) {
            CampfireCommand executor = new CampfireCommand(service, () -> PluginSettingsLoader.commands(getConfig()));
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        PluginCommand bindCommand = getCommand("bind");
        if (bindCommand != null) bindCommand.setExecutor(new BindCommand(service, eventHandler));
        if (configured && settings.backend().connectOnStart()) service.connect();
    }

    @Override
    public void onDisable() {
        if (eventHandler != null) eventHandler.close();
        if (service != null) service.close();
    }
}
