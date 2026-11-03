package ru.itmo.is.security;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

public final class LoginRateLimiter {
    public enum Operation { LOGIN, REGISTER }

    public record Decision(boolean allowed, long retryAfterSeconds) {}

    private record UserKey(Operation operation, String username) {}

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final Decision ALLOWED = new Decision(true, 0);

    private final LongSupplier nanoTime;
    private final long windowNanos;
    private final int usernameLimit, ipLimit, globalLimit, maxUsers, maxIps;
    private final Map<UserKey, Bucket> usernames = new HashMap<>();
    private final Map<String, Bucket> addresses = new HashMap<>();
    private Bucket global;

    public LoginRateLimiter() {
        this(System::nanoTime, Duration.ofMinutes(1), 30, 120, 1000, 4096, 2048);
    }

    LoginRateLimiter(LongSupplier nanoTime, Duration window, int usernameLimit,
                     int ipLimit, int globalLimit, int maxUsers, int maxIps) {
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.windowNanos = Objects.requireNonNull(window).toNanos();
        if (windowNanos <= 0 || usernameLimit <= 0 || ipLimit <= 0 || globalLimit <= 0
                || maxUsers <= 0 || maxIps <= 0) {
            throw new IllegalArgumentException("Rate limiter limits must be positive");
        }
        this.usernameLimit = usernameLimit;
        this.ipLimit = ipLimit;
        this.globalLimit = globalLimit;
        this.maxUsers = maxUsers;
        this.maxIps = maxIps;
        global = new Bucket(nanoTime.getAsLong());
    }

    public static boolean validUsername(String username) {
        return username != null && USERNAME.matcher(username).matches();
    }

    public synchronized Decision allow(Operation operation, String username, String remoteAddr) {
        Objects.requireNonNull(operation);
        if (!validUsername(username)) return new Decision(false, secondsCeiling(windowNanos));
        Decision peer = allowPeer(remoteAddr);
        return peer.allowed() ? allowUsername(operation, username) : peer;
    }

    public synchronized Decision allowPeer(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank() || remoteAddr.length() > 64) {
            return new Decision(false, secondsCeiling(windowNanos));
        }
        long now = nanoTime.getAsLong();
        addresses.values().removeIf(bucket -> expired(bucket, now));
        if (expired(global, now)) global = new Bucket(now);
        Bucket ip = addresses.get(remoteAddr);

        if (ip != null && ip.attempts >= ipLimit) return denied(ip, now);
        if (ip == null && addresses.size() >= maxIps) return saturated(addresses, now);
        if (global.attempts >= globalLimit) return denied(global, now);
        if (ip == null) {

            ip = new Bucket(now);
            addresses.put(remoteAddr, ip);
        }
        global.attempts++;

        ip.attempts++;
        return ALLOWED;
    }

    public synchronized Decision allowUsername(Operation operation, String username) {
        Objects.requireNonNull(operation);
        if (!validUsername(username)) return new Decision(false, secondsCeiling(windowNanos));
        long now = nanoTime.getAsLong();
        usernames.values().removeIf(bucket -> expired(bucket, now));
        UserKey key = new UserKey(operation, username);
        Bucket user = usernames.get(key);
        if (user == null) {
            if (usernames.size() >= maxUsers) return saturated(usernames, now);
            user = new Bucket(now);
            usernames.put(key, user);
        }
        if (user.attempts >= usernameLimit) return denied(user, now);
        user.attempts++;
        return ALLOWED;
    }

    private boolean expired(Bucket bucket, long now) {
        return now - bucket.started >= windowNanos;
    }

    private Decision denied(Bucket bucket, long now) {
        long elapsed = now - bucket.started;

        long remaining = elapsed < 0 ? windowNanos : windowNanos - elapsed;
        return new Decision(false, secondsCeiling(remaining));
    }

    private Decision saturated(Map<?, Bucket> buckets, long now) {
        long remaining = windowNanos;
        for (Bucket bucket : buckets.values()) {
            long elapsed = now - bucket.started;
            if (elapsed >= 0) remaining = Math.min(remaining, windowNanos - elapsed);
        }
        return new Decision(false, secondsCeiling(remaining));
    }

    private static long secondsCeiling(long nanos) {

        return Math.max(1, nanos / NANOS_PER_SECOND + (nanos % NANOS_PER_SECOND == 0 ? 0 : 1));
    }

    synchronized int trackedUsernames() { return usernames.size(); }
    synchronized int trackedAddresses() { return addresses.size(); }

    private static final class Bucket {
        final long started;
        int attempts;
        Bucket(long started) { this.started = started; }
    }
}
