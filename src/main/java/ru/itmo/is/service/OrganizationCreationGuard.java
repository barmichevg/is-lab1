package ru.itmo.is.service;

import jakarta.enterprise.context.ApplicationScoped;
import ru.itmo.is.dto.OrganizationResponseDto;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Prevents two almost simultaneous POST requests produced by the same UI
 * submission from creating two organizations.
 *
 * The browser sends one idempotency key for the whole lifetime of the
 * "create organization" dialog. Requests with the same key are serialized
 * and, after the first successful transaction, receive the same result.
 */
@ApplicationScoped
public class OrganizationCreationGuard {
    private static final Duration RESULT_TTL = Duration.ofMinutes(10);

    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, CachedResult> completed = new ConcurrentHashMap<>();

    public OrganizationResponseDto createOnce(String key, Supplier<OrganizationResponseDto> creator) {
        if (key == null || key.isBlank()) {
            return creator.get();
        }

        String normalizedKey = key.trim();
        cleanupExpired();

        Object lock = locks.computeIfAbsent(normalizedKey, ignored -> new Object());
        try {
            synchronized (lock) {
                CachedResult cached = completed.get(normalizedKey);
                if (cached != null && !cached.isExpired()) {
                    return cached.value();
                }

                OrganizationResponseDto created = creator.get();
                completed.put(normalizedKey, new CachedResult(created, Instant.now()));
                return created;
            }
        } finally {
            locks.remove(normalizedKey, lock);
        }
    }

    private void cleanupExpired() {
        completed.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private record CachedResult(OrganizationResponseDto value, Instant createdAt) {
        private boolean isExpired() {
            return createdAt.plus(RESULT_TTL).isBefore(Instant.now());
        }
    }
}
