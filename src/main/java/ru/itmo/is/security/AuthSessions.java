package ru.itmo.is.security;

import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@WebListener
public class AuthSessions implements HttpSessionListener {
    private static final ConcurrentHashMap<String, Token> TOKENS = new ConcurrentHashMap<>();

    private static final Object LIFECYCLE = new Object();
    private static final ReferenceQueue<HttpSession> DESTROYED_QUEUE = new ReferenceQueue<>();
    private static final Set<SessionIdentity> DESTROYED = new HashSet<>();

    public static void register(HttpSession session, String token) {
        Objects.requireNonNull(session);
        Objects.requireNonNull(token);
        if (!authenticated(session)) return;
        Token replacement = new Token(session, token);
        Token previous;
        synchronized (LIFECYCLE) {
            reapDestroyed();

            if (DESTROYED.contains(new SessionIdentity(session, null))) return;
            previous = TOKENS.put(token, replacement);
        }
        if (previous != null) previous.revoke();

        if (!authenticated(session)) revokeIfCurrent(replacement);
    }

    public static boolean valid(String token) {
        return validatedToken(token) != null;
    }

    public static Subscription subscribe(String token, Runnable onRevoked) {
        Objects.requireNonNull(onRevoked);
        Token owner = validatedToken(token);
        if (owner == null) return null;
        synchronized (owner.gate) {
            if (!owner.live()) return null;
            Subscription subscription = new Subscription(owner, onRevoked);
            owner.subscriptions.add(subscription);
            return subscription;
        }
    }

    private static Token validatedToken(String token) {
        if (token == null) return null;
        Token owner = TOKENS.get(token);

        return owner != null && owner.live() && authenticated(owner.session) && owner.live() ? owner : null;
    }

    private static boolean authenticated(HttpSession session) {
        try { return session.getAttribute("user") != null; }
        catch (IllegalStateException ended) { return false; }
    }

    private static void revokeIfCurrent(Token token) {
        synchronized (LIFECYCLE) { TOKENS.remove(token.value, token); }
        token.revoke();
    }

    @Override public void sessionDestroyed(HttpSessionEvent event) {
        HttpSession session = event.getSession();
        List<Token> ended = new ArrayList<>();
        synchronized (LIFECYCLE) {
            reapDestroyed();
            DESTROYED.add(new SessionIdentity(session, DESTROYED_QUEUE));
            TOKENS.forEach((value, owner) -> {
                if (owner.session == session && TOKENS.remove(value, owner)) ended.add(owner);
            });
        }

        ended.forEach(Token::revoke);
    }

    private static void reapDestroyed() {
        SessionIdentity identity;
        while ((identity = (SessionIdentity) DESTROYED_QUEUE.poll()) != null) DESTROYED.remove(identity);
    }

    private static final class SessionIdentity extends WeakReference<HttpSession> {
        private final int hash;
        SessionIdentity(HttpSession session, ReferenceQueue<HttpSession> queue) {
            super(session, queue);
            hash = System.identityHashCode(session);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof SessionIdentity identity && get() != null && get() == identity.get();
        }
    }

    private static final class Token {
        final HttpSession session;
        final String value;
        final Object gate = new Object();
        final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
        volatile boolean active = true;
        Token(HttpSession session, String value) { this.session = session; this.value = value; }
        boolean live() { return active && TOKENS.get(value) == this; }
        void revoke() {
            List<Subscription> closing;
            synchronized (gate) {
                active = false;
                closing = List.copyOf(subscriptions);
                subscriptions.clear();
            }

            for (Subscription subscription : closing) {
                if (subscription.closed.compareAndSet(false, true)) {
                    try { subscription.onRevoked.run(); }
                    catch (RuntimeException ignored) {   }
                }
            }
        }
    }

    public static final class Subscription implements AutoCloseable {
        private final Token owner;
        private final Runnable onRevoked;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Subscription(Token owner, Runnable onRevoked) { this.owner = owner; this.onRevoked = onRevoked; }

        public boolean sendIfActive(Runnable handoff) {
            synchronized (owner.gate) {
                if (closed.get() || !owner.live()) return false;
                handoff.run();
                return true;
            }
        }

        @Override public void close() {
            closed.set(true);
            owner.subscriptions.remove(this);
        }
    }
}
