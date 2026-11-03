package ru.itmo.is.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static ru.itmo.is.security.LoginRateLimiter.Operation.*;

class LoginRateLimiterTest {
    private static final long SECOND = 1_000_000_000L;

    static LoginRateLimiter limiter(AtomicLong clock, int users, int ips, int global, int maxUsers, int maxIps) {
        return new LoginRateLimiter(clock::get, Duration.ofMinutes(1), users, ips, global, maxUsers, maxIps);
    }

    @Test void thirtyAttemptsForOneUsernameDoNotBlockAnotherUserAtSameAddress() {
        var clock = new AtomicLong();
        var limiter = limiter(clock, 30, 120, 1000, 4096, 2048);
        for (int i = 0; i < 30; i++) assertTrue(limiter.allow(LOGIN, "alice", "127.0.0.1").allowed());
        assertFalse(limiter.allow(LOGIN, "alice", "127.0.0.1").allowed());
        assertTrue(limiter.allow(LOGIN, "bob", "127.0.0.1").allowed());
        assertEquals(2, limiter.trackedUsernames());
        assertEquals(1, limiter.trackedAddresses());
    }

    @Test void changingAddressCannotResetUsernameQuota() {
        var limiter = limiter(new AtomicLong(), 2, 100, 1000, 100, 100);
        assertTrue(limiter.allow(LOGIN, "alice", "192.0.2.1").allowed());
        assertTrue(limiter.allow(LOGIN, "alice", "192.0.2.2").allowed());
        assertFalse(limiter.allow(LOGIN, "alice", "192.0.2.3").allowed());
        assertTrue(limiter.allow(LOGIN, "bob", "192.0.2.3").allowed());
    }

    @Test void usernameQuotasAreSeparateForLoginAndRegistration() {
        var limiter = limiter(new AtomicLong(), 1, 100, 1000, 100, 100);
        assertTrue(limiter.allow(LOGIN, "alice", "127.0.0.1").allowed());
        assertFalse(limiter.allow(LOGIN, "alice", "127.0.0.1").allowed());
        assertTrue(limiter.allow(REGISTER, "alice", "127.0.0.1").allowed());
        assertFalse(limiter.allow(REGISTER, "alice", "127.0.0.1").allowed());
        assertEquals(2, limiter.trackedUsernames());
    }

    @Test void addressQuotaIsSharedAcrossUsersAndOperations() {
        var limiter = limiter(new AtomicLong(), 100, 3, 1000, 100, 100);
        assertTrue(limiter.allow(LOGIN, "alice", "2001:db8::1").allowed());
        assertTrue(limiter.allow(REGISTER, "bob", "2001:db8::1").allowed());
        assertTrue(limiter.allow(LOGIN, "charlie", "2001:db8::1").allowed());
        assertFalse(limiter.allow(REGISTER, "dave", "2001:db8::1").allowed());
        assertEquals(3, limiter.trackedUsernames(), "IP-denied attempts must not allocate user keys");
        assertTrue(limiter.allow(LOGIN, "dave", "2001:db8::2").allowed());
    }

    @Test void repeatedlyDeniedIpCannotDrainGlobalBudgetForOtherPeers() {
        var limiter = limiter(new AtomicLong(), 100, 2, 3, 100, 100);
        assertTrue(limiter.allowPeer("192.0.2.1").allowed());
        assertTrue(limiter.allowPeer("192.0.2.1").allowed());
        for (int i = 0; i < 1000; i++) assertFalse(limiter.allowPeer("192.0.2.1").allowed());
        assertTrue(limiter.allowPeer("192.0.2.2").allowed(), "Already-denied IP exhausted the global budget");
        assertFalse(limiter.allowPeer("192.0.2.3").allowed(), "The distributed global safety ceiling still applies");
    }

