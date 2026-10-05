package com.campfiremc.mc.service;

import com.campfiremc.mc.backend.BackendClient;
import com.campfiremc.mc.backend.protocol.MinecraftProtocol;
import com.campfiremc.mc.backend.transport.BackendTransport;
import com.campfiremc.mc.testsupport.ManualScheduler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class CampfireServiceTest {
    @Test
    void unconfiguredServiceIsSafeAndNeverAuthorizesChat() {
        PlayerSyncState state = new PlayerSyncState();
        CampfireService service = new CampfireService(state, new RecordingPresentation(), null);
        assertFalse(service.isConfigured());
        assertEquals(BackendClient.State.DISCONNECTED, service.status().state());
        service.connect();
        service.disconnect();
        service.join("p");
        service.playerJoined("p");
        service.leave("p");
        service.playerQuit("p");
        assertFalse(service.chat("p", "hello"));
        assertEquals(CampfireService.BindSubmission.UNCONFIGURED, service.bind("p", "100000"));
        service.close();
        assertFalse(state.isAuthorized("p"));
    }

    @Test
    void explicitTrueAuthorizesChatFalseDeniesAndMissingTagThreeDoesNothing() {
        Fixture test = new Fixture();
        test.ready();
        assertFalse(test.service.chat("u", "before join"));
        test.service.playerJoined("u");
        test.scheduler.runUntilIdle();
        assertSent(test, 0, MinecraftProtocol.command("u", "join", "u"));
        assertFalse(test.service.chat("u", "pending"));
        test.frame("u", "join", null);
        assertFalse(test.state.isAuthorized("u"));
        test.frame("u", "join", true);
        assertTrue(test.state.isAuthorized("u"));
        assertTrue(test.service.chat("u", "hello"));
        test.completeSend(0);
        assertSent(test, 1, MinecraftProtocol.message("u", "hello"));
        test.service.leave("u");
        assertFalse(test.service.chat("u", "after leave"));
        test.completeSend(1);
        assertSent(test, 2, MinecraftProtocol.command("u", "leave", "u"));
        test.frame("u", "leave", true);
        test.service.join("u");
        test.completeSend(2);
        assertSent(test, 3, MinecraftProtocol.command("u", "join", "u"));
        test.frame("u", "join", false);
        assertFalse(test.state.isAuthorized("u"));
        assertFalse(test.service.chat("u", "denied"));
    }

    @Test
    void abandonedJoinOccupiesSlotUntilResultThenReturningPlayerRetries() {
        Fixture test = new Fixture();
        test.ready();
        test.service.playerJoined("u");
        test.scheduler.runUntilIdle();
        test.service.playerQuit("u");
        test.service.playerJoined("u");
        test.scheduler.runUntilIdle();
        assertEquals(1, countCommand(test, "join"));
        assertFalse(test.state.isAuthorized("u"));
        test.frame("u", "join", true); // old incarnation: must not authorize
        assertFalse(test.state.isAuthorized("u"));
        test.completeSend(0);
        assertSent(test, 1, MinecraftProtocol.command("u", "join", "u"));
        test.frame("u", "join", true);
        assertTrue(test.state.isAuthorized("u"));
    }

    @Test
    void bindCodesAndPendingBindRevokeAuthorizationWithoutAutoRetry() {
        for (String invalid : List.of("000000", "099999", "99999", "1000000", "1a0000", "１２３４５６", " 100000", "100000 ")) {
            assertFalse(CampfireService.isValidBindCode(invalid), invalid);
        }
        assertFalse(CampfireService.isValidBindCode(null));
        assertTrue(CampfireService.isValidBindCode("100000"));
        assertTrue(CampfireService.isValidBindCode("999999"));
        Fixture test = new Fixture();
        assertEquals(CampfireService.BindSubmission.NOT_READY, test.service.bind("u", "100000"));
        test.ready();
        test.service.join("u");
        test.scheduler.runUntilIdle();
        test.frame("u", "join", true);
        assertTrue(test.state.isAuthorized("u"));
        assertEquals(CampfireService.BindSubmission.INVALID_CODE, test.service.bind("u", "０00000"));
        assertEquals(CampfireService.BindSubmission.SUBMITTED, test.service.bind("u", "100000"));
        assertFalse(test.state.isAuthorized("u"));
        assertFalse(test.service.chat("u", "nope"));
        assertEquals(CampfireService.BindSubmission.ALREADY_PENDING, test.service.bind("u", "100001"));
        test.completeSend(0);
        assertSent(test, 1, MinecraftProtocol.command("u", "bind", "100000"));
        test.frame("u", "bind", false);
        assertEquals(1, test.presentation.results.size());
        assertEquals("bind", test.presentation.results.get(0).action());
        assertFalse(test.state.isAuthorized("u"));
        assertEquals(1, countCommand(test, "bind"));
        assertEquals(CampfireService.BindSubmission.SUBMITTED, test.service.bind("u", "999999"));
        test.completeSend(1);
        assertSent(test, 2, MinecraftProtocol.command("u", "bind", "999999"));
        test.frame("u", "bind", true);
        assertFalse(test.state.isAuthorized("u"));
        assertFalse(test.service.chat("u", "waiting for post-bind join"));
        test.completeSend(2);
        assertSent(test, 3, MinecraftProtocol.command("u", "join", "u"));
        test.frame("u", "join", true);
        assertTrue(test.state.isAuthorized("u"));
    }

    @Test
    void oldJoinCannotOverrideBindAndAbandonedBindResultDoesNotNotify() {
        Fixture test = new Fixture();
        test.ready();
        test.service.join("u");
        test.scheduler.runUntilIdle();
        assertEquals(CampfireService.BindSubmission.SUBMITTED, test.service.bind("u", "100000"));
        test.frame("u", "join", true);
        assertFalse(test.state.isAuthorized("u"));
        test.completeSend(0);
        assertSent(test, 1, MinecraftProtocol.command("u", "bind", "100000"));
        test.frame("u", "bind", true);
        assertEquals(1, test.presentation.results.size());
        assertFalse(test.state.isAuthorized("u"));
        test.completeSend(1);
        assertSent(test, 2, MinecraftProtocol.command("u", "join", "u"));
        test.frame("u", "join", true);
        assertTrue(test.state.isAuthorized("u"));
        assertEquals(CampfireService.BindSubmission.SUBMITTED, test.service.bind("u", "999999"));
        test.completeSend(2);
        assertSent(test, 3, MinecraftProtocol.command("u", "bind", "999999"));
        test.service.playerQuit("u");
        test.frame("u", "bind", true);
        assertEquals(1, test.presentation.results.size());
        assertFalse(test.state.isAuthorized("u"));
    }

    @Test
    void sessionChangeClearsAuthorizationAndQueuedCommandsCannotEscapeOnReconnect() {
        Fixture test = new Fixture();
        test.ready();
        long firstSession = test.service.status().sessionId();
        test.service.join("u");
        test.scheduler.runUntilIdle();
        assertSent(test, 0, MinecraftProtocol.command("u", "join", "u"));
        test.service.disconnect();
        test.scheduler.runUntilIdle();
        test.frame("u", "join", true);
        assertFalse(test.state.isAuthorized("u"));
        test.service.connect();
        test.scheduler.runUntilIdle();
        test.verifications.get(1).complete(8);
        test.scheduler.runUntilIdle();
        test.transport.opens.get(1).complete(test.transport.connection);
        test.scheduler.runUntilIdle();
        long secondSession = test.service.status().sessionId();
        assertNotEquals(firstSession, secondSession);
        assertFalse(test.state.isAuthorized("u"));
        test.service.rejoinOnlinePlayers(firstSession, List.of("u"));
        assertEquals(1, test.transport.sent.size());
        test.service.rejoinOnlinePlayers(secondSession, List.of("u"));
        test.scheduler.runUntilIdle();
        assertSent(test, 1, MinecraftProtocol.command("u", "join", "u"));
    }

    @Test
    void quitRacingWithOnlineSnapshotDoesNotResurrectPlayer() {
        Fixture test = new Fixture();
        test.ready();
        test.service.playerQuit("u");
        test.service.rejoinOnlinePlayers(test.service.status().sessionId(), List.of("u"));
        test.scheduler.runUntilIdle();
        assertEquals(0, countCommand(test, "join"));
        assertFalse(test.state.isAuthorized("u"));
    }

    @Test
    void abandonedBindResponseUnblocksReturningPlayersJoinWithoutFeedback() {
        Fixture test = new Fixture();
        test.ready();
        test.service.playerJoined("u");
        test.scheduler.runUntilIdle();
        test.frame("u", "join", true);
        assertEquals(CampfireService.BindSubmission.SUBMITTED, test.service.bind("u", "100000"));
        test.completeSend(0);
        assertSent(test, 1, MinecraftProtocol.command("u", "bind", "100000"));
        test.service.playerQuit("u");
        test.service.playerJoined("u");
        assertFalse(test.state.isAuthorized("u"));
        test.frame("u", "bind", true);
        assertTrue(test.presentation.results.isEmpty());
        assertFalse(test.state.isAuthorized("u"));
        test.completeSend(1);
        assertSent(test, 2, MinecraftProtocol.command("u", "join", "u"));
        test.frame("u", "join", true);
        assertTrue(test.state.isAuthorized("u"));
    }

    @Test
    void delayedReadySnapshotCannotUndoExplicitLeave() {
        Fixture test = new Fixture();
        test.ready();
        long session = test.service.status().sessionId();
        test.service.playerJoined("u");
        test.service.leave("u");
        test.service.rejoinOnlinePlayers(session, List.of("u"));
        test.scheduler.runUntilIdle();
        assertEquals(0, countCommand(test, "join"));
        assertFalse(test.state.isAuthorized("u"));
        test.service.join("u");
        test.scheduler.runUntilIdle();
        assertEquals(1, countCommand(test, "join"));
    }

    @Test
    void loginTokenSurvivesBindButNotQuitOrSessionRotation() {
        Fixture test = new Fixture();
        test.ready();
        test.service.playerJoined("u");
        long login = test.state.snapshotLogin("u");
        assertTrue(login > 0);
        assertEquals(CampfireService.BindSubmission.SUBMITTED, test.service.bind("u", "100000"));
        assertTrue(test.state.isCurrentLogin("u", login));
        test.service.playerQuit("u");
        assertFalse(test.state.isCurrentLogin("u", login));
        test.service.playerJoined("u");
        long rejoined = test.state.snapshotLogin("u");
        assertNotEquals(login, rejoined);
        test.service.onStateChanged(new BackendClient.Status(BackendClient.State.CONNECTING, "", test.service.status().sessionId() + 1));
        assertFalse(test.state.isCurrentLogin("u", rejoined));
    }

    @Test
    void olderLeaveResultReleasesSlotAndSendsLaterQuitLeave() {
        Fixture test = new Fixture();
        test.ready();
        test.service.playerJoined("u");
        test.scheduler.runUntilIdle();
        test.frame("u", "join", true);
        test.service.playerQuit("u");
        test.completeSend(0);
        assertSent(test, 1, MinecraftProtocol.command("u", "leave", "u"));
        test.service.playerJoined("u");
        test.scheduler.runUntilIdle();
        test.completeSend(1);
        assertSent(test, 2, MinecraftProtocol.command("u", "join", "u"));
        test.frame("u", "join", true);
        assertTrue(test.state.isAuthorized("u"));
        test.service.playerQuit("u");
        test.scheduler.runUntilIdle();
        assertEquals(3, test.transport.sent.size());
        test.frame("u", "leave", true);
        assertFalse(test.state.isAuthorized("u"));
        test.completeSend(2);
        assertSent(test, 3, MinecraftProtocol.command("u", "leave", "u"));
    }

    @Test
    void quitSendsLeaveOnlyForPlayersOnTheAcceptedListAndReentryAlwaysJoins() {
        Fixture test = new Fixture();
        test.ready();
        test.service.playerQuit("unknown");
        test.scheduler.runUntilIdle();
        assertEquals(0, countCommand(test, "leave"));

        test.service.playerJoined("u");
        test.scheduler.runUntilIdle();
        test.frame("u", "join", false);
        test.service.playerQuit("u");
        test.scheduler.runUntilIdle();
        assertEquals(0, countCommand(test, "leave"));

        test.service.playerJoined("u");
        test.completeSend(0);
        assertSent(test, 1, MinecraftProtocol.command("u", "join", "u"));
        test.frame("u", "join", true);
        test.service.playerQuit("u");
        assertFalse(test.state.isAuthorized("u"));
        test.completeSend(1);
        assertSent(test, 2, MinecraftProtocol.command("u", "leave", "u"));
        test.frame("u", "leave", true);
        test.service.playerJoined("u");
        test.completeSend(2);
        assertSent(test, 3, MinecraftProtocol.command("u", "join", "u"));
        assertFalse(test.state.isAuthorized("u"));
        test.frame("u", "join", true);
        assertTrue(test.state.isAuthorized("u"));
    }

    @Test
    void pendingJoinQuitDoesNotSendLeaveOrAuthorizeFromLateSuccess() {
        Fixture test = new Fixture();
        test.ready();
        test.service.playerJoined("u");
        test.scheduler.runUntilIdle();
        test.service.playerQuit("u");
        test.completeSend(0);
        assertEquals(0, countCommand(test, "leave"));
        test.frame("u", "join", true);
        assertFalse(test.state.isAuthorized("u"));
        test.service.playerJoined("u");
        test.scheduler.runUntilIdle();
        assertEquals(2, countCommand(test, "join"));
    }

    private static int countCommand(Fixture test, String action) {
        // All sends are protobuf; action encoded as a UTF-8 string inside command payload.
        byte[] name = action.getBytes(StandardCharsets.UTF_8);
        int count = 0;
        for (byte[] sent : test.transport.sent) {
            outer: for (int i = 0; i < sent.length - name.length; i++) {
                for (int j = 0; j < name.length; j++) if (sent[i + j] != name[j]) continue outer;
                count++;
                break;
            }
        }
        return count;
    }

    private static void assertSent(Fixture test, int index, byte[] expected) {
        assertTrue(test.transport.sent.size() > index, "missing send " + index);
        assertArrayEquals(expected, test.transport.sent.get(index));
    }

    private static byte[] result(String uuid, String action, Boolean accepted) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        field(bytes, 1, uuid);
        field(bytes, 2, action);
        if (accepted != null) {
            bytes.write(24);
            bytes.write(accepted ? 1 : 0);
        }
        return bytes.toByteArray();
    }

    private static void field(ByteArrayOutputStream bytes, int field, String text) {
        byte[] value = text.getBytes(StandardCharsets.UTF_8);
        bytes.write(field * 8 + 2);
        bytes.write(value.length);
        bytes.writeBytes(value);
    }

    private static final class RecordingPresentation implements BackendEventHandler {
        final List<MinecraftProtocol.CommandResult> results = new ArrayList<>();
        @Override public void onChat(MinecraftProtocol.Chat chat) {}
        @Override public void onCommandResult(MinecraftProtocol.CommandResult result) { results.add(result); }
        @Override public void onStateChanged(BackendClient.Status status) {}
    }

    private static final class Fixture {
        final ManualScheduler scheduler = new ManualScheduler();
        final List<CompletableFuture<Integer>> verifications = new ArrayList<>();
        final FakeTransport transport = new FakeTransport();
        final RecordingPresentation presentation = new RecordingPresentation();
        final PlayerSyncState state = new PlayerSyncState();
        final CampfireService service = new CampfireService(state, presentation, events ->
                new BackendClient(Logger.getLogger("CampfireServiceTest"), () -> {
                    CompletableFuture<Integer> future = new CompletableFuture<>();
                    verifications.add(future);
                    return future;
                }, transport, scheduler, 2, 8, events));

        void ready() {
            service.connect();
            scheduler.runUntilIdle();
            verifications.get(0).complete(9);
            scheduler.runUntilIdle();
            transport.opens.get(0).complete(transport.connection);
            scheduler.runUntilIdle();
            assertEquals(BackendClient.State.READY, service.status().state());
        }

        void completeSend(int index) {
            transport.sends.get(index).complete(null);
            scheduler.runUntilIdle();
        }

        void frame(String uuid, String action, Boolean accepted) {
            transport.listener.onFrame(result(uuid, action, accepted));
            scheduler.runUntilIdle();
        }
    }

    private static final class FakeTransport implements BackendTransport, BackendTransport.Connection {
        final List<CompletableFuture<Connection>> opens = new ArrayList<>();
        final List<byte[]> sent = new ArrayList<>();
        final List<CompletableFuture<Void>> sends = new ArrayList<>();
        final Connection connection = this;
        Listener listener;
        int disconnects;
        int closes;

        @Override public CompletableFuture<Connection> open(int key, Listener callback) {
            listener = callback;
            CompletableFuture<Connection> result = new CompletableFuture<>();
            opens.add(result);
            return result;
        }
        @Override public void start() {}
        @Override public CompletableFuture<Void> send(byte[] bytes) {
            sent.add(bytes.clone());
            CompletableFuture<Void> result = new CompletableFuture<>();
            sends.add(result);
            return result;
        }
        @Override public void disconnect() { disconnects++; }
        @Override public void abort() {}
        @Override public void close() { closes++; }
    }
}
