package com.campfiremc.mc.platform;

import com.campfiremc.mc.backend.BackendClient;
import com.campfiremc.mc.backend.protocol.MinecraftProtocol;
import com.campfiremc.mc.service.BackendEventHandler;
import com.campfiremc.mc.service.CampfireService;
import com.campfiremc.mc.service.PlayerSyncState;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

public final class BukkitBackendEventHandler implements BackendEventHandler, AutoCloseable {
    private record PendingBind(long loginToken) {}

    private final JavaPlugin plugin;
    private final PlayerSyncState state;
    private final Object lifecycleLock = new Object();
    private final Map<String, PendingBind> pendingBinds = new HashMap<>();
    private CampfireService service;
    private boolean closed;

    public BukkitBackendEventHandler(JavaPlugin plugin, PlayerSyncState state) {
        this.plugin = Objects.requireNonNull(plugin);
        this.state = Objects.requireNonNull(state);
    }

    /** Complete assembly before connecting; backend callbacks are delivered to the service first. */
    public void attachService(CampfireService service) {
        synchronized (lifecycleLock) {
            if (closed || this.service != null) throw new IllegalStateException("Backend handler already attached or closed");
            this.service = Objects.requireNonNull(service);
        }
    }

    /** Stage login identity before the backend can answer; do not retain the secret code. */
    public CampfireService.BindSubmission submitBind(Player player, CampfireService service, String code) {
        String uuid = player.getUniqueId().toString();
        long loginToken = state.snapshotLogin(uuid);
        if (loginToken == 0) return CampfireService.BindSubmission.NOT_READY;
        PendingBind candidate = new PendingBind(loginToken);
        boolean staged = false;
        synchronized (lifecycleLock) {
            if (closed) return CampfireService.BindSubmission.NOT_READY;
            PendingBind previous = pendingBinds.get(uuid);
            if (previous == null || !state.isCurrentLogin(uuid, previous.loginToken())) {
                pendingBinds.put(uuid, candidate);
                staged = true;
            }
        }
        CampfireService.BindSubmission submission = service.bind(uuid, code);
        if (submission != CampfireService.BindSubmission.SUBMITTED && staged) {
            synchronized (lifecycleLock) {
                pendingBinds.remove(uuid, candidate);
            }
        }
        return submission;
    }

    @Override
    public void onChat(MinecraftProtocol.Chat chat) {
        String message = "[" + chat.source() + "] " + chat.message();
        synchronized (lifecycleLock) {
            if (closed || !plugin.isEnabled()) return;
            schedule(() -> Bukkit.broadcast(Component.text(message)));
        }
    }

    @Override
    public void onCommandResult(MinecraftProtocol.CommandResult result) {
        synchronized (lifecycleLock) {
            if (closed || !plugin.isEnabled()) return;
            if (!"bind".equals(result.action())) return;
            PendingBind pending = pendingBinds.remove(result.uuid());
            if (pending == null) return;
            schedule(() -> {
                if (!state.isCurrentLogin(result.uuid(), pending.loginToken())) return;
                Player player;
                try {
                    player = Bukkit.getPlayer(UUID.fromString(result.uuid()));
                } catch (IllegalArgumentException invalidUuid) {
                    return;
                }
                if (player == null || !player.isOnline()) return;
                player.sendMessage(result.accepted()
                        ? ChatColor.GREEN + "Your Campfire account is now bound."
                        : ChatColor.RED + "Binding was not confirmed. Check your account status before requesting a new code.");
            });
        }
    }

    @Override
    public void onStateChanged(BackendClient.Status status) {
        synchronized (lifecycleLock) {
            if (closed || !plugin.isEnabled()) return;
            plugin.getLogger().info("Campfire backend state: " + status.state());
            if (status.state() != BackendClient.State.READY) {
                pendingBinds.clear();
                return;
            }
            CampfireService currentService = service;
            if (currentService == null) return;
            long sessionId = status.sessionId();
            schedule(() -> currentService.rejoinOnlinePlayers(sessionId, Bukkit.getOnlinePlayers().stream()
                    .map(player -> player.getUniqueId().toString()).toList()));
        }
    }

    /** All Bukkit access in callbacks is routed through the primary server thread. */
    private void schedule(Runnable task) {
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                synchronized (lifecycleLock) {
                    if (closed || !plugin.isEnabled()) return;
                    task.run();
                }
            });
        } catch (IllegalPluginAccessException exception) {
            synchronized (lifecycleLock) {
                if (!closed && plugin.isEnabled()) throw exception;
            }
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            closed = true;
            pendingBinds.clear();
            service = null;
        }
    }
}
