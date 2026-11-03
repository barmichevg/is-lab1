package ru.itmo.is.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static ru.itmo.is.security.LoginRateLimiter.Operation.*;

/** No init/account store is installed: rejected requests must never reach auth hashing/IO. */
class AuthServletRateLimitTest {
    static class Exchange {
        int status = 200;
        int inputAccesses;
        final Map<String, String> headers = new HashMap<>();
        final List<String> requestHeadersRead = new ArrayList<>();
        final StringWriter body = new StringWriter();
        final HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setStatus": status = (int) args[0]; return null;
                        case "setHeader": headers.put((String) args[0], (String) args[1]); return null;
                        case "setContentType": return null;
                        case "getWriter": return new PrintWriter(body);
                        default: throw new AssertionError("Unexpected response call: " + method.getName());
                    }
                });
        final HttpServletRequest request;

        Exchange(String route, String contentType, byte[] bytes, String remoteAddr, String forwardedFor) {
            var input = new ByteArrayInputStream(bytes);
            var stream = new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] target, int offset, int length) { return input.read(target, offset, length); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
            request = (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                    new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getPathInfo": return route;
                            case "getContentType": return contentType;
                            case "getInputStream": inputAccesses++; return stream;
                            case "getRemoteAddr": return remoteAddr;
                            case "getHeader": requestHeadersRead.add((String) args[0]); return forwardedFor;
                            default: throw new AssertionError("Unexpected request call: " + method.getName());
                        }
                    });
        }
    }

    static Exchange request(String route, String body, String remoteAddr, String forwardedFor) {
        return new Exchange(route, "application/json", body.getBytes(StandardCharsets.UTF_8), remoteAddr, forwardedFor);
    }

    static LoginRateLimiter limiter(AtomicLong clock, int usernameLimit, int ipLimit) {
        return new LoginRateLimiter(clock::get, Duration.ofMinutes(1), usernameLimit, ipLimit, 1000, 100, 100);
    }

    @Test void exhaustedActualAddressCannotBeBypassedWithForwardedHeader() throws Exception {
        var limiter = limiter(new AtomicLong(), 100, 2);
        assertTrue(limiter.allow(LOGIN, "alice", "127.0.0.1").allowed());
        assertTrue(limiter.allow(REGISTER, "bob", "127.0.0.1").allowed());
        var servlet = new AuthServlet(limiter);
        var exchange = request("/login", "{\"username\":\"charlie\",\"password\":\"wrong\"}", "127.0.0.1", "203.0.113.99");
        servlet.doPost(exchange.request, exchange.response);
        assertEquals(429, exchange.status);
        assertEquals("60", exchange.headers.get("Retry-After"));
        assertFalse(exchange.requestHeadersRead.contains("X-Forwarded-For"));
        assertEquals(2, limiter.trackedUsernames());
        assertEquals(1, limiter.trackedAddresses());
    }

    @Test void retryAfterRoundsUpAndEmptyPasswordPolicyIsNotChanged() throws Exception {
        var clock = new AtomicLong();
        var limiter = limiter(clock, 1, 100);
        assertTrue(limiter.allow(REGISTER, "alice", "127.0.0.1").allowed());
        clock.set(59_100_000_000L);
        var exchange = request("/register", "{\"username\":\"alice\",\"password\":\"\"}", "127.0.0.1", null);
        new AuthServlet(limiter).doPost(exchange.request, exchange.response);
        // Empty is a valid password string, so this request reaches the limiter,
        // not a new password policy. It must still stop before hashing/register IO.
        assertEquals(429, exchange.status);
        assertEquals("1", exchange.headers.get("Retry-After"));
    }

    @Test void invalidUsernameBodyAndPasswordShapeDoNotAllocateRateKeys() throws Exception {
        var limiter = limiter(new AtomicLong(), 1, 100);
        var servlet = new AuthServlet(limiter);
        for (String body : new String[]{"{", "{}", "{\"username\":\"" + "x".repeat(65) + "\",\"password\":\"x\"}",
                "{\"username\":\"with space\",\"password\":\"x\"}", "{\"username\":\"alice\",\"password\":1}"}) {
            var exchange = request("/login", body, "127.0.0.1", null);
            servlet.doPost(exchange.request, exchange.response);
            assertEquals(400, exchange.status, body);
        }
        assertEquals(0, limiter.trackedUsernames());
        assertEquals(1, limiter.trackedAddresses(), "Invalid bodies still consume the early peer/global allowance");
    }

    @Test void requestLimitIsMeasuredInBytesBeforeRateAllocation() throws Exception {
        var limiter = limiter(new AtomicLong(), 1, 100);
        String json = "{\"username\":\"alice\",\"password\":\"" + "я".repeat(33000) + "\"}";
        assertTrue(json.length() < 65536, "A character count would miss this oversized UTF-8 request");
        var exchange = request("/register", json, "127.0.0.1", null);
        new AuthServlet(limiter).doPost(exchange.request, exchange.response);
        assertEquals(413, exchange.status);
        assertEquals(0, limiter.trackedUsernames());
        assertEquals(1, limiter.trackedAddresses(), "Invalid bodies still consume the early peer/global allowance");
    }

    @Test void malformedFloodIsDeniedBeforeReadingAnotherBody() throws Exception {
        var limiter = limiter(new AtomicLong(), 30, 2);
        var servlet = new AuthServlet(limiter);
        for (int i = 0; i < 2; i++) {
            var malformed = request("/login", "{", "127.0.0.1", null);
            servlet.doPost(malformed.request, malformed.response);
            assertEquals(400, malformed.status);
        }
        var denied = request("/login", "{", "127.0.0.1", null);
        servlet.doPost(denied.request, denied.response);
        assertEquals(429, denied.status);
        assertEquals(0, denied.inputAccesses, "Peer limit must run before getInputStream/JSON parsing");
        assertEquals("60", denied.headers.get("Retry-After"));
        assertEquals(0, limiter.trackedUsernames());
    }

    @Test void malformedRequestsAcrossAddressesStillConsumeGlobalSafetyCeiling() throws Exception {
        var limiter = new LoginRateLimiter(new AtomicLong()::get, Duration.ofMinutes(1), 30, 120, 2, 100, 100);
        var servlet = new AuthServlet(limiter);
        for (int i = 0; i < 2; i++) {
            var malformed = request("/register", "{", "192.0.2." + i, null);
            servlet.doPost(malformed.request, malformed.response);
            assertEquals(400, malformed.status);
        }
        var denied = request("/login", "{", "192.0.2.99", null);
        servlet.doPost(denied.request, denied.response);
        assertEquals(429, denied.status);
        assertEquals(0, denied.inputAccesses);
        assertEquals(2, limiter.trackedAddresses());
        assertEquals(0, limiter.trackedUsernames());
    }

    @Test void unsupportedContentTypeDoesNotAllocateUserRateKeys() throws Exception {
        var limiter = limiter(new AtomicLong(), 1, 100);
        var exchange = new Exchange("/login", "text/plain", "{}".getBytes(StandardCharsets.UTF_8), "127.0.0.1", null);
        new AuthServlet(limiter).doPost(exchange.request, exchange.response);
        assertEquals(415, exchange.status);
        assertEquals(0, limiter.trackedUsernames());
        assertEquals(1, limiter.trackedAddresses(), "Invalid bodies still consume the early peer/global allowance");
    }
}
