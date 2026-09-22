package ru.itmo.is.websocket;

import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ServerEndpoint("/ws/organizations")
public class OrganizationWebSocket {
    private static final Set<Session> SESSIONS = ConcurrentHashMap.newKeySet();

    @OnOpen
    public void onOpen(Session session) {
        SESSIONS.add(session);
    }

    @OnClose
    public void onClose(Session session) {
        SESSIONS.remove(session);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        if (session != null) {
            SESSIONS.remove(session);
        }
    }

    public static void broadcast(String type, Integer id) {
        String message = id == null
                ? "{\"type\":\"" + escape(type) + "\"}"
                : "{\"type\":\"" + escape(type) + "\",\"id\":" + id + "}";

        for (Session session : SESSIONS) {
            if (!session.isOpen()) {
                SESSIONS.remove(session);
                continue;
            }
            session.getAsyncRemote().sendText(message, result -> {
                if (!result.isOK()) {
                    SESSIONS.remove(session);
                }
            });
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
