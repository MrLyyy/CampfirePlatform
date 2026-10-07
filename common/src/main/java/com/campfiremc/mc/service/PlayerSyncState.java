package com.campfiremc.mc.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Local authorization and request ownership; this class never performs I/O. */
public final class PlayerSyncState {
    public record Ticket(UUID uuid, String action, long sessionId, long playerVersion, long loginToken) {}
    public record Result(Ticket ticket, boolean current) {}
    public record BindRequest(BindOutcome outcome, Ticket ticket) {}
    public enum BindOutcome { SUBMITTED, NOT_READY, ALREADY_PENDING }

    private static final class Player {
        long version;
        long loginToken;
        long requestedSession = -1;
        long leftSession = -1;
        boolean online;
        boolean onlineKnown;
        boolean eligible;
        boolean wantsJoin;
        boolean leaveNeeded;
        final Map<String, Ticket> pending = new HashMap<>();
    }

    private final Map<UUID, Player> players = new HashMap<>();
    private long sessionId = -1;
    private long nextLoginToken;
    private boolean ready;
    private boolean closed;

    public synchronized void session(long newSessionId, boolean isReady) {
        if (closed) return;
        if (newSessionId != sessionId) {
            sessionId = newSessionId;
            for (Player player : players.values()) {
                player.version++;
                player.eligible = false;
                player.loginToken = 0;
                player.requestedSession = -1;
                player.leftSession = -1;
                player.pending.clear();
                player.leaveNeeded = false;
            }
        }
        ready = isReady;
        if (!ready) players.values().forEach(player -> player.eligible = false);
    }

    public synchronized List<Ticket> joined(UUID uuid) {
        if (closed) return List.of();
        Player player = player(uuid);
        if (!player.onlineKnown || !player.online) {
            player.version++;
            player.eligible = false;
            player.requestedSession = -1;
            player.leftSession = -1;
            player.loginToken = ++nextLoginToken;
        } else if (player.loginToken == 0) {
            player.loginToken = ++nextLoginToken;
        }
        player.onlineKnown = true;
        player.online = true;
        player.wantsJoin = true;
        return progress(uuid, player);
    }

    public synchronized List<Ticket> snapshot(UUID uuid, long expectedSession) {
        if (closed || !ready || sessionId != expectedSession) return List.of();
        Player player = player(uuid);
        if ((player.onlineKnown && !player.online) || player.leftSession == sessionId) return List.of();
        player.online = true;
        player.onlineKnown = true;
        if (player.loginToken == 0) player.loginToken = ++nextLoginToken;
        player.wantsJoin = true;
        return progress(uuid, player);
    }

    public synchronized List<Ticket> join(UUID uuid) {
        if (closed) return List.of();
        Player player = player(uuid);
        player.leftSession = -1;
        player.wantsJoin = true;
        if (!player.pending.containsKey("join") && !player.pending.containsKey("bind")) {
            player.version++;
            player.eligible = false;
            player.requestedSession = -1;
        }
        return progress(uuid, player);
    }

    public synchronized List<Ticket> leave(UUID uuid, boolean quit) {
        if (closed) return List.of();
        Player player = player(uuid);
        boolean notifyBackend = ready && player.eligible;
        player.version++;
        player.eligible = false;
        player.wantsJoin = false;
        player.requestedSession = -1;
        if (quit) {
            player.onlineKnown = true;
            player.online = false;
            player.loginToken = 0;
        } else if (ready) {
            player.leftSession = sessionId;
        }
        if (notifyBackend) player.leaveNeeded = true;
        return progress(uuid, player);
    }

    public synchronized BindRequest bind(UUID uuid) {
        if (closed || !ready) return new BindRequest(BindOutcome.NOT_READY, null);
        Player player = player(uuid);
        if (player.pending.containsKey("bind")) return new BindRequest(BindOutcome.ALREADY_PENDING, null);
        player.version++;
        player.eligible = false;
        player.wantsJoin = false;
        player.requestedSession = -1;
        Ticket ticket = ticket(uuid, "bind", player);
        return new BindRequest(BindOutcome.SUBMITTED, ticket);
    }

