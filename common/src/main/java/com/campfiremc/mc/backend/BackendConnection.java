package com.campfiremc.mc.backend;

import com.campfiremc.mc.protocol.MinecraftProtocol;
import java.util.function.BooleanSupplier;

/** Asynchronous backend connection; callbacks may run outside the game thread. */
public interface BackendConnection extends AutoCloseable {
    enum State { DISCONNECTED, VERIFYING, CONNECTING, READY, STOPPED }
    record Status(State state, String lastError, long sessionId) { }
    interface Listener {
        void onStatus(Status status);
        void onMessage(long sessionId, MinecraftProtocol.ServerMessage message);
    }
    Status status();
    void connect();
    void reconnect();
    void disconnect();
    void setListener(Listener listener);
    void send(long sessionId, byte[] bytes, BooleanSupplier guard, Runnable discarded);
    @Override void close();
}
