package com.campfiremc.mc.service;

import com.campfiremc.mc.backend.BackendClient;
import com.campfiremc.mc.backend.protocol.MinecraftProtocol;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Session-scoped player authorization and command slots. Never calls backend code while locked. */
public final class PlayerSyncState {
    private final Map<String, Player> players = new HashMap<>();
    private long sessionId = Long.MIN_VALUE;
    private long nextLoginToken;
    private boolean ready;
    private boolean closed;

    private static final class Player {
        long version;
        long loginToken;
        long requestedSession = Long.MIN_VALUE;
        long leftSession = Long.MIN_VALUE;
        boolean eligible;
        boolean online;
        boolean onlineKnown;
        boolean wantsJoin;
        boolean leaveNeeded;
        final Map<String, Ticket> pending = new HashMap<>();
    }

    record Ticket(String uuid, String action, long sessionId, long playerVersion) {}
    record Resolution(boolean valid, Ticket retry) {}

    public synchronized boolean isAuthorized(String uuid) {
        Player player = players.get(uuid);
        return ready && player != null && player.eligible;
    }

    public boolean isEligible(String uuid) { return isAuthorized(uuid); }

    /** A positive token identifies this online incarnation in this backend session. */
    public synchronized long snapshotLogin(String uuid) {
        Player player = players.get(uuid);
        return ready && player != null && player.online ? player.loginToken : 0;
    }

    public synchronized boolean isCurrentLogin(String uuid, long token) {
        Player player = players.get(uuid);
        return token != 0 && ready && player != null && player.online && player.loginToken == token;
    }

    /** A snapshot cannot revive a player who quit after it was taken. */
    synchronized Ticket rejoin(String uuid, long expectedSessionId) {
        if (!readyFor(expectedSessionId)) return null;
        Player player = players.get(uuid);
        if (player != null && player.onlineKnown && !player.online) return null;
        if (player != null && player.leftSession == sessionId) return null;
        return join(uuid, true);
    }

    synchronized void onStateChanged(BackendClient.Status status) {
        if (closed || status.sessionId() < sessionId) return;
        boolean nextReady = status.state() == BackendClient.State.READY;
        if (status.sessionId() == sessionId && ready && !nextReady) return;
        if (sessionId != status.sessionId() || (ready && !nextReady)) {
            for (Player player : players.values()) {
                player.version++;
                player.loginToken = 0;
                player.eligible = false;
                player.leaveNeeded = false;
                player.pending.clear();
            }
        }
        sessionId = status.sessionId();
        ready = nextReady;
    }

    synchronized void close() {
        closed = true;
        ready = false;
        for (Player player : players.values()) {
            player.version++;
            player.loginToken = 0;
            player.eligible = false;
            player.leaveNeeded = false;
            player.pending.clear();
        }
    }

    synchronized boolean readyFor(long expectedSessionId) {
        return ready && sessionId == expectedSessionId;
    }

    synchronized Ticket join(String uuid, boolean lifecycle) {
        Player player = player(uuid);
        if (lifecycle) {
            // A lifecycle join starts a new incarnation only after a known quit.
            if (player.onlineKnown && !player.online) {
                player.version++;
                player.leftSession = Long.MIN_VALUE;
                player.requestedSession = Long.MIN_VALUE;
                player.eligible = false;
            }
            if (!player.online || player.loginToken == 0) player.loginToken = ++nextLoginToken;
            player.online = true;
            player.onlineKnown = true;
        }
        player.wantsJoin = true;
        player.leaveNeeded = false;
        if (!lifecycle) player.leftSession = Long.MIN_VALUE;
        if (!ready || player.pending.containsKey("join") || player.pending.containsKey("bind")) return null;
        if (lifecycle && player.requestedSession == sessionId) return null;
        player.version++;
        player.eligible = false;
        player.requestedSession = sessionId;
        Ticket ticket = new Ticket(uuid, "join", sessionId, player.version);
        player.pending.put("join", ticket);
        return ticket;
    }

