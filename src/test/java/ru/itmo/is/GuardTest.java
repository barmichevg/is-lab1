package ru.itmo.is;

import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import ru.itmo.is.dto.*;
import ru.itmo.is.exception.ApiValidationException;
import ru.itmo.is.model.OrganizationType;
import ru.itmo.is.repository.IdempotencyRepository;
import ru.itmo.is.service.OrganizationCreationGuard;

import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Guard contract tests; PostgreSQL/HTTP tests exercise unique-key waits and rollback. */
class GuardTest {
    static OrganizationResponseDto response() {
        var result = new OrganizationResponseDto();
        result.setId(1); result.setVersion(Long.MAX_VALUE); result.setName("Имя <>&\"");
        result.setCreationDate(LocalDateTime.parse("2026-11-01T23:01:02.123456"));
        result.setAnnualTurnover(Float.MIN_VALUE); result.setEmployeesCount(Long.MAX_VALUE);
        result.setRating(Integer.MAX_VALUE); result.setType(OrganizationType.GOVERNMENT);
        var c = new CoordinatesDto(); c.setId(2); c.setX(Integer.MIN_VALUE); c.setY(Integer.MAX_VALUE);
        result.setCoordinates(c);
        var town = new LocationDto(); town.setId(3); town.setX(Double.MIN_VALUE); town.setY(-1.2345678901234567);
        town.setZ(Integer.MIN_VALUE); town.setName("Город");
        var address = new AddressDto(); address.setId(4); address.setZipCode(""); address.setTown(town);
        result.setOfficialAddress(address);
        return result;
    }

    static OrganizationCreationGuard guard(IdempotencyRepository repository) throws Exception {
        var guard = new OrganizationCreationGuard();
        var field = OrganizationCreationGuard.class.getDeclaredField("repository");
        field.setAccessible(true); field.set(guard, repository);
        return guard;
    }

    @Test void noKeyDoesNotClaimOrRequireAMonitor() throws Exception {
        var result = response();
        assertSame(result, guard(null).createOnce("student", "create", null, "body", () -> result));
        for (var method : OrganizationCreationGuard.class.getDeclaredMethods()) {
            assertFalse(Modifier.isSynchronized(method.getModifiers()));
        }
    }

    @Test void responseIsStoredBeforeReturningAndReplayNeedsNoBusinessCall() throws Exception {
        var count = new AtomicInteger();
        var repository = new IdempotencyRepository() {
            boolean claimed;
            StoredResult saved;
            @Override public boolean claim(String owner, String operation, String key, String hash) {
                assertEquals("student", owner); assertEquals("create", operation); assertEquals("key", key);
                assertTrue(hash.matches("[0-9a-f]{64}"));
                if (claimed) return false;
                claimed = true; saved = new StoredResult(hash, null); return true;
            }
            @Override public StoredResult find(String owner, String operation, String key) { return saved; }
            @Override public void complete(String owner, String operation, String key, String encoded) {
                saved = new StoredResult(saved.fingerprint(), encoded);
            }
        };
        var first = guard(repository).createOnce("student", "create", "key", "body", () -> { count.incrementAndGet(); return response(); });
        // Fresh service instance: the result comes from persistent storage, not an instance field.
        var replay = guard(repository).createOnce("student", "create", "key", "body", () -> { fail("Replay executed the mutation"); return null; });
        assertEquals(OrganizationResponseCodec.encode(first), OrganizationResponseCodec.encode(replay));
        assertEquals(1, count.get());
        assertThrows(ApiValidationException.class, () -> guard(repository).createOnce("student", "create", "key", "changed", GuardTest::response));
    }

    @Test void differentKeysAndRequestsWithoutKeysExecuteConcurrently() throws Exception {
        for (boolean keyed : new boolean[]{true, false}) {
            var entered = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            var completed = new AtomicInteger();
            var repository = new IdempotencyRepository() {
                @Override public boolean claim(String owner, String operation, String key, String hash) { return true; }
                @Override public void complete(String owner, String operation, String key, String encoded) { completed.incrementAndGet(); }
            };
            var guard = guard(repository);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var a = pool.submit(() -> guard.createOnce("student", "create", keyed ? "a" : null, "a", () -> waitingResponse(entered, release)));
                var b = pool.submit(() -> guard.createOnce("student", "create", keyed ? "b" : null, "b", () -> waitingResponse(entered, release)));
                assertTrue(entered.await(3, TimeUnit.SECONDS), "Unrelated request blocked on a global monitor");
                release.countDown(); a.get(3, TimeUnit.SECONDS); b.get(3, TimeUnit.SECONDS);
                assertEquals(keyed ? 2 : 0, completed.get());
            } finally { release.countDown(); pool.shutdownNow(); }
        }
    }

    static OrganizationResponseDto waitingResponse(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Release timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        return response();
    }

    @Test void businessFailureCannotPublishACompletedResult() throws Exception {
        var completed = new AtomicInteger();
        var repository = new IdempotencyRepository() {
            @Override public boolean claim(String owner, String operation, String key, String hash) { return true; }
            @Override public void complete(String owner, String operation, String key, String encoded) { completed.incrementAndGet(); }
        };
        assertThrows(IllegalStateException.class, () -> guard(repository).createOnce("student", "create", "key", "body", () -> { throw new IllegalStateException(); }));
        assertEquals(0, completed.get()); // Actual claim rollback is verified with real JTA/PostgreSQL.
    }

    @Test void invalidKeysAreRejectedBeforeDatabaseAccess() throws Exception {
        for (String key : new String[]{"", "  ", "x".repeat(257)}) {
            assertThrows(ApiValidationException.class, () -> guard(null).createOnce("student", "create", key, "body", GuardTest::response));
        }
    }

    @Test void guardAndRepositoryUseOneRequiredTransaction() throws Exception {
        var method = OrganizationCreationGuard.class.getMethod("createOnce", String.class, String.class,
                String.class, String.class, java.util.function.Supplier.class);
        assertEquals(Transactional.TxType.REQUIRED, method.getAnnotation(Transactional.class).value());
        assertEquals(Transactional.TxType.MANDATORY, IdempotencyRepository.class.getAnnotation(Transactional.class).value());
    }

    @Test void durableCodecPreservesExactNumbersDatesUnicodeAndOptionalAddress() {
        var original = response();
        for (boolean postal : new boolean[]{false, true}) {
            original.setPostalAddress(postal ? original.getOfficialAddress() : null);
            String encoded = OrganizationResponseCodec.encode(original);
            var replay = OrganizationResponseCodec.decode(encoded);
            assertEquals(encoded, OrganizationResponseCodec.encode(replay));
            assertEquals(Long.toString(Long.MAX_VALUE), replay.getEmployeesCount());
            assertEquals(Long.toString(Long.MAX_VALUE), replay.getVersion());
            assertEquals(Float.MIN_VALUE, replay.getAnnualTurnover());
            assertEquals(Double.MIN_VALUE, replay.getOfficialAddress().getTown().getX());
            assertEquals(original.getCreationDate(), replay.getCreationDate());
            assertEquals(original.getName(), replay.getName());
        }
    }
}
