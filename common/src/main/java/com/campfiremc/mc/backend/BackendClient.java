package com.campfiremc.mc.backend;

import com.campfiremc.mc.protocol.MinecraftProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Serializes connection state and outbound messages on a single daemon executor. */
public final class BackendClient implements BackendConnection {
    private static final Logger LOG = LoggerFactory.getLogger(BackendClient.class);
    public interface Exchange {
        CompletableFuture<Integer> verify();
        CompletableFuture<WebSocketTransport.Socket> open(int key, java.util.function.Consumer<byte[]> received,
                                                           java.util.function.Consumer<String> failed);
        void shutdown();
    }

    private final Exchange exchange;
    private final ScheduledExecutorService executor;
    private final int initialReconnectSeconds;
    private final int maxReconnectSeconds;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final Set<Pending> awaitingExecutor = ConcurrentHashMap.newKeySet();
    private volatile Status status = new Status(State.DISCONNECTED, null, 0);
    private volatile Listener listener;
    private volatile boolean closed;
    private boolean stopped;
    private long generation;
    private int retryDelay;
    private boolean sending;
    private ScheduledFuture<?> retry;
    private CompletableFuture<Integer> verification;
    private WebSocketTransport.Socket socket;

    public BackendClient(URI baseUrl, String accountUuid, Ed25519Credentials credentials,
                         Duration connectTimeout, Duration requestTimeout, Duration handshakeTimeout,
                         int maxMessageBytes, int initialReconnectSeconds, int maxReconnectSeconds) {
        this(makeExchange(baseUrl, accountUuid, credentials, connectTimeout, requestTimeout,
                        handshakeTimeout, maxMessageBytes), initialReconnectSeconds, maxReconnectSeconds);
    }

    /** Injection seam for deterministic network fakes. */
    public BackendClient(Exchange exchange, int initialReconnectSeconds, int maxReconnectSeconds) {
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        if (initialReconnectSeconds < 1 || maxReconnectSeconds < initialReconnectSeconds) {
            throw new IllegalArgumentException("invalid reconnect delay");
        }
        this.initialReconnectSeconds = initialReconnectSeconds;
        this.maxReconnectSeconds = maxReconnectSeconds;
        this.retryDelay = initialReconnectSeconds;
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "campfire-backend");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static Exchange makeExchange(URI base, String account, Ed25519Credentials credentials,
                                         Duration connectTimeout, Duration requestTimeout,
                                         Duration handshakeTimeout, int maxMessageBytes) {
        Objects.requireNonNull(base, "baseUrl");
        Objects.requireNonNull(credentials, "credentials");
        String trimmed = Objects.requireNonNull(account, "accountUuid").trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("accountUuid is empty");
        HttpClient http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        BackendAuthenticator auth = new BackendAuthenticator(http, base, requestTimeout);
        WebSocketTransport transport = new WebSocketTransport(http, handshakeTimeout, maxMessageBytes);
        return new Exchange() {
            @Override public CompletableFuture<Integer> verify() { return auth.verify(trimmed, credentials); }
            @Override public CompletableFuture<WebSocketTransport.Socket> open(int key,
                    java.util.function.Consumer<byte[]> received, java.util.function.Consumer<String> failed) {
                return transport.open(base, trimmed, key, received, failed);
            }
            @Override public void shutdown() { http.shutdownNow(); }
        };
    }

    @Override public Status status() { return status; }
    @Override public void setListener(Listener listener) { this.listener = listener; }

    @Override public void connect() {
        post(() -> {
            if (!stopped && status.state() == State.DISCONNECTED && retry == null) begin();
        });
    }

    @Override public void reconnect() {
        post(() -> {
            stopped = false;
            invalidate(false);
            retryDelay = initialReconnectSeconds;
            begin();
        });
    }

    @Override public void disconnect() {
        post(() -> {
            stopped = true;
            invalidate(true);
            update(State.STOPPED, status.lastError());
        });
    }

