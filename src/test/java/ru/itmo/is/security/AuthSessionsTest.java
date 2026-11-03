package ru.itmo.is.security;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class AuthSessionsTest {
    final List<Owner> owners = new ArrayList<>();
    static final class Owner {
        final String token = UUID.randomUUID().toString();
        volatile boolean authenticated = true;
        volatile Runnable attributeHook = () -> {};
        final HttpSession session = (HttpSession) Proxy.newProxyInstance(HttpSession.class.getClassLoader(),
                new Class<?>[]{HttpSession.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getAttribute") && args[0].equals("user")) {
                        String observed = authenticated ? "owner" : null;
                        attributeHook.run();
                        return observed;
                    }
                    // Registry keys must use identity, not container equals/hashCode.
                    throw new AssertionError("Unexpected Servlet call: " + method.getName());
                });
    }
    Owner owner() {
        Owner owner = new Owner(); owners.add(owner); AuthSessions.register(owner.session, owner.token); return owner;
    }
    static void destroy(Owner owner) { new AuthSessions().sessionDestroyed(new HttpSessionEvent(owner.session)); }
    @AfterEach void cleanup() {
        for (Owner owner : owners) { owner.attributeHook = () -> {}; destroy(owner); assertFalse(AuthSessions.valid(owner.token)); }
    }
    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Latch timeout"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    @Test void registerCannotResurrectSessionDestroyedWhileServletAttributeAccessWasInFlight() throws Exception {
        Owner owner = new Owner(); owners.add(owner);
        var observed = new CountDownLatch(1); var release = new CountDownLatch(1); var pool = Executors.newSingleThreadExecutor();
        owner.attributeHook = () -> { observed.countDown(); await(release); };
        try {
            var register = pool.submit(() -> AuthSessions.register(owner.session, owner.token)); await(observed);
            // The proxy intentionally still returns its old "owner" observation.
            // A second getAttribute alone cannot fix this publication/lifecycle race.
            destroy(owner); release.countDown(); register.get(5, TimeUnit.SECONDS);
            assertFalse(AuthSessions.valid(owner.token));
            assertNull(AuthSessions.subscribe(owner.token, () -> fail("Never subscribed")));
        } finally {
            release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void revokedTokenRejectsNewAndExistingSubscriptionsAndIsolatedCallbacksKeepRunning() {
        Owner owner = owner(); var closed = new AtomicInteger(); var sent = new AtomicInteger();
        var first = AuthSessions.subscribe(owner.token, closed::incrementAndGet);
        var broken = AuthSessions.subscribe(owner.token, () -> { throw new IllegalStateException("Broken close"); });
        var last = AuthSessions.subscribe(owner.token, closed::incrementAndGet);
        assertNotNull(first); assertNotNull(broken); assertNotNull(last);
        assertTrue(first.sendIfActive(sent::incrementAndGet));
        assertDoesNotThrow(() -> destroy(owner)); assertEquals(2, closed.get());
        assertFalse(first.sendIfActive(sent::incrementAndGet)); assertFalse(last.sendIfActive(sent::incrementAndGet));
        assertNull(AuthSessions.subscribe(owner.token, () -> fail("Cannot subscribe after revoke")));
        assertEquals(1, sent.get()); destroy(owner); assertEquals(2, closed.get());
    }

    @Test void closingASubscriptionDoesNotRevokeOwnerOrInvokeItsTransportCallback() {
        Owner owner = owner(); var closed = new AtomicInteger(); var subscription = AuthSessions.subscribe(owner.token, closed::incrementAndGet);
        assertNotNull(subscription); subscription.close(); subscription.close();
        assertTrue(AuthSessions.valid(owner.token)); assertEquals(0, closed.get());
        assertFalse(subscription.sendIfActive(() -> fail("Closed subscription cannot send")));
        destroy(owner); assertEquals(0, closed.get());
    }

    @Test void replacingTokenRevokesOldSubscriptionWithoutDestroyingNewOwner() {
        Owner old = owner(), replacement = owner(); var closed = new AtomicInteger();
        var previous = AuthSessions.subscribe(old.token, closed::incrementAndGet); assertNotNull(previous);
        AuthSessions.register(replacement.session, old.token);
        assertEquals(1, closed.get()); assertFalse(previous.sendIfActive(() -> fail("Old binding cannot send")));
        assertTrue(AuthSessions.valid(old.token));
        destroy(old); assertTrue(AuthSessions.valid(old.token), "Destroying replaced owner must not revoke new owner");
        destroy(replacement); assertFalse(AuthSessions.valid(old.token));
    }

    @Test void revocationCallbacksRunOutsideBothLifecycleAndTokenGates() throws Exception {
        Owner owner = owner(); var pool = Executors.newSingleThreadExecutor();
        var reference = new java.util.concurrent.atomic.AtomicReference<AuthSessions.Subscription>();
        Owner another = new Owner(); owners.add(another);
        var subscription = AuthSessions.subscribe(owner.token, () -> {
            try {
                pool.submit(() -> {
                    AuthSessions.register(another.session, another.token);
                    assertTrue(AuthSessions.valid(another.token));
                    assertFalse(reference.get().sendIfActive(() -> fail("Revoked subscription")));
                }).get(5, TimeUnit.SECONDS);
            } catch (Exception failure) { throw new AssertionError("A close callback ran under a registry/send gate", failure); }
        });
        assertNotNull(subscription); reference.set(subscription);
        try { destroy(owner); }
        finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test void missingUnauthenticatedAndDestroyedSessionsCannotAcquireSubscriptions() {
        assertFalse(AuthSessions.valid(null)); assertNull(AuthSessions.subscribe("not-present", () -> fail("Unknown token")));
        Owner owner = new Owner(); owners.add(owner); owner.authenticated = false;
        AuthSessions.register(owner.session, owner.token); assertFalse(AuthSessions.valid(owner.token));
        owner.authenticated = true; destroy(owner); AuthSessions.register(owner.session, owner.token);
        assertFalse(AuthSessions.valid(owner.token), "Late registration cannot revive the same destroyed identity");
    }
}
