package ru.itmo.is.websocket;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.websocket.CloseReason;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import jakarta.websocket.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.itmo.is.security.AuthSessions;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class OrganizationWebSocketTest {
    final OrganizationWebSocket endpoint = new OrganizationWebSocket();
    final List<Client> clients = new ArrayList<>();

    static class Client {
        final String token;
        final List<String> messages = new CopyOnWriteArrayList<>();
        volatile boolean open = true, authenticated = true, asyncFailure, syncFailure, closeFailure, runtimeCloseFailure, deferCallback;
        volatile Runnable attributeHook = () -> {}, remoteHook = () -> {}, sendHook = () -> {};
        final AtomicInteger sends = new AtomicInteger(), closes = new AtomicInteger();
        volatile CloseReason closeReason;
        volatile SendHandler deferred;
        final HttpSession http;
        final RemoteEndpoint.Async remote;
        final Session session;

        Client() { this(null); }
        Client(Client sameAuthentication) {
            token = sameAuthentication == null ? UUID.randomUUID().toString() : sameAuthentication.token;
            http = sameAuthentication == null ? (HttpSession) Proxy.newProxyInstance(HttpSession.class.getClassLoader(),
                    new Class<?>[]{HttpSession.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getAttribute") && args[0].equals("user")) {
                            String observed = authenticated ? "test-user" : null;
                            attributeHook.run(); // May return a pre-revocation observation after the listener finishes.
                            return observed;
                        }
                        throw new AssertionError("Unexpected HTTP session call: " + method.getName());
                    }) : sameAuthentication.http;
            remote = (RemoteEndpoint.Async) Proxy.newProxyInstance(
                    RemoteEndpoint.Async.class.getClassLoader(), new Class<?>[]{RemoteEndpoint.Async.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("sendText") && args.length == 2) {
                            sends.incrementAndGet();
                            sendHook.run();
                            if (syncFailure) throw new IllegalStateException("Send failed synchronously");
                            messages.add((String) args[0]);
                            if (deferCallback) deferred = (SendHandler) args[1];
                            else ((SendHandler) args[1]).onResult(asyncFailure
                                    ? new SendResult(new IOException("Asynchronous send failed")) : new SendResult());
                            return null;
                        }
                        throw new AssertionError("Unexpected remote call: " + method.getName());
                    });
            session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(),
                    new Class<?>[]{Session.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals": return proxy == args[0];
                            case "getRequestParameterMap": return Map.of("token", List.of(token));
                            case "isOpen": return open;
                            case "getAsyncRemote": remoteHook.run(); return remote;
                            case "close":
                                closes.incrementAndGet(); closeReason = (CloseReason) args[0];
                                if (runtimeCloseFailure) throw new IllegalStateException("Close failed at runtime");
                                if (closeFailure) throw new IOException("Close failed");
                                open = false;
                                return null;
                            default: throw new AssertionError("Unexpected websocket call: " + method.getName());
                        }
                    });
        }
    }

    Client register() {
        var client = new Client(); clients.add(client);
        AuthSessions.register(client.http, client.token);
        return client;
    }
    Client connect() {
        var client = register(); endpoint.onOpen(client.session); return client;
    }
    Client connectSameToken(Client owner) {
        var client = new Client(owner); clients.add(client); endpoint.onOpen(client.session); return client;
    }
    void revoke(Client client) { new AuthSessions().sessionDestroyed(new HttpSessionEvent(client.http)); }

    @AfterEach void cleanup() {
        for (Client client : clients) {
            client.attributeHook = () -> {}; client.remoteHook = () -> {}; client.sendHook = () -> {};
            endpoint.onClose(client.session); revoke(client); assertFalse(AuthSessions.valid(client.token));
        }
    }

    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Latch timeout"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    static void shutdown(ExecutorService pool) throws InterruptedException {
        pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
    static void assertRetryableClose(Client client) {
        assertNotNull(client.closeReason); assertEquals(1011, client.closeReason.getCloseCode().getCode());
    }
    static void assertRevokedClose(Client client) {
        assertNotNull(client.closeReason); assertEquals(1008, client.closeReason.getCloseCode().getCode());
    }

    @Test void asynchronousFailureClosesAndUnsubscribesButKeepsOtherClients() {
        var broken = connect(); broken.asyncFailure = true;
        var healthy = connect(); OrganizationWebSocket.broadcast("UPDATED", 7);
        assertRetryableClose(broken); assertFalse(broken.open);
        assertEquals(List.of("{\"type\":\"UPDATED\",\"id\":7}"), healthy.messages);
        OrganizationWebSocket.broadcast("REFRESH", null);
        assertEquals(1, broken.sends.get()); assertEquals(2, healthy.sends.get()); assertNull(healthy.closeReason);
    }

    @Test void synchronousSendAndCloseFailuresCannotInterruptOtherClients() {
        var firstHealthy = connect(); var broken = connect(); broken.syncFailure = true; broken.closeFailure = true;
        var secondHealthy = connect();
        assertDoesNotThrow(() -> OrganizationWebSocket.broadcast("CREATED", 8));
        assertRetryableClose(broken); assertTrue(broken.open);
        assertEquals(1, firstHealthy.sends.get()); assertEquals(1, secondHealthy.sends.get());
        assertDoesNotThrow(() -> OrganizationWebSocket.broadcast("DELETED", 8));
        assertEquals(1, broken.sends.get()); assertEquals(2, firstHealthy.sends.get()); assertEquals(2, secondHealthy.sends.get());
    }

    @Test void endpointErrorClosesSessionToTriggerReconnect() {
        var broken = connect(); endpoint.onError(broken.session, new IOException("Transport error"));
        assertRetryableClose(broken); assertFalse(broken.open); OrganizationWebSocket.broadcast("REFRESH", null);
        assertEquals(0, broken.sends.get()); assertDoesNotThrow(() -> endpoint.onError(null, new IOException("No session")));
    }

    @Test void failedAsynchronousSendStillUnsubscribesWhenCloseFails() {
        var broken = connect(); broken.asyncFailure = true; broken.closeFailure = true;
        assertDoesNotThrow(() -> OrganizationWebSocket.broadcast("REFRESH", null)); assertRetryableClose(broken);
        OrganizationWebSocket.broadcast("REFRESH", null); assertEquals(1, broken.sends.get());
    }

    @Test void normalDeliveryAndRepeatedAuthenticationChecksDoNotDisconnectAnyone() {
        var client = connect();
        for (int i = 0; i < 10; i++) assertTrue(AuthSessions.valid(client.token));
        OrganizationWebSocket.broadcast("CREATED", 42); OrganizationWebSocket.broadcast("REFRESH", null);
        assertEquals(List.of("{\"type\":\"CREATED\",\"id\":42}", "{\"type\":\"REFRESH\"}"), client.messages);
        assertTrue(client.open); assertEquals(0, client.closes.get());
        revoke(client); assertFalse(AuthSessions.valid(client.token)); assertRevokedClose(client);
        OrganizationWebSocket.broadcast("DELETED", 42); assertEquals(2, client.sends.get());
    }

    @Test void logoutBetweenObservedValidSessionAndSubscriptionCannotAddARevokedSocket() throws Exception {
        var client = register(); var observed = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        client.attributeHook = () -> { observed.countDown(); await(release); };
        try {
            var opening = pool.submit(() -> endpoint.onOpen(client.session)); await(observed);
            // Listener must finish even while the HTTP getAttribute call is still
            // waiting. Holding a registry gate around that call would deadlock.
            pool.submit(() -> revoke(client)).get(5, TimeUnit.SECONDS);
            assertFalse(AuthSessions.valid(client.token));
            OrganizationWebSocket.broadcast("CREATED", 1); assertTrue(client.messages.isEmpty());
            release.countDown(); opening.get(5, TimeUnit.SECONDS);
            assertRevokedClose(client); assertFalse(client.open);
            OrganizationWebSocket.broadcast("CREATED", 2); assertEquals(0, client.sends.get());
        } finally { release.countDown(); shutdown(pool); }
    }

    @Test void revokeDuringAsyncHandoffWaitsForAdmissionButNotForCompletionCallback() throws Exception {
        var client = connect(); client.deferCallback = true;
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var listenerStarted = new CountDownLatch(1);
        var order = new CopyOnWriteArrayList<String>(); var pool = Executors.newFixedThreadPool(2);
        client.sendHook = () -> { entered.countDown(); await(release); order.add("handoff"); };
        try {
            var sending = pool.submit(() -> OrganizationWebSocket.broadcast("UPDATED", 7)); await(entered);
            var revoke = pool.submit(() -> {
                listenerStarted.countDown(); revoke(client); order.add("revoke-returned");
            });
            await(listenerStarted);
            assertFalse(revoke.isDone(), "A started transport handoff must finish before revoke returns");
            release.countDown(); sending.get(5, TimeUnit.SECONDS); revoke.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("handoff", "revoke-returned"), order);
            assertNotNull(client.deferred, "Send completion deliberately has not arrived yet");
            assertRevokedClose(client); assertFalse(AuthSessions.valid(client.token));
            OrganizationWebSocket.broadcast("DELETED", 7); assertEquals(1, client.sends.get());
            client.deferred.onResult(new SendResult(new IOException("Late failure after logout")));
            assertEquals(1, client.closes.get(), "Late completion cannot re-close/re-register a revoked socket");
        } finally { release.countDown(); shutdown(pool); }
    }

    @Test void blockedTokenHandoffDoesNotHoldGlobalRegistryOrOtherTokenGates() throws Exception {
        var blocked = connect(); var healthy = connect();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(3);
        blocked.sendHook = () -> { entered.countDown(); await(release); };
        try {
            var sending = pool.submit(() -> OrganizationWebSocket.broadcast("UPDATED", 1)); await(entered);
            pool.submit(() -> {
                assertTrue(AuthSessions.valid(healthy.token));
                var subscription = AuthSessions.subscribe(healthy.token, () -> {});
                assertNotNull(subscription);
                try { assertTrue(subscription.sendIfActive(() -> healthy.messages.add("independent-handoff"))); }
                finally { subscription.close(); }
                revoke(healthy);
            }).get(5, TimeUnit.SECONDS);
            assertRevokedClose(healthy); assertTrue(healthy.messages.contains("independent-handoff"));
            release.countDown(); sending.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); shutdown(pool); }
    }

    @Test void revokeClosesAllSameTokenSubscriptionsEvenWhenOneCloseThrowsRuntime() {
        var first = connect(); var broken = connectSameToken(first); broken.runtimeCloseFailure = true;
        var last = connectSameToken(first); var otherOwner = connect();
        assertDoesNotThrow(() -> revoke(first));
        assertRevokedClose(first); assertRevokedClose(broken); assertRevokedClose(last);
        assertEquals(1, broken.closes.get()); assertFalse(AuthSessions.valid(first.token));
        OrganizationWebSocket.broadcast("CREATED", 9);
        assertEquals(0, first.sends.get()); assertEquals(0, broken.sends.get()); assertEquals(0, last.sends.get());
        assertEquals(List.of("{\"type\":\"CREATED\",\"id\":9}"), otherOwner.messages);
    }

    @Test void socketCloseDoesNotInvalidateHttpAuthenticationAndTokenCanReconnect() {
        var first = connect(); endpoint.onClose(first.session); assertTrue(AuthSessions.valid(first.token));
        var reconnect = connectSameToken(first); OrganizationWebSocket.broadcast("UPDATED", 5);
        assertEquals(0, first.sends.get()); assertEquals(1, reconnect.sends.get()); assertTrue(reconnect.open);
    }
}
