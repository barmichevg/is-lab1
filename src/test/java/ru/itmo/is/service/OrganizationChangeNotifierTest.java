package ru.itmo.is.service;

import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrganizationChangeNotifierTest {
    static class RecordingNotifier extends OrganizationChangeNotifier {
        final List<String> events = new ArrayList<>();
        @Override void broadcast(String type, Integer id) { events.add(type + ":" + id); }
    }

    static class Registry {
        int status = Status.STATUS_ACTIVE;
        int registrations;
        boolean statusFails, registrationFails;
        Synchronization callback;
        final TransactionSynchronizationRegistry proxy = (TransactionSynchronizationRegistry) Proxy.newProxyInstance(
                TransactionSynchronizationRegistry.class.getClassLoader(),
                new Class<?>[]{TransactionSynchronizationRegistry.class}, (object, method, args) -> {
                    switch (method.getName()) {
                        case "getTransactionStatus":
                            if (statusFails) throw new IllegalStateException("Status unavailable");
                            return status;
                        case "registerInterposedSynchronization":
                            registrations++;
                            if (registrationFails) throw new IllegalStateException("Registration failed");
                            callback = (Synchronization) args[0];
                            return null;
                        default: throw new AssertionError("Unexpected registry call: " + method.getName());
                    }
                });
        RecordingNotifier notifier() {
            var notifier = new RecordingNotifier();
            notifier.transactionRegistry = proxy;
            return notifier;
        }
    }

    @Test void activeTransactionPublishesOnlyAfterCommittedCompletion() {
        var registry = new Registry();
        var notifier = registry.notifier();
        notifier.afterCommit("UPDATED", 42);
        assertEquals(1, registry.registrations);
        assertTrue(notifier.events.isEmpty());
        registry.callback.beforeCompletion();
        assertTrue(notifier.events.isEmpty());
        registry.callback.afterCompletion(Status.STATUS_COMMITTED);
        assertEquals(List.of("UPDATED:42"), notifier.events);
    }

    @Test void rolledBackAndUnknownCompletionDoNotPublish() {
        for (int completion : new int[]{Status.STATUS_ROLLEDBACK, Status.STATUS_UNKNOWN}) {
            var registry = new Registry();
            var notifier = registry.notifier();
            notifier.afterCommit("CREATED", 42);
            registry.callback.afterCompletion(completion);
            assertTrue(notifier.events.isEmpty());
        }
    }

    @Test void registrationFailureNeverFallsBackToImmediateBroadcast() {
        var registry = new Registry();
        registry.registrationFails = true;
        var notifier = registry.notifier();
        assertDoesNotThrow(() -> notifier.afterCommit("CREATED", 42));
        assertEquals(1, registry.registrations);
        assertNull(registry.callback);
        assertTrue(notifier.events.isEmpty());
    }

    @Test void statusFailureNeverFallsBackToImmediateBroadcast() {
        var registry = new Registry();
        registry.statusFails = true;
        var notifier = registry.notifier();
        assertDoesNotThrow(() -> notifier.afterCommit("DELETED", 42));
        assertEquals(0, registry.registrations);
        assertTrue(notifier.events.isEmpty());
    }

    @Test void onlyNoTransactionStatusAllowsImmediateBroadcast() {
        for (int status : new int[]{Status.STATUS_MARKED_ROLLBACK, Status.STATUS_PREPARING,
                Status.STATUS_PREPARED, Status.STATUS_COMMITTING, Status.STATUS_ROLLING_BACK,
                Status.STATUS_ROLLEDBACK, Status.STATUS_UNKNOWN, Status.STATUS_COMMITTED}) {
            var registry = new Registry();
            registry.status = status;
            var notifier = registry.notifier();
            notifier.afterCommit("REFRESH", null);
            assertEquals(0, registry.registrations);
            assertTrue(notifier.events.isEmpty(), "Unexpected broadcast for status " + status);
        }
        var registry = new Registry();
        registry.status = Status.STATUS_NO_TRANSACTION;
        var notifier = registry.notifier();
        notifier.afterCommit("REFRESH", null);
        assertEquals(List.of("REFRESH:null"), notifier.events);
    }
}
