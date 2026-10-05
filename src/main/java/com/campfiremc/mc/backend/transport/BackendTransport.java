package com.campfiremc.mc.backend.transport;

import java.util.concurrent.CompletableFuture;

public interface BackendTransport extends AutoCloseable {
    CompletableFuture<Connection> open(int key, Listener listener);

    interface Connection {
        void start();
        CompletableFuture<Void> send(byte[] bytes);
        void disconnect();
        void abort();
    }

    interface Listener {
        void onFrame(byte[] bytes);
        void onFailure(String message);
    }

    @Override
    void close();
}
