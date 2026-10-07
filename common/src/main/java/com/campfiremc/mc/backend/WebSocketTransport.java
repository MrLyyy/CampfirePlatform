package com.campfiremc.mc.backend;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One WebSocket session, with binary framing and at-most-once failure reporting. */
public final class WebSocketTransport {
    public interface Socket {
        CompletableFuture<WebSocket> sendBinary(ByteBuffer bytes, boolean last);
        void startReceiving();
        void close();
        void abort();
    }

    private final HttpClient http;
    private final int maxMessageBytes;
    private final Duration handshakeTimeout;

    public WebSocketTransport(HttpClient http, Duration handshakeTimeout, int maxMessageBytes) {
        this.http = Objects.requireNonNull(http, "http");
        this.handshakeTimeout = Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
        if (maxMessageBytes < 1) throw new IllegalArgumentException("maxMessageBytes must be positive");
        this.maxMessageBytes = maxMessageBytes;
    }

    public CompletableFuture<Socket> open(URI baseUrl, String account, int key,
                                          Consumer<byte[]> received, Consumer<String> failure) {
        String url = baseUrl.toString().replaceFirst("/$", "")
                .replaceFirst("^http://", "ws://").replaceFirst("^https://", "wss://")
                + "/api/platform/mc?account_uuid="
                + URLEncoder.encode(account.trim(), StandardCharsets.UTF_8).replace("+", "%20")
                + "&key=" + key;
        FrameListener listener = new FrameListener(maxMessageBytes, received, failure);
        return http.newWebSocketBuilder().connectTimeout(handshakeTimeout)
                .buildAsync(URI.create(url), listener).thenApply(socket -> (Socket) listener);
    }

    /** Exposed to tests to exercise fragmentation and terminal event deduplication. */
    public static final class FrameListener implements WebSocket.Listener, Socket {
        private final int limit;
        private final Consumer<byte[]> received;
        private final Consumer<String> failure;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private ByteArrayOutputStream partial = new ByteArrayOutputStream();
        private volatile WebSocket socket;
        private volatile boolean ready;

        public FrameListener(int limit, Consumer<byte[]> received, Consumer<String> failure) {
            if (limit < 1) throw new IllegalArgumentException("limit must be positive");
            this.limit = limit;
            this.received = Objects.requireNonNull(received, "received");
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        @Override public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            // The initial request is made only after the client publishes READY.
        }

        @Override public void startReceiving() {
            ready = true;
            WebSocket ws = socket;
            if (ws != null && !terminal.get()) ws.request(1);
        }

        @Override public synchronized CompletionStage<?> onBinary(WebSocket ws, ByteBuffer bytes, boolean last) {
            if (terminal.get()) return CompletableFuture.completedFuture(null);
            int length = bytes.remaining();
            if (length > limit - partial.size()) {
                fail("backend frame exceeded " + limit + " bytes");
                return CompletableFuture.completedFuture(null);
            }
            byte[] chunk = new byte[length];
            bytes.get(chunk);
            partial.writeBytes(chunk);
            if (last) {
                byte[] complete = partial.toByteArray();
                partial = new ByteArrayOutputStream();
                try { received.accept(complete); }
                catch (RuntimeException e) { fail("backend message processing failed"); }
            }
            if (ready && !terminal.get()) ws.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
            fail("backend sent text frame");
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            fail("backend websocket closed");
            return CompletableFuture.completedFuture(null);
        }

        @Override public void onError(WebSocket ws, Throwable error) { fail("backend websocket error"); }

        private void fail(String error) {
            if (terminal.compareAndSet(false, true)) {
                abortSocket();
                try { failure.accept(error); } catch (RuntimeException ignored) { }
            }
        }

        @Override public CompletableFuture<WebSocket> sendBinary(ByteBuffer bytes, boolean last) {
            if (terminal.get() || socket == null) return CompletableFuture.failedFuture(
                    new IllegalStateException("backend websocket unavailable"));
            return socket.sendBinary(bytes, last);
        }

        @Override public void close() {
            if (!terminal.compareAndSet(false, true)) return;
            WebSocket ws = socket;
            if (ws == null) return;
            try {
                ws.sendClose(1000, "plugin disconnect")
                        .orTimeout(5, TimeUnit.SECONDS)
                        .exceptionally(error -> { ws.abort(); return null; });
            } catch (RuntimeException e) { ws.abort(); }
        }

        @Override public void abort() {
            terminal.set(true);
            abortSocket();
        }

        private void abortSocket() {
            WebSocket ws = socket;
            if (ws != null) ws.abort();
        }
    }
}