    /** Two-field frames can be proto3 rejections; only consume an exact pending request. */
    public synchronized Result rejectedIfPending(String uuidText, String action, long expectedSession) {
        if (!action.equals("join") && !action.equals("leave") && !action.equals("bind")) {
            return new Result(null, false);
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidText);
        } catch (IllegalArgumentException ignored) {
            return new Result(null, false);
        }
        if (!uuid.toString().equals(uuidText)) return new Result(null, false);
        return result(uuid, action, expectedSession, false);
    }

    public synchronized Result result(UUID uuid, String action, long expectedSession, boolean accepted) {
        if (closed || !ready || expectedSession != sessionId) return new Result(null, false);
        Player player = players.get(uuid);
        if (player == null) return new Result(null, false);
        Ticket ticket = player.pending.remove(action);
        if (ticket == null) return new Result(null, false);
        boolean current = ticket.sessionId() == sessionId && ticket.playerVersion() == player.version;
        if (current && action.equals("join")) {
            player.eligible = accepted;
            // Even a rejection counts as the lifecycle attempt in this session.
        } else if (current && action.equals("bind") && accepted) {
            // The backend adds a successfully bound player to this connection's cache.
            player.eligible = true;
            player.wantsJoin = true;
            player.requestedSession = sessionId;
            player.leftSession = -1;
        }
        if (!current && action.equals("join") && player.wantsJoin && !player.eligible) {
            player.requestedSession = -1;
        }
        return new Result(ticket, current);
    }

    public synchronized List<Ticket> afterResult(UUID uuid) {
        Player player = players.get(uuid);
        return player == null || closed ? List.of() : progress(uuid, player);
    }

    public synchronized List<Ticket> discarded(Ticket ticket) {
        if (closed || ticket.sessionId() != sessionId) return List.of();
        Player player = players.get(ticket.uuid());
        if (player == null || player.pending.get(ticket.action()) != ticket) return List.of();
        player.pending.remove(ticket.action());
        if (ticket.action().equals("join") && player.wantsJoin && !player.eligible) player.requestedSession = -1;
        return ready ? progress(ticket.uuid(), player) : List.of();
    }

    public synchronized boolean guard(Ticket ticket) {
        Player player = players.get(ticket.uuid());
        return !closed && ready && ticket.sessionId() == sessionId && player != null
                && ticket.playerVersion() == player.version && player.pending.get(ticket.action()) == ticket;
    }

    public synchronized boolean authorized(UUID uuid) {
        Player player = players.get(uuid);
        return !closed && ready && player != null && player.eligible;
    }

    public synchronized long authorizedVersion(UUID uuid, long expectedSession) {
        Player player = players.get(uuid);
        return !closed && ready && sessionId == expectedSession && player != null && player.eligible
                ? player.version : -1;
    }

    public synchronized boolean messageGuard(UUID uuid, long expectedSession, long version) {
        return version >= 0 && authorizedVersion(uuid, expectedSession) == version;
    }

    public synchronized boolean notificationGuard(Ticket ticket) {
        Player player = players.get(ticket.uuid());
        return !closed && ready && sessionId == ticket.sessionId() && player != null && player.online
                && ticket.loginToken() != 0 && ticket.loginToken() == player.loginToken
                && ticket.playerVersion() == player.version;
    }

    public synchronized boolean ready(long expectedSession) {
        return !closed && ready && sessionId == expectedSession;
    }

    public synchronized void close() {
        closed = true;
        ready = false;
        players.clear();
    }

    private Player player(UUID uuid) {
        return players.computeIfAbsent(uuid, ignored -> new Player());
    }

    private Ticket ticket(UUID uuid, String action, Player player) {
        Ticket ticket = new Ticket(uuid, action, sessionId, player.version, player.loginToken);
        player.pending.put(action, ticket);
        return ticket;
    }

    private List<Ticket> progress(UUID uuid, Player player) {
        if (!ready) return List.of();
        List<Ticket> work = new ArrayList<>(2);
        if (player.leaveNeeded && !player.pending.containsKey("leave")) {
            player.leaveNeeded = false;
            work.add(ticket(uuid, "leave", player));
        }
        if (player.wantsJoin && !player.eligible && player.requestedSession != sessionId
                && !player.pending.containsKey("join") && !player.pending.containsKey("bind")) {
            player.eligible = false;
            player.requestedSession = sessionId;
            work.add(ticket(uuid, "join", player));
        }
        return List.copyOf(work);
    }
}