    @Override public void send(long sessionId, byte[] bytes, BooleanSupplier guard, Runnable discarded) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(guard, "guard");
        Objects.requireNonNull(discarded, "discarded");
        Pending item = new Pending(sessionId, bytes.clone(), guard, discarded);
        if (closed || status.state() != State.READY || status.sessionId() != sessionId) {
            item.discard();
            return;
        }
        awaitingExecutor.add(item);
        if (!post(() -> {
            awaitingExecutor.remove(item);
            if (!valid(item)) { item.discard(); return; }
            pending.addLast(item);
            drain();
        })) {
            awaitingExecutor.remove(item);
            item.discard();
        }
    }

    private boolean valid(Pending item) {
        if (closed || status.state() != State.READY || status.sessionId() != item.sessionId) return false;
        try { return item.guard.getAsBoolean(); }
        catch (RuntimeException ignored) { return false; }
    }

    private void drain() {
        if (sending) return;
        while (!pending.isEmpty()) {
            Pending item = pending.removeFirst();
            if (!valid(item)) { item.discard(); continue; }
            sending = true;
            long current = generation;
            try {
                socket.sendBinary(ByteBuffer.wrap(item.bytes), true).whenComplete((sent, error) -> {
                    post(() -> {
                        if (current != generation || closed) return;
                        sending = false;
                        if (error != null) fail(current, item.sendError());
                        else drain();
                    });
                });
            } catch (RuntimeException e) {
                sending = false;
                fail(current, item.sendError());
            }
            return;
        }
    }

    private void begin() {
        if (closed || stopped) return;
        long current = ++generation;
        update(State.VERIFYING, status.lastError());
        try {
            verification = exchange.verify();
            verification.whenComplete((key, error) -> post(() -> {
                if (current != generation || stopped || closed) return;
                verification = null;
                if (error != null) { fail(current, "verification request failed"); return; }
                update(State.CONNECTING, status.lastError());
                try {
                    exchange.open(key,
                            bytes -> post(() -> receive(current, bytes)),
                            failure -> post(() -> fail(current, failure)))
                            .whenComplete((opened, openError) -> {
                                if (!post(() -> {
                                    if (current != generation || stopped || closed) {
                                        if (opened != null) opened.abort();
                                        return;
                                    }
                                    if (openError != null) { fail(current, "backend websocket handshake failed"); return; }
                                    socket = opened;
                                    retryDelay = initialReconnectSeconds;
                                    update(State.READY, status.lastError());
                                    try { opened.startReceiving(); }
                                    catch (RuntimeException e) { fail(current, "backend websocket start failed"); }
                                }) && opened != null) opened.abort();
                            });
                } catch (RuntimeException e) { fail(current, "backend websocket handshake failed"); }
            }));
        } catch (RuntimeException e) { fail(current, "verification request failed"); }
    }

    private void receive(long current, byte[] bytes) {
        if (current != generation || closed || status.state() != State.READY) return;
        MinecraftProtocol.ServerMessage decoded;
        try { decoded = MinecraftProtocol.decode(bytes); }
        catch (RuntimeException e) { fail(current, "invalid backend protobuf message"); return; }
        Listener callback = listener;
        if (callback != null) {
            try { callback.onMessage(current, decoded); }
            catch (RuntimeException e) { LOG.warn("Backend message listener failed ({})", e.getClass().getSimpleName()); }
        }
    }

    private void fail(long current, String reason) {
        if (current != generation || stopped || closed || status.state() == State.DISCONNECTED) return;
        invalidate(false);
        update(State.DISCONNECTED, reason);
        int seconds = retryDelay;
        retryDelay = (int) Math.min(maxReconnectSeconds,
                Math.max((long) retryDelay + 1, (long) retryDelay * 2));
        retry = executor.schedule(() -> {
            retry = null;
            begin();
        }, seconds, TimeUnit.SECONDS);
    }

    private void invalidate(boolean graceful) {
        generation++;
        // Discard callbacks may re-enter send; the old session must already be unavailable.
        status = new Status(closed || stopped ? State.STOPPED : State.DISCONNECTED, status.lastError(), generation);
        if (retry != null) { retry.cancel(false); retry = null; }
        if (verification != null) { verification.cancel(true); verification = null; }
        WebSocketTransport.Socket old = socket;
        socket = null;
        sending = false;
        while (!pending.isEmpty()) pending.removeFirst().discard();
        if (old != null) {
            try { if (graceful) old.close(); else old.abort(); }
            catch (RuntimeException ignored) { }
        }
    }

    private void update(State state, String error) {
        status = new Status(state, error, generation);
        if (!closed) {
            Listener callback = listener;
            if (callback != null) {
                try { callback.onStatus(status); }
                catch (RuntimeException e) { LOG.warn("Backend status listener failed ({})", e.getClass().getSimpleName()); }
            }
        }
    }

    private boolean post(Runnable task) {
        if (closed) return false;
        try { executor.execute(task); return true; }
        catch (RejectedExecutionException ignored) { return false; }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        status = new Status(State.STOPPED, status.lastError(), status.sessionId() + 1);
        for (Pending item : awaitingExecutor) item.discard();
        awaitingExecutor.clear();
        // Closing avoids blocking on HTTP/WebSocket shutdown. A queued send has an explicit discard path.
        try {
            executor.execute(() -> {
                try {
                    stopped = true;
                    invalidate(false);
                    status = new Status(State.STOPPED, status.lastError(), generation);
                } finally {
                    exchange.shutdown();
                }
            });
        } finally { executor.shutdown(); }
    }

    private static final class Pending {
        final long sessionId;
        final byte[] bytes;
        final BooleanSupplier guard;
        final Runnable discarded;
        private boolean done;
        Pending(long sessionId, byte[] bytes, BooleanSupplier guard, Runnable discarded) {
            this.sessionId = sessionId;
            this.bytes = bytes;
            this.guard = guard;
            this.discarded = discarded;
        }
        String sendError() {
            // Bind requests carry a sensitive one-time code; never expose it or transport details.
            return bytes.length > 9 && containsBindAction(bytes)
                    ? "websocket bind send failed" : "backend websocket send failed";
        }
        private static boolean containsBindAction(byte[] bytes) {
            // Inspect only the canonical command prefix, never stringify the one-time code.
            if (bytes.length == 0 || bytes[0] != 0x0a) return false;
            int[] cursor = {1};
            int uuidLength = readLength(bytes, cursor);
            if (uuidLength < 0 || uuidLength > bytes.length - cursor[0]) return false;
            cursor[0] += uuidLength;
            if (cursor[0] >= bytes.length || bytes[cursor[0]++] != 0x12) return false;
            int commandLength = readLength(bytes, cursor);
            if (commandLength < 0 || commandLength > bytes.length - cursor[0]) return false;
            if (cursor[0] >= bytes.length || bytes[cursor[0]++] != 0x0a) return false;
            int actionLength = readLength(bytes, cursor);
            return actionLength == 4 && cursor[0] + 4 <= bytes.length
                    && bytes[cursor[0]] == 'b' && bytes[cursor[0] + 1] == 'i'
                    && bytes[cursor[0] + 2] == 'n' && bytes[cursor[0] + 3] == 'd';
        }
        private static int readLength(byte[] bytes, int[] cursor) {
            int value = 0;
            for (int shift = 0; shift <= 28; shift += 7) {
                if (cursor[0] >= bytes.length) return -1;
                int b = bytes[cursor[0]++] & 255;
                if (shift == 28 && (b & 0xf0) != 0) return -1;
                value |= (b & 127) << shift;
                if ((b & 128) == 0) return value;
            }
            return -1;
        }
        synchronized void discard() {
            if (done) return;
            done = true;
            try { discarded.run(); } catch (RuntimeException ignored) { }
        }
    }
}
