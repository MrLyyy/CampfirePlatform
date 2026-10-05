package com.campfiremc.mc.backend.transport;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WebSocketTransport implements BackendTransport {
    private final HttpClient httpClient;
    private final URI endpoint;
    private final Duration timeout;
    private final int maxFrameBytes;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Owns the supplied HTTP client, shared only with this backend's authenticator. */
    public WebSocketTransport(HttpClient httpClient, String baseUrl, String accountUuid,
                              Duration timeout, int maxFrameBytes) {
        this.httpClient = httpClient;
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String wsBase = base.replaceFirst("^https://", "wss://").replaceFirst("^http://", "ws://");
        String account = URLEncoder.encode(accountUuid, StandardCharsets.UTF_8).replace("+", "%20");
        this.endpoint = URI.create(wsBase + "/api/platform/mc?account_uuid=" + account);
        this.timeout = timeout;
        this.maxFrameBytes = maxFrameBytes;
    }

    @Override
    public CompletableFuture<Connection> open(int key, BackendTransport.Listener listener) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("transport is closed"));
        CompletableFuture<Connection> result = new CompletableFuture<>();
        SocketListener adapter = new SocketListener(listener, maxFrameBytes);
        URI uri = URI.create(endpoint + "&key=" + key);
        httpClient.newWebSocketBuilder().connectTimeout(timeout).buildAsync(uri, adapter)
                .whenComplete((socket, error) -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                    } else {
                        adapter.socket = socket;
                        if (closed.get()) {
                            socket.abort();
                            result.completeExceptionally(new IllegalStateException("transport is closed"));
                        } else if (!result.complete(adapter)) socket.abort();
                    }
                });
        return result;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) httpClient.shutdownNow();
    }

    static final class SocketListener implements WebSocket.Listener, Connection {
        private final BackendTransport.Listener listener;
        private final int maxFrameBytes;
        private final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private volatile WebSocket socket;

        SocketListener(BackendTransport.Listener listener, int maxFrameBytes) {
            this.listener = listener;
            this.maxFrameBytes = maxFrameBytes;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            if (terminated.get()) webSocket.abort();
        }

        @Override
        public void start() {
            if (!terminated.get()) socket.request(1);
        }

        @Override
        public CompletableFuture<Void> send(byte[] bytes) {
            if (terminated.get()) return CompletableFuture.failedFuture(new IllegalStateException("socket is closed"));
            return socket.sendBinary(ByteBuffer.wrap(bytes), true).thenApply(ignored -> null);
        }

        @Override
        public void disconnect() {
            if (terminated.compareAndSet(false, true)) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "plugin disconnect")
                        .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .whenComplete((ignored, error) -> socket.abort());
            }
        }

        @Override
        public void abort() {
            terminated.set(true);
            if (socket != null) socket.abort();
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            if (terminated.get()) return null;
            if (data.remaining() > maxFrameBytes - frame.size()) {
                fail("backend frame exceeded " + maxFrameBytes + " bytes");
                return null;
            }
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            frame.writeBytes(bytes);
            if (last) {
                byte[] complete = frame.toByteArray();
                frame.reset();
                listener.onFrame(complete);
            }
            if (!terminated.get()) webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fail("backend sent unexpected text frame");
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            fail("websocket closed: " + statusCode + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            fail("websocket error: " + error.getMessage());
        }

        private void fail(String message) {
            if (terminated.compareAndSet(false, true)) {
                if (socket != null) socket.abort();
                listener.onFailure(message);
            }
        }
    }
}
