package com.campfiremc.mc.service;

import com.campfiremc.mc.backend.BackendConnection;
import com.campfiremc.mc.protocol.MinecraftProtocol;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Coordinates backend operations without running external callbacks under the state lock. */
public final class CampfireService implements AutoCloseable, BackendConnection.Listener {
    public enum BindOutcome { SUBMITTED, NOT_READY, ALREADY_PENDING, INVALID_CODE, UNCONFIGURED }

    public interface Presentation {
        void ready(long sessionId);
        void broadcast(long sessionId, String source, String message, BooleanSupplier guard);
        void bindResult(UUID uuid, boolean accepted, BooleanSupplier guard);
    }

    private final BackendConnection backend;
    private final PlayerSyncState players;
    private final Presentation presentation;
    private volatile boolean closed;

    public CampfireService(BackendConnection backend, Presentation presentation) {
        this.backend = backend;
        this.presentation = presentation;
        this.players = new PlayerSyncState();
        backend.setListener(this);
        BackendConnection.Status status = backend.status();
        players.session(status.sessionId(), status.state() == BackendConnection.State.READY);
    }

    public BackendConnection.Status status() {
        return backend.status();
    }

    public void start() {
        if (!closed) backend.connect();
    }

    public void connect() {
        if (closed) return;
        players.session(backend.status().sessionId(), false);
        backend.reconnect();
    }

    public void disconnect() {
        if (closed) return;
        players.session(backend.status().sessionId(), false);
        backend.disconnect();
    }

    public void joined(UUID uuid) {
        submit(players.joined(uuid));
    }

    public void quit(UUID uuid) {
        submit(players.leave(uuid, true));
    }

    public void join(UUID uuid) {
        submit(players.join(uuid));
    }

    public void leave(UUID uuid) {
        submit(players.leave(uuid, false));
    }

    public BindOutcome bind(UUID uuid, String code) {
        if (code == null || !code.matches("[1-9][0-9]{5}")) return BindOutcome.INVALID_CODE;
        if (closed) return BindOutcome.NOT_READY;
        PlayerSyncState.BindRequest request = players.bind(uuid);
        if (request.outcome() != PlayerSyncState.BindOutcome.SUBMITTED) {
            return BindOutcome.valueOf(request.outcome().name());
        }
        PlayerSyncState.Ticket ticket = request.ticket();
        backend.send(ticket.sessionId(), MinecraftProtocol.command(uuid.toString(), "bind", code),
                () -> players.guard(ticket), () -> discarded(ticket));
        return BindOutcome.SUBMITTED;
    }

    public boolean send(UUID uuid, String message) {
        if (closed) return false;
        long sessionId = backend.status().sessionId();
        long version = players.authorizedVersion(uuid, sessionId);
        if (version < 0) return false;
        backend.send(sessionId, MinecraftProtocol.message(uuid.toString(), message),
                () -> players.messageGuard(uuid, sessionId, version), () -> {});
        return true;
    }

    public boolean authorized(UUID uuid) {
        return players.authorized(uuid);
    }

    public void rejoinOnlinePlayers(long sessionId, Collection<UUID> online) {
        if (!players.ready(sessionId)) return;
        for (UUID uuid : online) {
            if (!players.ready(sessionId)) return;
            submit(players.snapshot(uuid, sessionId));
        }
    }

    @Override
    public void onStatus(BackendConnection.Status status) {
        if (closed) return;
        boolean ready = status.state() == BackendConnection.State.READY;
        players.session(status.sessionId(), ready);
        if (ready) presentation.ready(status.sessionId());
    }

    @Override
    public void onMessage(long sessionId, MinecraftProtocol.ServerMessage message) {
        if (closed || !players.ready(sessionId)) return;
        if (message instanceof MinecraftProtocol.Chat chat) {
            PlayerSyncState.Result rejection = players.rejectedIfPending(chat.source(), chat.message(), sessionId);
            if (rejection.ticket() != null) {
                handleResult(rejection, false);
            } else {
                presentation.broadcast(sessionId, chat.source(), chat.message(), () -> players.ready(sessionId));
            }
        } else if (message instanceof MinecraftProtocol.CommandResult command) {
            UUID uuid;
            try {
                uuid = UUID.fromString(command.uuid());
            } catch (IllegalArgumentException ignored) {
                return;
            }
            if (!uuid.toString().equals(command.uuid())) return;
            handleResult(players.result(uuid, command.action(), sessionId, command.accepted()), command.accepted());
        }
    }

    private void handleResult(PlayerSyncState.Result result, boolean accepted) {
        PlayerSyncState.Ticket ticket = result.ticket();
        if (ticket == null) return;
        try {
            if (result.current() && ticket.action().equals("bind")) {
                presentation.bindResult(ticket.uuid(), accepted, () -> players.notificationGuard(ticket));
            }
        } finally {
            submit(players.afterResult(ticket.uuid()));
        }
    }

    private void discarded(PlayerSyncState.Ticket ticket) {
        BackendConnection.Status status = backend.status();
        if (status.state() != BackendConnection.State.READY || status.sessionId() != ticket.sessionId()) {
            players.session(status.sessionId(), false);
        }
        submit(players.discarded(ticket));
    }

    private void submit(List<PlayerSyncState.Ticket> tickets) {
        if (closed) return;
        for (PlayerSyncState.Ticket ticket : tickets) {
            backend.send(ticket.sessionId(), MinecraftProtocol.command(ticket.uuid().toString(), ticket.action(),
                            ticket.uuid().toString()), () -> players.guard(ticket),
                    () -> discarded(ticket));
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        players.close();
        backend.close();
    }
}