    @Test void globalSafetyCeilingStillBoundsDistributedAttempts() {
        var clock = new AtomicLong();
        var limiter = limiter(clock, 100, 100, 3, 100, 100);
        for (int i = 0; i < 3; i++) assertTrue(limiter.allow(LOGIN, "user" + i, "192.0.2." + i).allowed());
        var denied = limiter.allow(REGISTER, "other", "192.0.2.99");
        assertFalse(denied.allowed());
        assertEquals(60, denied.retryAfterSeconds());
        assertEquals(3, limiter.trackedAddresses(), "Globally denied attempts must not allocate IP keys");
        clock.set(60 * SECOND);
        assertTrue(limiter.allow(REGISTER, "other", "192.0.2.99").allowed());
        assertEquals(1, limiter.trackedUsernames());
        assertEquals(1, limiter.trackedAddresses());
    }

    @Test void exactExpiryAndRetryAfterUseInjectableMonotonicClockWithoutSleeps() {
        var clock = new AtomicLong();
        var limiter = new LoginRateLimiter(clock::get, Duration.ofMillis(1500), 1, 100, 1000, 100, 100);
        assertTrue(limiter.allow(LOGIN, "alice", "127.0.0.1").allowed());
        assertEquals(2, limiter.allow(LOGIN, "alice", "127.0.0.1").retryAfterSeconds());
        clock.set(500_000_000L);
        assertEquals(1, limiter.allow(LOGIN, "alice", "127.0.0.1").retryAfterSeconds());
        clock.set(1_499_999_999L);
        assertFalse(limiter.allow(LOGIN, "alice", "127.0.0.1").allowed());
        clock.set(1_500_000_000L);
        assertTrue(limiter.allow(LOGIN, "alice", "127.0.0.1").allowed());
    }

    @Test void backwardsClockCannotRefreshAnExhaustedBucket() {
        var clock = new AtomicLong(10 * SECOND);
        var limiter = limiter(clock, 1, 100, 1000, 100, 100);
        assertTrue(limiter.allow(LOGIN, "alice", "127.0.0.1").allowed());
        clock.set(9 * SECOND);
        var denied = limiter.allow(LOGIN, "alice", "127.0.0.1");
        assertFalse(denied.allowed());
        assertEquals(60, denied.retryAfterSeconds());
    }

    @Test void userCapacityFailsClosedWithoutEvictingLiveLimits() {
        var limiter = limiter(new AtomicLong(), 1, 100, 1000, 2, 100);
        assertTrue(limiter.allow(LOGIN, "alice", "127.0.0.1").allowed());
        assertTrue(limiter.allow(LOGIN, "bob", "127.0.0.1").allowed());
        var saturated = limiter.allow(LOGIN, "charlie", "127.0.0.1");
        assertFalse(saturated.allowed());
        assertEquals(60, saturated.retryAfterSeconds());
        assertFalse(limiter.allow(LOGIN, "alice", "127.0.0.1").allowed(), "Saturation must not reset an exhausted live key");
        assertEquals(2, limiter.trackedUsernames());
    }

    @Test void ipCapacityFailsClosedWithoutAllocatingOrEvictingLiveKeys() {
        var limiter = limiter(new AtomicLong(), 100, 1, 1000, 100, 2);
        assertTrue(limiter.allow(LOGIN, "alice", "192.0.2.1").allowed());
        assertTrue(limiter.allow(LOGIN, "alice", "192.0.2.2").allowed());
        assertFalse(limiter.allow(LOGIN, "bob", "192.0.2.3").allowed());
        assertFalse(limiter.allow(LOGIN, "alice", "192.0.2.1").allowed());
        assertEquals(2, limiter.trackedAddresses());
        assertEquals(1, limiter.trackedUsernames());
    }

