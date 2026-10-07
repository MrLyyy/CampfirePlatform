package com.campfiremc.mc.server;

import com.campfiremc.mc.Constants;
import com.campfiremc.mc.backend.BackendClient;
import com.campfiremc.mc.backend.Ed25519Credentials;
import com.campfiremc.mc.config.CampfireConfig;
import com.campfiremc.mc.config.ConfigStore;
import com.campfiremc.mc.service.CampfireService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** A dedicated-server-scoped bridge; queued presentation work expires with this runtime. */
public final class CampfireRuntime implements AutoCloseable, CampfireService.Presentation {
    private static final ConcurrentHashMap<MinecraftServer, CampfireRuntime> SERVERS = new ConcurrentHashMap<>();

    private final MinecraftServer server;
    private CampfireConfig config = CampfireConfig.defaults();
    private CampfireService service;
    private volatile boolean closed;

    private CampfireRuntime(MinecraftServer server) {
        this.server = server;
    }

    public static void start(MinecraftServer server, Path configDirectory) {
        if (!server.isDedicatedServer()) return;
        CampfireRuntime runtime = new CampfireRuntime(server);
        CampfireRuntime previous = SERVERS.put(server, runtime);
        if (previous != null) previous.close();
        runtime.initialize(configDirectory);
    }

    private void initialize(Path directory) {
        try {
            ConfigStore.Loaded loaded = ConfigStore.load(directory);
            config = loaded.config();
            if (!config.enabled()) {
                Constants.LOG.info("CampfirePlatform backend disabled; configure config/campfireplatform/config.json to enable it.");
                return;
            }
            BackendClient backend = new BackendClient(URI.create(config.baseUrl()), config.accountUuid(),
                    new Ed25519Credentials(loaded.seed()), Duration.ofSeconds(config.connectTimeoutSeconds()),
                    Duration.ofSeconds(config.requestTimeoutSeconds()), Duration.ofSeconds(config.handshakeTimeoutSeconds()),
                    config.maxMessageBytes(), config.initialReconnectSeconds(), config.maxReconnectSeconds());
            service = new CampfireService(backend, this);
            server.getPlayerList().getPlayers().forEach(player -> service.joined(player.getUUID()));
            service.start();
        } catch (Exception exception) {
            // Config can contain credentials; never echo its values or an exception's request URI.
            Constants.LOG.error("CampfirePlatform initialization failed ({}); check configuration and private-key-base64 (32-byte Ed25519 seed). Local gameplay remains available.",
                    exception.getClass().getSimpleName());
            if (service != null) service.close();
            service = null;
        }
    }

    public static void stop(MinecraftServer server) {
        CampfireRuntime runtime = SERVERS.remove(server);
        if (runtime != null) runtime.close();
    }

    public static CampfireRuntime get(MinecraftServer server) {
        return SERVERS.get(server);
    }

    public static void playerJoined(ServerPlayer player) {
        CampfireRuntime runtime = get(player.getServer());
        if (runtime != null && runtime.service != null && !runtime.closed) runtime.service.joined(player.getUUID());
    }

    public static void playerLeft(ServerPlayer player) {
        CampfireRuntime runtime = get(player.getServer());
        if (runtime != null && runtime.service != null && !runtime.closed) runtime.service.quit(player.getUUID());
    }

    public static void chat(ServerPlayer player, String message) {
        CampfireRuntime runtime = get(player.getServer());
        if (runtime != null && runtime.service != null && !runtime.closed) runtime.service.send(player.getUUID(), message);
    }

    public CampfireService service() {
        return closed ? null : service;
    }

    public UUID consoleUuid() {
        return config.consoleUuid().isBlank() ? null : UUID.fromString(config.consoleUuid());
    }

    @Override
    public void ready(long sessionId) {
        present(() -> {
            CampfireService current = service();
            if (current != null) current.rejoinOnlinePlayers(sessionId,
                    server.getPlayerList().getPlayers().stream().map(ServerPlayer::getUUID).toList());
        }, () -> true);
    }

    @Override
    public void broadcast(long sessionId, String source, String message, BooleanSupplier guard) {
        present(() -> server.getPlayerList().broadcastSystemMessage(
                Component.literal("[" + source + "] " + message).withStyle(ChatFormatting.WHITE), false), guard);
    }

    @Override
    public void bindResult(UUID uuid, boolean accepted, BooleanSupplier guard) {
        present(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) player.sendSystemMessage(Component.literal(accepted
                    ? "绑定成功，已启用聊天同步。" : "绑定失败，请检查验证码。"));
        }, guard);
    }

    private void present(Runnable action, BooleanSupplier guard) {
        if (closed) return;
        server.execute(() -> {
            if (!closed && SERVERS.get(server) == this && guard.getAsBoolean()) action.run();
        });
    }

    @Override
    public void close() {
        closed = true;
        if (service != null) service.close();
    }
}
