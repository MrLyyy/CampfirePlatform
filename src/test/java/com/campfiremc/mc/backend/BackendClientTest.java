package com.campfiremc.mc.backend;

import com.campfiremc.mc.backend.protocol.MinecraftProtocol;
import com.campfiremc.mc.backend.transport.BackendTransport;
import com.campfiremc.mc.service.BackendEventHandler;
import com.campfiremc.mc.testsupport.ManualScheduler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class BackendClientTest {
    @Test
    void verifiesThenOpensAndOnlyStartsAfterReady() {
        Fixture test = new Fixture(2, 8);
        test.client.connect();
        test.scheduler.runUntilIdle();
        test.assertState(BackendClient.State.VERIFYING);
        assertEquals(1, test.verifications.size());
        assertTrue(test.transport.opens.isEmpty());

        test.verifications.get(0).complete(42);
        test.scheduler.runUntilIdle();
        test.assertState(BackendClient.State.CONNECTING);
        assertEquals(42, test.transport.opens.get(0).key);
        assertEquals(0, test.transport.opens.get(0).connection.starts);

        test.transport.opens.get(0).complete();
        test.scheduler.runUntilIdle();
        test.assertState(BackendClient.State.READY);
        assertEquals(1, test.transport.opens.get(0).connection.starts);
        assertEquals(List.of(BackendClient.State.VERIFYING, BackendClient.State.CONNECTING,
                BackendClient.State.READY), test.events.states);
    }

    @Test
    void exponentialBackoffCapsAndSuccessfulOpenResetsDelayWithoutErasingLastError() {
        Fixture test = new Fixture(2, 5);
        test.client.connect();
        test.scheduler.runUntilIdle();
        for (int attempt = 0; attempt < 3; attempt++) {
            test.verifications.get(attempt).completeExceptionally(new IllegalStateException("failure " + attempt));
            test.scheduler.runUntilIdle();
            test.assertState(BackendClient.State.DISCONNECTED);
            int delay = attempt == 0 ? 2 : attempt == 1 ? 4 : 5;
            test.scheduler.advanceSeconds(delay - 1);
            assertEquals(attempt + 1, test.verifications.size());
            test.scheduler.advanceSeconds(1);
            assertEquals(attempt + 2, test.verifications.size());
        }
        test.verifications.get(3).complete(123);
        test.scheduler.runUntilIdle();
        test.transport.opens.get(0).complete();
        test.scheduler.runUntilIdle();
        test.assertState(BackendClient.State.READY);
        assertTrue(test.client.status().lastError().contains("failure 2"));

        test.transport.opens.get(0).listener.onFailure("socket closed");
        test.scheduler.runUntilIdle();
        assertEquals("socket closed", test.client.status().lastError());
        assertEquals(1, test.transport.opens.get(0).connection.aborts);
        test.scheduler.advanceSeconds(1);
        assertEquals(4, test.verifications.size());
        test.scheduler.advanceSeconds(1);
        assertEquals(5, test.verifications.size());
    }

    @Test
    void disconnectDuringVerificationCancelsItAndIgnoresLateVerification() {
        Fixture test = new Fixture(2, 8);
        test.client.connect();
        test.scheduler.runUntilIdle();
        test.client.disconnect();
        test.scheduler.runUntilIdle();
        test.assertState(BackendClient.State.STOPPED);
        assertTrue(test.verifications.get(0).isCancelled());
        test.client.connect();
        test.scheduler.runUntilIdle();
        assertEquals(1, test.verifications.size());
        test.scheduler.advanceSeconds(30);
        assertTrue(test.transport.opens.isEmpty());
        test.client.reconnect();
        test.scheduler.runUntilIdle();
        assertEquals(2, test.verifications.size());
    }

    @Test
    void disconnectWhileHandshakeIsPendingAbortsLateConnection() {
        Fixture test = new Fixture(2, 8);
        test.connectToPendingOpen();
        Open pending = test.transport.opens.get(0);
        test.client.disconnect();
        test.scheduler.runUntilIdle();
        pending.complete();
        test.scheduler.runUntilIdle();
        test.assertState(BackendClient.State.STOPPED);
        assertEquals(1, pending.connection.aborts);
        assertEquals(0, pending.connection.starts);
    }

    @Test
    void reconnectIsolatesOldFramesFailuresAndPendingSends() {
        Fixture test = new Fixture(2, 8);
        Open old = test.ready();
        test.client.sendMessage("p", "first");
        test.scheduler.runUntilIdle();
        assertEquals(1, old.connection.messages.size());
        test.client.reconnect();
        test.scheduler.runUntilIdle();
        assertEquals(1, old.connection.aborts);
        test.assertState(BackendClient.State.VERIFYING);
        old.listener.onFrame(chatFrame());
        old.listener.onFailure("stale error");
        old.connection.sends.get(0).completeExceptionally(new IllegalStateException("old send"));
        test.scheduler.runUntilIdle();
        assertTrue(test.events.chats.isEmpty());
        assertEquals("", test.client.status().lastError());
        assertEquals(2, test.verifications.size());
        test.verifications.get(1).complete(7);
        test.scheduler.runUntilIdle();
        Open current = test.transport.opens.get(1);
        current.complete();
        test.scheduler.runUntilIdle();
        test.assertState(BackendClient.State.READY);
        assertEquals(1, current.connection.starts);
        assertEquals(0, current.connection.messages.size());
    }

    @Test
    void closeIsIdempotentAndAbortsOpenCompletingAfterCleanup() {
        Fixture test = new Fixture(2, 8);
        test.connectToPendingOpen();
        Open pending = test.transport.opens.get(0);
        test.client.close();
        test.client.close();
        test.scheduler.runUntilIdle();
        assertEquals(1, test.transport.closes);
        test.assertState(BackendClient.State.STOPPED);
        pending.complete();
        test.scheduler.runUntilIdle();
        assertEquals(1, pending.connection.aborts, "late open must be released exactly once");
        assertEquals(0, pending.connection.starts);
    }

    @Test
    void closeReleasesOpenWhoseCompletionCallbackWasAlreadyQueued() {
        Fixture test = new Fixture(2, 8);
        test.connectToPendingOpen();
        Open pending = test.transport.opens.get(0);
        pending.complete(); // callback queued, but not yet executed
        test.client.close();
        test.scheduler.runUntilIdle();
        assertEquals(1, pending.connection.aborts, "queued open must not leak on close");
        assertEquals(0, pending.connection.starts);
        assertEquals(1, test.transport.closes);
    }

    @Test
    void sendWaitsForCompletionAndOneFailureStartsOnlyOneReconnect() {
        Fixture test = new Fixture(2, 8);
        Open open = test.ready();
        test.client.sendJoin("a");
        test.client.sendMessage("a", "hello");
        test.client.sendLeave("a");
        test.scheduler.runUntilIdle();
        assertEquals(1, open.connection.messages.size());
        assertArrayEquals(MinecraftProtocol.command("a", "join", "a"), open.connection.messages.get(0));
        open.connection.sends.get(0).complete(null);
        test.scheduler.runUntilIdle();
        assertEquals(2, open.connection.messages.size());
        assertArrayEquals(MinecraftProtocol.message("a", "hello"), open.connection.messages.get(1));
        open.connection.sends.get(1).completeExceptionally(new IllegalStateException("write failed"));
        open.listener.onFailure("socket failed too");
        test.scheduler.runUntilIdle();
        test.assertState(BackendClient.State.DISCONNECTED);
        assertEquals(1, open.connection.aborts);
        assertEquals(2, open.connection.messages.size(), "queued leave is dropped");
        test.scheduler.advanceSeconds(2);
        assertEquals(2, test.verifications.size(), "one retry despite two errors");
        test.scheduler.advanceSeconds(20);
        assertEquals(2, test.verifications.size());
    }

    @Test
    void eventHandlerExceptionDoesNotBecomeAProtocolError() {
        Fixture test = new Fixture(2, 8);
        Open open = test.ready();
        test.events.throwOnChat = true;
        open.listener.onFrame(chatFrame());
        test.scheduler.runUntilIdle();
        test.assertState(BackendClient.State.READY);
        assertEquals(0, open.connection.aborts);
        assertEquals("", test.client.status().lastError());
        test.events.throwOnChat = false;
        open.listener.onFrame(chatFrame());
        test.scheduler.runUntilIdle();
        assertEquals(List.of(new MinecraftProtocol.Chat("server", "hi")), test.events.chats);
    }

    @Test
    void routesRealBinaryChatAndCommandFramesAndRejectsMalformedFrames() {
        Fixture test = new Fixture(2, 8);
        Open open = test.ready();
        open.listener.onFrame(chatFrame());
        open.listener.onFrame(bytes(0x0a, 1, 'u', 0x12, 4, 'j', 'o', 'i', 'n', 0x18, 1));
        test.scheduler.runUntilIdle();
        assertEquals(List.of(new MinecraftProtocol.Chat("server", "hi")), test.events.chats);
        assertEquals(List.of(new MinecraftProtocol.CommandResult("u", "join", true)), test.events.results);
        test.assertState(BackendClient.State.READY);
        open.listener.onFrame(bytes(0x0a, 3, 'b'));
        test.scheduler.runUntilIdle();
        test.assertState(BackendClient.State.DISCONNECTED);
        assertTrue(test.client.status().lastError().startsWith("invalid backend protobuf frame:"));
        assertEquals(1, open.connection.aborts);
    }

    @Test
    void queuedMessagesAreCheckedAgainImmediatelyBeforeSending() {
        Fixture test = new Fixture(2, 8);
        Open open = test.ready();
        var eligible = new java.util.concurrent.atomic.AtomicBoolean(true);
        test.client.sendMessage("p", "first", eligible::get);
        test.client.sendMessage("p", "second", eligible::get);
        test.scheduler.runUntilIdle();
        assertEquals(1, open.connection.messages.size());
        eligible.set(false);
        open.connection.sends.get(0).complete(null);
        test.scheduler.runUntilIdle();
        assertEquals(1, open.connection.messages.size());
    }

    @Test
    void bindUsesCommandPayloadAndDoesNotExposeCodeInLogsOrErrors() {
        Fixture test = new Fixture(2, 8);
        Open open = test.ready();
        var logs = new ArrayList<String>();
        Logger logger = Logger.getLogger("BackendClientTest");
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) { logs.add(record.getMessage()); }
            public void flush() {}
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            test.client.sendBind("p", "123456", () -> true);
            test.scheduler.runUntilIdle();
            assertArrayEquals(MinecraftProtocol.command("p", "bind", "123456"), open.connection.messages.get(0));
            open.connection.sends.get(0).completeExceptionally(new IllegalStateException("123456 rejected"));
            test.scheduler.runUntilIdle();
            assertFalse(test.client.status().lastError().contains("123456"));
            assertTrue(logs.stream().noneMatch(line -> line.contains("123456")));
        } finally { logger.removeHandler(handler); }
    }

    private static byte[] chatFrame() {
        return bytes(0x0a, 6, 's', 'e', 'r', 'v', 'e', 'r', 0x12, 2, 'h', 'i');
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) bytes[i] = (byte) values[i];
        return bytes;
    }

    private static final class Fixture {
        final ManualScheduler scheduler = new ManualScheduler();
        final List<CompletableFuture<Integer>> verifications = new ArrayList<>();
        final FakeTransport transport = new FakeTransport();
        final RecordingEvents events = new RecordingEvents();
        final BackendClient client;

        Fixture(int initial, int max) {
            Supplier<CompletableFuture<Integer>> verify = () -> {
                CompletableFuture<Integer> result = new CompletableFuture<>();
                verifications.add(result);
                return result;
            };
            client = new BackendClient(Logger.getLogger("BackendClientTest"), verify, transport,
                    scheduler, initial, max, events);
        }

        void assertState(BackendClient.State state) { assertEquals(state, client.status().state()); }

        void connectToPendingOpen() {
            client.connect();
            scheduler.runUntilIdle();
            verifications.get(0).complete(7);
            scheduler.runUntilIdle();
            assertState(BackendClient.State.CONNECTING);
        }

        Open ready() {
            connectToPendingOpen();
            Open open = transport.opens.get(0);
            open.complete();
            scheduler.runUntilIdle();
            assertState(BackendClient.State.READY);
            return open;
        }
    }

    private static final class FakeTransport implements BackendTransport {
        final List<Open> opens = new ArrayList<>();
        int closes;

        @Override
        public CompletableFuture<Connection> open(int key, Listener listener) {
            Open open = new Open(key, listener);
            opens.add(open);
            return open.future;
        }

        @Override
        public void close() { closes++; }
    }

    private static final class Open {
        final int key;
        final BackendTransport.Listener listener;
        final FakeConnection connection = new FakeConnection();
        final CompletableFuture<BackendTransport.Connection> future = new CompletableFuture<>();

        Open(int key, BackendTransport.Listener listener) {
            this.key = key;
            this.listener = listener;
        }

        void complete() { future.complete(connection); }
    }

    private static final class FakeConnection implements BackendTransport.Connection {
        final List<byte[]> messages = new ArrayList<>();
        final List<CompletableFuture<Void>> sends = new ArrayList<>();
        int starts;
        int disconnects;
        int aborts;

        @Override
        public void start() { starts++; }

        @Override
        public CompletableFuture<Void> send(byte[] bytes) {
            messages.add(bytes.clone());
            CompletableFuture<Void> result = new CompletableFuture<>();
            sends.add(result);
            return result;
        }

        @Override
        public void disconnect() { disconnects++; }

        @Override
        public void abort() { aborts++; }
    }

    private static final class RecordingEvents implements BackendEventHandler {
        final List<BackendClient.State> states = new ArrayList<>();
        final List<MinecraftProtocol.Chat> chats = new ArrayList<>();
        final List<MinecraftProtocol.CommandResult> results = new ArrayList<>();
        boolean throwOnChat;

        @Override
        public void onStateChanged(BackendClient.Status status) { states.add(status.state()); }

        @Override
        public void onChat(MinecraftProtocol.Chat chat) {
            if (throwOnChat) throw new IllegalStateException("handler failed");
            chats.add(chat);
        }

        @Override
        public void onCommandResult(MinecraftProtocol.CommandResult result) { results.add(result); }
    }
}