    @Test void expiredUsersFreeCapacityButUnexpiredUsersKeepTheirQuota() {
        var clock = new AtomicLong();
        var limiter = limiter(clock, 1, 100, 1000, 2, 100);
        assertTrue(limiter.allow(LOGIN, "alice", "127.0.0.1").allowed());
        clock.set(10 * SECOND);
        assertTrue(limiter.allow(LOGIN, "bob", "127.0.0.1").allowed());
        clock.set(60 * SECOND);
        assertTrue(limiter.allow(LOGIN, "charlie", "127.0.0.1").allowed());
        assertEquals(2, limiter.trackedUsernames());
        assertFalse(limiter.allow(LOGIN, "bob", "127.0.0.1").allowed());
        assertEquals(10, limiter.allow(LOGIN, "dave", "127.0.0.1").retryAfterSeconds());
    }

    @Test void expiredAddressesFreeCapacityWithoutResettingOtherAddresses() {
        var clock = new AtomicLong();
        var limiter = limiter(clock, 100, 1, 1000, 100, 2);
        assertTrue(limiter.allow(LOGIN, "alice", "192.0.2.1").allowed());
        clock.set(10 * SECOND);
        assertTrue(limiter.allow(LOGIN, "bob", "192.0.2.2").allowed());
        clock.set(60 * SECOND);
        assertTrue(limiter.allow(LOGIN, "charlie", "192.0.2.3").allowed());
        assertEquals(2, limiter.trackedAddresses());
        assertFalse(limiter.allow(LOGIN, "bob", "192.0.2.2").allowed());
    }

    @Test void invalidOrUnboundedKeysNeverAllocateBuckets() {
        var limiter = limiter(new AtomicLong(), 100, 100, 1000, 100, 100);
        for (String user : new String[]{null, "", "x".repeat(65), "with space", "я"}) {
            assertFalse(limiter.allow(LOGIN, user, "127.0.0.1").allowed());
        }
        for (String ip : new String[]{null, "", " ", "x".repeat(65)}) {
            assertFalse(limiter.allow(LOGIN, "alice", ip).allowed());
        }
        assertEquals(0, limiter.trackedUsernames());
        assertEquals(0, limiter.trackedAddresses());
        assertTrue(LoginRateLimiter.validUsername("x".repeat(64)));
        assertFalse(LoginRateLimiter.validUsername("x".repeat(65)));
    }

    @Test @Timeout(10) void concurrentAttemptsCannotOverrunUsernameQuota() throws Exception {
        var limiter = limiter(new AtomicLong(), 25, 1000, 1000, 100, 100);
        assertEquals(25, parallelAttempts(limiter, false));
        assertTrue(limiter.allow(LOGIN, "bob", "127.0.0.1").allowed());
        assertEquals(2, limiter.trackedUsernames());
    }

    @Test @Timeout(10) void concurrentAttemptsCannotOverrunGlobalQuota() throws Exception {
        var limiter = limiter(new AtomicLong(), 1000, 1000, 50, 100, 100);
        assertEquals(50, parallelAttempts(limiter, true));
        assertEquals(50, limiter.trackedUsernames());
        assertEquals(1, limiter.trackedAddresses());
    }

    private static int parallelAttempts(LoginRateLimiter limiter, boolean uniqueUsers) throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        var allowed = new AtomicInteger();
        try {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 32; i++) {
                int task = i;
                futures.add(pool.submit(() -> {
                    try { assertTrue(start.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                    for (int j = 0; j < 10; j++) {
                        String username = uniqueUsers ? "user" + task + "_" + j : "alice";
                        if (limiter.allow(LOGIN, username, "127.0.0.1").allowed()) allowed.incrementAndGet();
                    }
                }));
            }
            start.countDown();
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
            return allowed.get();
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void invalidConfigurationIsRejected() {
        var clock = new AtomicLong();
        assertThrows(IllegalArgumentException.class,
                () -> new LoginRateLimiter(clock::get, Duration.ZERO, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> limiter(clock, 0, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> limiter(clock, 1, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> limiter(clock, 1, 1, 1, 1, 0));
    }
}
