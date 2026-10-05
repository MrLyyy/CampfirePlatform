package com.campfiremc.mc.backend;

import com.campfiremc.mc.backend.auth.BackendAuthenticator;
import com.campfiremc.mc.backend.auth.Ed25519Credentials;
import com.campfiremc.mc.backend.protocol.MinecraftProtocol;
import com.campfiremc.mc.backend.transport.BackendTransport;
import com.campfiremc.mc.backend.transport.WebSocketTransport;
import com.campfiremc.mc.config.PluginSettings;
import com.campfiremc.mc.service.BackendEventHandler;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BackendClient implements AutoCloseable {
    public enum State { DISCONNECTED, VERIFYING, CONNECTING, READY, STOPPED }
    public record Status(State state, String lastError, long sessionId) {
        public Status(State state, String lastError) { this(state, lastError, 0); }
    }

    private final Logger logger;
    private final Supplier<CompletableFuture<Integer>> authenticator;
    private final BackendTransport transport;
    private final ScheduledExecutorService executor;
    private final BackendEventHandler events;
    private final int initialReconnectSeconds;
    private final int maxReconnectSeconds;
    private final Object lifecycleLock = new Object();
    private volatile Status status = new Status(State.DISCONNECTED, "");
    private volatile boolean closed;

    // Mutable session state belongs exclusively to the executor.
    private boolean stopped;
    private long generation;
    private int reconnectSeconds;
    private BackendTransport.Connection connection;
    private CompletableFuture<Integer> verification;
    private ScheduledFuture<?> reconnectTask;
    private final ArrayDeque<OutgoingMessage> outgoing = new ArrayDeque<>();
    private boolean sending;

    public BackendClient(Logger logger, PluginSettings.Backend settings, byte[] seed,
                         BackendEventHandler events) {
        this(logger, production(settings, seed), settings, events);
    }

    private BackendClient(Logger logger, Resources resources, PluginSettings.Backend settings,
                          BackendEventHandler events) {
        this(logger, resources.authenticator()::verify, resources.transport(), newExecutor(),
                settings.initialReconnectSeconds(), settings.maxReconnectSeconds(), events);
    }

    public BackendClient(Logger logger, Supplier<CompletableFuture<Integer>> authenticator,
                         BackendTransport transport, ScheduledExecutorService executor,
                         int initialReconnectSeconds, int maxReconnectSeconds, BackendEventHandler events) {
        this.logger = logger;
        this.authenticator = authenticator;
        this.transport = transport;
        this.executor = executor;
        this.initialReconnectSeconds = Math.max(1, initialReconnectSeconds);
        this.maxReconnectSeconds = Math.max(this.initialReconnectSeconds, maxReconnectSeconds);
        this.reconnectSeconds = this.initialReconnectSeconds;
        this.events = events;
    }

    private record Resources(BackendAuthenticator authenticator, BackendTransport transport) {}
    private record OutgoingMessage(byte[] bytes, BooleanSupplier guard,
                                   boolean sensitive, Runnable discarded) {}

    private static Resources production(PluginSettings.Backend settings, byte[] seed) {
        Ed25519Credentials credentials = new Ed25519Credentials(seed);
        HttpClient http = HttpClient.newBuilder().connectTimeout(settings.timeout()).build();
        return new Resources(new BackendAuthenticator(http, settings.baseUrl(), settings.accountUuid(),
                credentials, settings.timeout(), Clock.systemUTC()),
                new WebSocketTransport(http, settings.baseUrl(), settings.accountUuid(),
                        settings.timeout(), settings.maxFrameBytes()));
    }

    private static ScheduledExecutorService newExecutor() {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "campfire-backend-client");
            thread.setDaemon(true);
            return thread;
        });
    }

    public Status status() { return status; }

    public void connect() {
        submit(() -> {
            if (stopped || status.state() == State.READY || status.state() == State.VERIFYING
                    || status.state() == State.CONNECTING) return;
            cancelReconnect();
            verifyAndConnect();
        });
    }

    public void reconnect() {
        submit(() -> {
            stopped = false;
            cancelReconnect();
            invalidate(false);
            transition(State.DISCONNECTED);
            verifyAndConnect();
        });
    }

    public void disconnect() {
        submit(() -> {
            stopped = true;
            cancelReconnect();
            invalidate(true);
            transition(State.STOPPED);
        });
    }

    public void sendJoin(String uuid) { sendJoin(uuid, () -> true); }

    public void sendJoin(String uuid, BooleanSupplier guard) { sendJoin(uuid, guard, () -> {}); }

    public void sendJoin(String uuid, BooleanSupplier guard, Runnable discarded) {
        send(MinecraftProtocol.command(uuid, "join", uuid), guard, false, discarded);
    }

    public void sendLeave(String uuid) { sendLeave(uuid, () -> true); }

    public void sendLeave(String uuid, BooleanSupplier guard) { sendLeave(uuid, guard, () -> {}); }

    public void sendLeave(String uuid, BooleanSupplier guard, Runnable discarded) {
        send(MinecraftProtocol.command(uuid, "leave", uuid), guard, false, discarded);
    }

    public void sendBind(String uuid, String code, BooleanSupplier guard) { sendBind(uuid, code, guard, () -> {}); }

    public void sendBind(String uuid, String code, BooleanSupplier guard, Runnable discarded) {
        send(MinecraftProtocol.command(uuid, "bind", code), guard, true, discarded);
    }

    public void sendMessage(String uuid, String message) { sendMessage(uuid, message, () -> true); }

    public void sendMessage(String uuid, String message, BooleanSupplier guard) {
        send(MinecraftProtocol.message(uuid, message), guard, false, () -> {});
    }

    private void send(byte[] bytes, BooleanSupplier guard, boolean sensitive, Runnable discarded) {
        long session = status.sessionId();
        if (!submit(() -> {
            if (!isCurrent(session) || !guard.getAsBoolean()) { discarded.run(); return; }
            if (status.state() != State.READY || connection == null) {
                logger.warning("Cannot send backend message while websocket is not ready");
                discarded.run();
                return;
            }
            outgoing.add(new OutgoingMessage(bytes, guard, sensitive, discarded));
            sendNext(generation);
        }, discarded)) discarded.run();
    }

    private void sendNext(long flow) {
        if (!isCurrent(flow) || sending || outgoing.isEmpty() || connection == null) return;
        OutgoingMessage message = outgoing.remove();
        while (!message.guard().getAsBoolean()) {
            message.discarded().run();
            if (outgoing.isEmpty()) return;
            message = outgoing.remove();
        }
        OutgoingMessage sendingMessage = message;
        sending = true;
        try {
            connection.send(message.bytes()).whenComplete((ignored, error) -> submit(() -> {
                if (!isCurrent(flow)) return;
                sending = false;
                if (error != null) fail(sendingMessage.sensitive()
                        ? "websocket bind send failed" : "websocket send failed: " + error.getMessage());
                else sendNext(flow);
            }));
        } catch (RuntimeException exception) {
            fail(message.sensitive() ? "websocket bind send failed" : "websocket send failed: " + exception.getMessage());
        }
    }

    private void verifyAndConnect() {
        if (closed || stopped) return;
        long flow = ++generation;
        transition(State.VERIFYING);
        try {
            verification = authenticator.get();
            verification.whenComplete((key, error) -> submit(() -> {
                if (!isCurrent(flow)) return;
                verification = null;
                if (error != null) {
                    fail("verification request failed: " + error.getMessage());
                } else if (key == null) {
                    fail("verification response did not contain data.key");
                } else {
                    openSocket(key, flow);
                }
            }));
        } catch (RuntimeException exception) {
            fail("verification failed: " + exception.getMessage());
        }
    }

    private void openSocket(int key, long flow) {
        if (!isCurrent(flow)) return;
        transition(State.CONNECTING);
        try {
            transport.open(key, new BackendTransport.Listener() {
                @Override
                public void onFrame(byte[] bytes) { submit(() -> handleFrame(bytes, flow)); }
                @Override
                public void onFailure(String message) {
                    submit(() -> { if (isCurrent(flow)) fail(message); });
                }
            }).whenComplete((opened, error) -> {
                if (!submit(() -> {
                    if (!isCurrent(flow)) {
                        if (opened != null) opened.abort();
                        return;
                    }
                    if (error != null) {
                        fail("websocket connection failed: " + error.getMessage());
                        return;
                    }
                    connection = opened;
                    reconnectSeconds = initialReconnectSeconds;
                    transition(State.READY);
                    try { opened.start(); }
                    catch (RuntimeException exception) { fail("websocket start failed: " + exception.getMessage()); }
                }, () -> { if (opened != null) opened.abort(); }) && opened != null) opened.abort();
            });
        } catch (RuntimeException exception) {
            fail("websocket connection failed: " + exception.getMessage());
        }
    }

    private void handleFrame(byte[] bytes, long flow) {
        if (!isCurrent(flow)) return;
        MinecraftProtocol.ServerFrame frame;
        try {
            frame = MinecraftProtocol.decodeServerFrame(bytes);
        } catch (RuntimeException exception) {
            fail("invalid backend protobuf frame: " + exception.getMessage());
            return;
        }
        notifyEvent(() -> {
            switch (frame) {
                case MinecraftProtocol.Chat chat -> events.onChat(chat);
                case MinecraftProtocol.CommandResult result -> events.onCommandResult(result);
            }
        });
    }

    private void fail(String message) {
        if (closed || stopped) return;
        status = new Status(status.state(), message, generation);
        logger.warning(message);
        invalidate(false);
        transition(State.DISCONNECTED);
        if (reconnectTask != null) return;
        int delay = reconnectSeconds;
        reconnectSeconds = (int) Math.min(maxReconnectSeconds, Math.max((long) delay + 1, (long) delay * 2));
        long flow = generation;
        reconnectTask = executor.schedule(() -> {
            reconnectTask = null;
            if (isCurrent(flow)) verifyAndConnect();
        }, delay, TimeUnit.SECONDS);
    }

    private boolean isCurrent(long flow) { return !closed && !stopped && generation == flow; }

    private void invalidate(boolean graceful) {
        generation++;
        CompletableFuture<Integer> pending = verification;
        verification = null;
        if (pending != null) pending.cancel(true);
        BackendTransport.Connection current = connection;
        connection = null;
        var discardedMessages = new ArrayDeque<>(outgoing);
        outgoing.clear();
        sending = false;
        discardedMessages.forEach(message -> message.discarded().run());
        if (current != null) {
            if (graceful) current.disconnect(); else current.abort();
        }
    }

    private void cancelReconnect() {
        ScheduledFuture<?> pending = reconnectTask;
        reconnectTask = null;
        if (pending != null) pending.cancel(false);
    }

    private void transition(State next) {
        if (closed) return;
        status = new Status(next, status.lastError(), generation);
        Status snapshot = status;
        notifyEvent(() -> events.onStateChanged(snapshot));
    }

    private void notifyEvent(Runnable event) {
        if (closed) return;
        try { event.run(); }
        catch (RuntimeException exception) { logger.log(Level.WARNING, "Backend event handler failed", exception); }
    }

    private boolean submit(Runnable action) {
        return submit(action, () -> {});
    }

    private boolean submit(Runnable action, Runnable discarded) {
        synchronized (lifecycleLock) {
            if (closed) return false;
            try {
                executor.execute(() -> {
                    if (!closed) action.run(); else discarded.run();
                });
                return true;
            } catch (RejectedExecutionException exception) {
                if (!closed) logger.log(Level.WARNING, "Backend executor rejected a task", exception);
                return false;
            }
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            status = new Status(State.STOPPED, status.lastError());
            try {
                executor.execute(() -> {
                    try {
                        stopped = true;
                        cancelReconnect();
                        invalidate(false);
                    } finally {
                        try { transport.close(); }
                        finally { executor.shutdown(); }
                    }
                });
            } catch (RejectedExecutionException exception) {
                try { transport.close(); }
                finally { executor.shutdown(); }
                logger.log(Level.WARNING, "Backend cleanup executor was unavailable", exception);
            }
        }
    }
}
