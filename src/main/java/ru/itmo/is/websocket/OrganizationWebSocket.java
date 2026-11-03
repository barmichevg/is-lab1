package ru.itmo.is.websocket;

import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import ru.itmo.is.security.AuthSessions;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@ServerEndpoint("/ws/organizations")
public class OrganizationWebSocket {
    private static final ConcurrentHashMap<Session, Client> CLIENTS = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session) {
        Client client = null;
        try {
            List<String> parameters = session.getRequestParameterMap().get("token");
            String token = parameters == null || parameters.isEmpty() ? "" : parameters.get(0);
            client = new Client(session, token);
            CLIENTS.put(session, client);
            Client opening = client;

            AuthSessions.Subscription subscription = AuthSessions.subscribe(token, () -> closeRevoked(opening));
            client.subscription = subscription;
            if (subscription == null) closeRevoked(client);
            else if (client.closed.get()) subscription.close();
            else if (!session.isOpen()) unsubscribe(client);
        } catch (RuntimeException failure) {
            if (client != null) closeFailedSession(client);
            else closeTransport(session, false);
        }
    }

    @OnClose
    public void onClose(Session session) {
        Client client = CLIENTS.get(session);
        if (client != null) unsubscribe(client);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        if (session != null) {
            Client client = CLIENTS.get(session);
            if (client != null) closeFailedSession(client);
        }
    }

    private static final class Client {
        final Session session;
        final String token;
        final AtomicBoolean closed = new AtomicBoolean();
        volatile AuthSessions.Subscription subscription;
        Client(Session session, String token) { this.session = session; this.token = token; }
    }

    private static boolean unsubscribe(Client client) {
        boolean first = client.closed.compareAndSet(false, true);
        CLIENTS.remove(client.session, client);
        AuthSessions.Subscription subscription = client.subscription;
        if (subscription != null) subscription.close();
        return first;
    }

    private static void closeFailedSession(Client client) {
        if (unsubscribe(client)) closeTransport(client.session, false);
    }

    private static void closeRevoked(Client client) {
        if (unsubscribe(client)) closeTransport(client.session, true);
    }

    private static void closeTransport(Session session, boolean revoked) {
        try {
            session.close(new jakarta.websocket.CloseReason(
                    revoked ? jakarta.websocket.CloseReason.CloseCodes.VIOLATED_POLICY
                            : jakarta.websocket.CloseReason.CloseCodes.UNEXPECTED_CONDITION,
                    revoked ? "Session ended" : "Synchronization failed; reconnect"));
        } catch (java.io.IOException | RuntimeException ignored) {

        }
    }

    public static void closeToken(String token) {
        if (token == null) return;
        for (Client client : CLIENTS.values()) if (token.equals(client.token)) closeRevoked(client);
    }

    public static void broadcast(String type, Integer id) {
        String message = id == null
                ? "{\"type\":\"" + escape(type) + "\"}"
                : "{\"type\":\"" + escape(type) + "\",\"id\":" + id + "}";

        for (Client client : CLIENTS.values()) {
            AuthSessions.Subscription subscription = client.subscription;
            if (subscription == null || client.closed.get()) continue;
            AtomicBoolean handoffEnded = new AtomicBoolean();
            AtomicBoolean failed = new AtomicBoolean();
            AtomicBoolean transportClosed = new AtomicBoolean();
            try {
                boolean admitted = subscription.sendIfActive(() -> {
                    if (!client.session.isOpen()) { transportClosed.set(true); return; }
                    client.session.getAsyncRemote().sendText(message, result -> {
                        if (!result.isOK()) {
                            failed.set(true);

                            if (handoffEnded.get()) closeFailedSession(client);
                        }
                    });
                });
                handoffEnded.set(true);
                if (!admitted) closeRevoked(client);
                else if (transportClosed.get()) unsubscribe(client);
                else if (failed.get()) closeFailedSession(client);
            } catch (RuntimeException failure) {
                handoffEnded.set(true);
                closeFailedSession(client);
            }
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
