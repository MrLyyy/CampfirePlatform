package com.campfiremc.mc.service;

import com.campfiremc.mc.backend.BackendClient;
import com.campfiremc.mc.backend.protocol.MinecraftProtocol;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Function;

/** Coordinates backend traffic with session-scoped, explicit player authorization. */
public final class CampfireService implements AutoCloseable, BackendEventHandler {
    public enum BindSubmission { SUBMITTED, NOT_READY, ALREADY_PENDING, INVALID_CODE, UNCONFIGURED }

    private final PlayerSyncState state;
    private final BackendEventHandler presentation;
    private final BackendClient backend;

    /** Production path: the client is constructed with this service as its event handler. */
    public CampfireService(PlayerSyncState state, BackendEventHandler presentation,
                           Function<BackendEventHandler, BackendClient> factory) {
        this.state = Objects.requireNonNull(state);
        this.presentation = Objects.requireNonNull(presentation);
        this.backend = factory == null ? null : factory.apply(this);
    }

    /** Compatibility path for callers that already own a client (without its callbacks). */
    public CampfireService(BackendClient backend) {
        this.state = new PlayerSyncState();
        this.presentation = null;
        this.backend = backend;
        if (backend != null) state.onStateChanged(backend.status());
    }

    public boolean isConfigured() { return backend != null; }

    public BackendClient.Status status() {
        return backend == null
                ? new BackendClient.Status(BackendClient.State.DISCONNECTED, "")
                : backend.status();
    }

    public void connect() { if (backend != null) backend.reconnect(); }
    public void disconnect() { if (backend != null) backend.disconnect(); }

    /** Explicit player opt-in, never inferred from a chat message. */
    public void join(String uuid) { sendJoin(uuid, false); }

    /** Lifecycle entry, not the same as an explicit /join request. */
    public void playerJoined(String uuid) { sendJoin(uuid, true); }

    private void sendJoin(String uuid, boolean lifecycle) {
        refreshStatus();
        PlayerSyncState.Ticket ticket = state.join(uuid, lifecycle);
        sendTicket(ticket);
    }

    private void sendTicket(PlayerSyncState.Ticket ticket) {
        if (ticket == null || backend == null) return;
        if ("join".equals(ticket.action())) {
            backend.sendJoin(ticket.uuid(), () -> state.valid(ticket),
                    () -> sendTicket(state.discarded(ticket)));
        } else if ("leave".equals(ticket.action())) {
            backend.sendLeave(ticket.uuid(), () -> state.valid(ticket),
                    () -> sendTicket(state.discarded(ticket)));
        }
    }

    public void leave(String uuid) { sendLeave(uuid, false); }

    public void playerQuit(String uuid) { sendLeave(uuid, true); }

    private void sendLeave(String uuid, boolean lifecycle) {
        refreshStatus();
        PlayerSyncState.Ticket ticket = state.leave(uuid, lifecycle);
        sendTicket(ticket);
    }

    /** True only when an authorized message was submitted to the backend client. */
    public boolean chat(String uuid, String message) {
        refreshStatus();
        PlayerSyncState.Ticket ticket = state.message(uuid);
        if (ticket == null || backend == null) return false;
        backend.sendMessage(uuid, message, () -> state.valid(ticket));
        return true;
    }

    /** Six ASCII digits, from 100000 through 999999 (no whitespace or Unicode digits). */
    public static boolean isValidBindCode(String code) {
        if (code == null || code.length() != 6 || code.charAt(0) < '1' || code.charAt(0) > '9')
            return false;
        for (int i = 1; i < 6; i++) {
            if (code.charAt(i) < '0' || code.charAt(i) > '9') return false;
        }
        return true;
    }

    public BindSubmission bind(String uuid, String code) {
        if (!isValidBindCode(code)) return BindSubmission.INVALID_CODE;
        if (backend == null) return BindSubmission.UNCONFIGURED;
        refreshStatus();
        if (!state.readyFor(backend.status().sessionId())) return BindSubmission.NOT_READY;
        if (state.bindPending(uuid)) return BindSubmission.ALREADY_PENDING;
        PlayerSyncState.Ticket ticket = state.bind(uuid);
        if (ticket == null) return BindSubmission.NOT_READY;
        backend.sendBind(uuid, code, () -> state.valid(ticket),
                () -> sendTicket(state.discarded(ticket)));
        return BindSubmission.SUBMITTED;
    }

    /** Called on the platform thread after a READY event with an online-player snapshot. */
    public void rejoinOnlinePlayers(long sessionId, Collection<String> uuids) {
        Objects.requireNonNull(uuids);
        refreshStatus();
        if (backend == null || backend.status().state() != BackendClient.State.READY
                || backend.status().sessionId() != sessionId || !state.readyFor(sessionId)) return;
        for (String uuid : uuids) {
            if (!state.readyFor(sessionId) || backend.status().state() != BackendClient.State.READY
                    || backend.status().sessionId() != sessionId) return;
            sendTicket(state.rejoin(uuid, sessionId));
        }
    }

    private void refreshStatus() {
        if (backend != null) state.onStateChanged(backend.status());
    }

    @Override
    public void onChat(MinecraftProtocol.Chat chat) {
        if (presentation != null) presentation.onChat(chat);
    }

    @Override
    public void onCommandResult(MinecraftProtocol.CommandResult result) {
        PlayerSyncState.Resolution resolution = state.complete(result);
        if (resolution.valid() && "bind".equals(result.action()) && presentation != null)
            presentation.onCommandResult(result);
        sendTicket(resolution.retry());
    }

    @Override
    public void onStateChanged(BackendClient.Status status) {
        state.onStateChanged(status);
        if (presentation != null) presentation.onStateChanged(status);
    }

    @Override
    public void close() {
        state.close();
        if (backend != null) backend.close();
    }
}
