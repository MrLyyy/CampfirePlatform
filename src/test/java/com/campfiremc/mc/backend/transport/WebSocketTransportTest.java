package com.campfiremc.mc.backend.transport;

import org.junit.jupiter.api.Test;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketTransportTest {
    @Test
    void startRequestsTheFirstMessageOnlyAfterTheSocketOpens() {
        RecordingListener events = new RecordingListener();
        WebSocketTransport.SocketListener adapter = new WebSocketTransport.SocketListener(events, 4);
        FakeWebSocket socket = new FakeWebSocket();

        adapter.onOpen(socket);
        assertEquals(0, socket.requests);
        adapter.start();
        assertEquals(1, socket.requests);
    }

    @Test
    void assemblesBinaryFragmentsAndKeepsEachListenersBufferIndependent() {
        RecordingListener firstEvents = new RecordingListener();
        RecordingListener secondEvents = new RecordingListener();
        WebSocketTransport.SocketListener first = new WebSocketTransport.SocketListener(firstEvents, 4);
        WebSocketTransport.SocketListener second = new WebSocketTransport.SocketListener(secondEvents, 4);
        FakeWebSocket firstSocket = new FakeWebSocket();
        FakeWebSocket secondSocket = new FakeWebSocket();
        first.onOpen(firstSocket);
        second.onOpen(secondSocket);

        first.onBinary(firstSocket, ByteBuffer.wrap(new byte[]{1, 2}), false);
        assertTrue(firstEvents.frames.isEmpty());
        second.onBinary(secondSocket, ByteBuffer.wrap(new byte[]{9}), true);
        assertArrayEquals(new byte[]{9}, secondEvents.frames.get(0));
        first.onBinary(firstSocket, ByteBuffer.wrap(new byte[]{0, 3, 0}, 1, 1), true);
        assertArrayEquals(new byte[]{1, 2, 3}, firstEvents.frames.get(0));
        first.onBinary(firstSocket, ByteBuffer.wrap(new byte[]{4}), true);
        second.onBinary(secondSocket, ByteBuffer.wrap(new byte[]{8}), true);

        assertEquals(2, firstEvents.frames.size());
        assertArrayEquals(new byte[]{4}, firstEvents.frames.get(1));
        assertEquals(2, secondEvents.frames.size());
        assertArrayEquals(new byte[]{8}, secondEvents.frames.get(1));
        assertTrue(firstEvents.failures.isEmpty());
        assertTrue(secondEvents.failures.isEmpty());
        assertEquals(3, firstSocket.requests);
        assertEquals(2, secondSocket.requests);
    }

    @Test
    void acceptsFramesExactlyAtTheByteLimit() {
        RecordingListener events = new RecordingListener();
        WebSocketTransport.SocketListener adapter = new WebSocketTransport.SocketListener(events, 4);
        FakeWebSocket socket = new FakeWebSocket();
        adapter.onOpen(socket);

        adapter.onBinary(socket, ByteBuffer.wrap(new byte[]{1, 2}), false);
        adapter.onBinary(socket, ByteBuffer.wrap(new byte[]{3, 4}), true);
        adapter.onBinary(socket, ByteBuffer.wrap(new byte[]{5, 6, 7, 8}), true);

        assertEquals(2, events.frames.size());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, events.frames.get(0));
        assertArrayEquals(new byte[]{5, 6, 7, 8}, events.frames.get(1));
        assertTrue(events.failures.isEmpty());
        assertEquals(3, socket.requests);
        assertEquals(0, socket.aborts);
    }

    @Test
    void rejectsAFrameWhoseFragmentsExceedTheByteLimit() {
        RecordingListener events = new RecordingListener();
        WebSocketTransport.SocketListener adapter = new WebSocketTransport.SocketListener(events, 4);
        FakeWebSocket socket = new FakeWebSocket();
        adapter.onOpen(socket);

        adapter.onBinary(socket, ByteBuffer.wrap(new byte[]{1, 2, 3}), false);
        adapter.onBinary(socket, ByteBuffer.wrap(new byte[]{4, 5}), true);
        adapter.onBinary(socket, ByteBuffer.wrap(new byte[]{6}), true);

        assertTrue(events.frames.isEmpty());
        assertEquals(List.of("backend frame exceeded 4 bytes"), events.failures);
        assertEquals(1, socket.requests);
        assertEquals(1, socket.aborts);
    }

    @Test
    void rejectsAnOversizedSingleBinaryMessage() {
        RecordingListener events = new RecordingListener();
        WebSocketTransport.SocketListener adapter = new WebSocketTransport.SocketListener(events, 4);
        FakeWebSocket socket = new FakeWebSocket();
        adapter.onOpen(socket);

        adapter.onBinary(socket, ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5}), true);

        assertTrue(events.frames.isEmpty());
        assertEquals(List.of("backend frame exceeded 4 bytes"), events.failures);
        assertEquals(0, socket.requests);
        assertEquals(1, socket.aborts);
    }

    @Test
    void rejectsTextWithoutPublishingAnyFurtherEvents() {
        RecordingListener events = new RecordingListener();
        WebSocketTransport.SocketListener adapter = new WebSocketTransport.SocketListener(events, 4);
        FakeWebSocket socket = new FakeWebSocket();
        adapter.onOpen(socket);

        adapter.onText(socket, "not binary", true);
        adapter.onBinary(socket, ByteBuffer.wrap(new byte[]{1}), true);
        adapter.onText(socket, "again", true);

        assertTrue(events.frames.isEmpty());
        assertEquals(List.of("backend sent unexpected text frame"), events.failures);
        assertEquals(0, socket.requests);
        assertEquals(1, socket.aborts);
    }

    @Test
    void reportsOnlyTheFirstErrorOrClose() {
        RecordingListener errorEvents = new RecordingListener();
        WebSocketTransport.SocketListener errorFirst = new WebSocketTransport.SocketListener(errorEvents, 4);
        FakeWebSocket errorSocket = new FakeWebSocket();
        errorFirst.onOpen(errorSocket);
        errorFirst.onError(errorSocket, new IllegalStateException("broken"));
        errorFirst.onError(errorSocket, new IllegalStateException("again"));
        errorFirst.onClose(errorSocket, 1006, "closed");
        assertEquals(List.of("websocket error: broken"), errorEvents.failures);
        assertEquals(1, errorSocket.aborts);

        RecordingListener closeEvents = new RecordingListener();
        WebSocketTransport.SocketListener closeFirst = new WebSocketTransport.SocketListener(closeEvents, 4);
        FakeWebSocket closeSocket = new FakeWebSocket();
        closeFirst.onOpen(closeSocket);
        closeFirst.onClose(closeSocket, 1000, "bye");
        closeFirst.onClose(closeSocket, 1001, "again");
        closeFirst.onError(closeSocket, new IllegalStateException("late"));
        assertEquals(List.of("websocket closed: 1000 bye"), closeEvents.failures);
        assertEquals(1, closeSocket.aborts);
    }

    @Test
    void disconnectSuppressesSubsequentSocketEvents() {
        RecordingListener events = new RecordingListener();
        WebSocketTransport.SocketListener adapter = new WebSocketTransport.SocketListener(events, 4);
        FakeWebSocket socket = new FakeWebSocket();
        adapter.onOpen(socket);

        adapter.disconnect();
        adapter.start();
        publishLateEvents(adapter, socket);

        assertEquals(1, socket.closes);
        assertEquals(1, socket.aborts);
        assertEquals(0, socket.requests);
        assertTrue(events.frames.isEmpty());
        assertTrue(events.failures.isEmpty());
    }

    @Test
    void abortSuppressesSubsequentSocketEvents() {
        RecordingListener events = new RecordingListener();
        WebSocketTransport.SocketListener adapter = new WebSocketTransport.SocketListener(events, 4);
        FakeWebSocket socket = new FakeWebSocket();
        adapter.onOpen(socket);

        adapter.abort();
        adapter.start();
        publishLateEvents(adapter, socket);

        assertEquals(0, socket.closes);
        assertEquals(1, socket.aborts);
        assertEquals(0, socket.requests);
        assertTrue(events.frames.isEmpty());
        assertTrue(events.failures.isEmpty());
    }

    private static void publishLateEvents(WebSocketTransport.SocketListener adapter, FakeWebSocket socket) {
        adapter.onBinary(socket, ByteBuffer.wrap(new byte[]{1}), true);
        adapter.onText(socket, "late text", true);
        adapter.onError(socket, new IllegalStateException("late error"));
        adapter.onClose(socket, 1000, "late close");
    }

    private static final class RecordingListener implements BackendTransport.Listener {
        private final List<byte[]> frames = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();

        @Override
        public void onFrame(byte[] bytes) {
            frames.add(bytes);
        }

        @Override
        public void onFailure(String message) {
            failures.add(message);
        }
    }

    private static final class FakeWebSocket implements WebSocket {
        private int requests;
        private int aborts;
        private int closes;

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            closes++;
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
            requests += n;
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return closes > 0 || aborts > 0;
        }

        @Override
        public boolean isInputClosed() {
            return aborts > 0;
        }

        @Override
        public void abort() {
            aborts++;
        }
    }
}