    synchronized Ticket leave(String uuid, boolean lifecycle) {
        Player player = player(uuid);
        boolean wasEligible = ready && player.eligible;
        player.version++;
        player.eligible = false;
        player.wantsJoin = false;
        player.requestedSession = Long.MIN_VALUE;
        if (!lifecycle && ready) player.leftSession = sessionId;
        if (lifecycle) {
            player.online = false;
            player.loginToken = 0;
            player.onlineKnown = true;
        }
        if (!wasEligible) return null;
        player.leaveNeeded = true;
        if (player.pending.containsKey("leave")) return null;
        Ticket ticket = new Ticket(uuid, "leave", sessionId, player.version);
        player.pending.put("leave", ticket);
        player.leaveNeeded = false;
        return ticket;
    }

    synchronized Ticket bind(String uuid) {
        Player player = player(uuid);
        if (!ready || player.pending.containsKey("bind")) return null;
        player.version++;
        player.eligible = false;
        player.wantsJoin = false;
        player.requestedSession = Long.MIN_VALUE;
        Ticket ticket = new Ticket(uuid, "bind", sessionId, player.version);
        player.pending.put("bind", ticket);
        return ticket;
    }

    synchronized boolean bindPending(String uuid) {
        Player player = players.get(uuid);
        return player != null && player.pending.containsKey("bind");
    }

    synchronized Ticket message(String uuid) {
        Player player = players.get(uuid);
        if (!ready || player == null || !player.eligible) return null;
        return new Ticket(uuid, "message", sessionId, player.version);
    }

    synchronized boolean valid(Ticket ticket) {
        Player player = players.get(ticket.uuid());
        if (!ready || sessionId != ticket.sessionId() || player == null
                || player.version != ticket.playerVersion()) return false;
        return switch (ticket.action()) {
            case "message" -> player.eligible;
            case "join" -> player.wantsJoin && player.pending.get("join") == ticket;
            case "bind", "leave" -> player.pending.get(ticket.action()) == ticket;
            default -> false;
        };
    }

    synchronized Resolution complete(MinecraftProtocol.CommandResult result) {
        Player player = players.get(result.uuid());
        if (player == null) return new Resolution(false, null);
        Ticket ticket = player.pending.remove(result.action());
        if (ticket == null) return new Resolution(false, null);
        boolean valid = ready && ticket.sessionId() == sessionId && ticket.playerVersion() == player.version
                && (!result.action().equals("join") || player.wantsJoin);
        if (valid && "join".equals(result.action())) {
            player.eligible = result.accepted();
        }
        if (valid && "bind".equals(result.action())) {
            player.eligible = false;
            player.wantsJoin = result.accepted();
        }
        // An abandoned join slot remains occupied until this result arrives. A returning
        // player can now make a new request; a successful bind requests join, never rebinds.
        Ticket retry = "leave".equals(result.action())
                ? retryLeave(result.uuid(), player)
                : (!valid || ("bind".equals(result.action()) && result.accepted()))
                        ? retryJoin(result.uuid(), player) : null;
        return new Resolution(valid, retry);
    }

    synchronized Ticket discarded(Ticket ticket) {
        Player player = players.get(ticket.uuid());
        if (player == null || player.pending.get(ticket.action()) != ticket) return null;
        player.pending.remove(ticket.action());
        if ("leave".equals(ticket.action())) return retryLeave(ticket.uuid(), player);
        if (!"join".equals(ticket.action()) && !"bind".equals(ticket.action())) return null;
        return retryJoin(ticket.uuid(), player);
    }

    private Ticket retryLeave(String uuid, Player player) {
        if (!ready || !player.leaveNeeded || player.wantsJoin || player.pending.containsKey("leave"))
            return null;
        player.leaveNeeded = false;
        Ticket retry = new Ticket(uuid, "leave", sessionId, player.version);
        player.pending.put("leave", retry);
        return retry;
    }

    private Ticket retryJoin(String uuid, Player player) {
        if (!ready || !player.wantsJoin || player.pending.containsKey("bind")
                || player.pending.containsKey("join") || player.eligible) return null;
        player.version++;
        player.requestedSession = sessionId;
        Ticket retry = new Ticket(uuid, "join", sessionId, player.version);
        player.pending.put("join", retry);
        return retry;
    }

    private Player player(String uuid) {
        return players.computeIfAbsent(Objects.requireNonNull(uuid), ignored -> new Player());
    }
}
