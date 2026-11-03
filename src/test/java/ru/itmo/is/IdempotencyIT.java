package ru.itmo.is;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.*;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real JTA/SQL atomicity. Run ONLY against an isolated test app; one test installs a scoped CHECK. */
@Timeout(60)
class IdempotencyIT {
    ApiIT api;
    final List<ApiIT> peers = new ArrayList<>();

    @BeforeEach void setup() throws Exception { api = new ApiIT(); api.setup(); }
    @AfterEach void cleanup() throws Exception {
        for (ApiIT peer : peers) peer.cleanup();
        if (api != null) api.cleanup();
    }

    ApiIT peer(String target) throws Exception {
        var peer = new ApiIT();
        peer.base = target;
        peer.client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(10)).build();
        var login = Json.createObjectBuilder().add("username", DatabaseIT.env("TEST_AUTH_USER"))
                .add("password", DatabaseIT.env("TEST_AUTH_PASSWORD")).build();
        var response = peer.send("POST", "/api/auth/login", login.toString(), null);
        assertEquals(200, response.statusCode(), response.body());
        var session = ApiIT.parse(response.body());
        peer.csrf = session.getString("csrf"); peer.wsToken = session.getString("wsToken");
        peers.add(peer);
        return peer;
    }

    String secondary() { return System.getenv().getOrDefault("TEST_SECOND_BASE_URL", api.base); }
    static String key() { return "idempotency-" + UUID.randomUUID(); }

    @Test void concurrentSameKeyAcrossIndependentSessionsCreatesExactlyOnce() throws Exception {
        var peer = peer(secondary());
        String key = key(), name = "same-key-" + UUID.randomUUID(), body = api.fixture(name, "4");
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        try {
            List<Future<JsonObject>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                ApiIT caller = i % 2 == 0 ? api : peer;
                results.add(pool.submit(() -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return caller.ok("POST", "/api/organizations", body, key); }));
            }
            start.countDown();
            JsonObject first = null;
            for (var task : results) {
                var result = task.get(25, TimeUnit.SECONDS); api.track(result);
                if (first == null) first = result;
                else assertEquals(first, result, "Every retry must return the original complete response");
            }
            assertNotNull(first);
            assertEquals(first, api.get(first.getInt("id")));
            assertEquals(1, api.ok("GET", "/api/organizations?filterValue=" + name, null, null).getInt("total"));
            try (var db = db()) { assertEquals(1, records(db, "create", key)); }
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS)); }
    }

    @Test void replayAfterNewLoginDoesNotRepeatIncrementAndScopesSeparateOperations() throws Exception {
        String key = key();
        var created = api.ok("POST", "/api/organizations", api.fixture("scoped-" + UUID.randomUUID(), "4"), key);
        api.track(created);
        String route = "/api/special-operations/" + created.getInt("id") + "/add-employee";
        var incremented = api.ok("POST", route, null, key); // Same key, DIFFERENT operation.
        var peer = peer(secondary());
        assertEquals(incremented, peer.ok("POST", route, null, key));
        assertEquals("5", api.get(created.getInt("id")).getString("employeesCount"));
        try (var db = db()) {
            assertEquals(1, records(db, "create", key));
            assertEquals(1, records(db, "add-employee", key));
        }
    }

    @Test void changedPayloadRejectedButOriginalResponseSurvivesLaterMutationAndDeletion() throws Exception {
        String key = key(), body = api.fixture("snapshot-" + UUID.randomUUID(), "4");
        var created = api.ok("POST", "/api/organizations", body, key); api.track(created);
        assertEquals(400, api.send("POST", "/api/organizations", api.fixture("different", "4"), key).statusCode());
        api.ok("POST", "/api/special-operations/" + created.getInt("id") + "/add-employee", null, key());
        assertEquals(created, api.ok("POST", "/api/organizations", body, key), "Replay is a snapshot, not a reload");
        assertEquals(204, api.send("DELETE", "/api/organizations/" + created.getInt("id"), null, null).statusCode());
        assertEquals(created, api.ok("POST", "/api/organizations", body, key));
        assertEquals(404, api.send("GET", "/api/organizations/" + created.getInt("id"), null, null).statusCode());
    }

    @Test void failedRequestReleasesClaimAndCorrectedPayloadCanRetry() throws Exception {
        String key = key(), body = api.fixture("validation-retry-" + UUID.randomUUID(), "4");
        assertEquals(400, api.send("POST", "/api/organizations", body.replace("\"rating\":1", "\"rating\":0"), key).statusCode());
        try (var db = db()) { assertEquals(0, records(db, "create", key)); }
        var created = api.ok("POST", "/api/organizations", body, key); api.track(created);
        assertEquals(created, api.ok("POST", "/api/organizations", body, key));
    }

    @Test void responsePersistenceFailureRollsBackBusinessMutationAndClaimTogether() throws Exception {
        var original = api.create("completion-rollback-" + UUID.randomUUID(), "4");
        original = api.get(original.getInt("id"));
        String key = key(), constraint = "idempotency_failure_" + UUID.randomUUID().toString().replace("-", "");
        String route = "/api/special-operations/" + original.getInt("id") + "/add-employee";
        try (var db = db()) {
            verifySameApp(db, original);
            boolean installed = false;
            try {
                execute(db, "ALTER TABLE api_idempotency ADD CONSTRAINT " + constraint
                        + " CHECK (request_key <> '" + key + "' OR response_json IS NULL)");
                installed = true;
                var failure = api.send("POST", route, null, key);
                assertEquals(409, failure.statusCode(), failure.body());
                assertFalse(failure.body().contains(constraint));
                assertEquals(original, api.get(original.getInt("id")), "Failure storing response must undo employees/version changes");
                assertEquals(0, records(db, "add-employee", key), "Failed transaction must not leave a pending claim");
            } finally {
                if (installed) execute(db, "ALTER TABLE api_idempotency DROP CONSTRAINT " + constraint);
            }
        }
        var saved = api.ok("POST", route, null, key);
        assertEquals("5", saved.getString("employeesCount"));
        assertEquals(saved, api.ok("POST", route, null, key));
    }

    @Test void blockedKeyDoesNotBlockOtherKeysOrRequestsWithoutKeys() throws Exception {
        String blockedKey = key(), body = api.fixture("unblocked-" + UUID.randomUUID(), "4");
        var pool = Executors.newFixedThreadPool(3);
        Future<HttpResponse<String>> blocked = null;
        try (var lock = db(); var observer = db()) {
            lock.setAutoCommit(false);
            try {
                try (var insert = lock.prepareStatement("INSERT INTO api_idempotency(owner_name,operation,request_key,request_sha256) VALUES(?, 'create', ?, ?)")) {
                    insert.setString(1, DatabaseIT.env("TEST_AUTH_USER")); insert.setString(2, blockedKey); insert.setString(3, "0".repeat(64)); insert.executeUpdate();
                }
                int pid = (int) scalar(lock, "SELECT pg_backend_pid()");
                blocked = pool.submit(() -> api.send("POST", "/api/organizations", body, blockedKey));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    if (scalar(observer, "SELECT count(*) FROM pg_stat_activity WHERE " + pid + "=ANY(pg_blocking_pids(pid))") > 0) { waiting = true; break; }
                    if (blocked.isDone()) fail("Expected an uncommitted-key wait, got " + blocked.get().body());
                }
                assertTrue(waiting, "Actual HTTP caller must wait on the exact duplicate key");
                var keyed = pool.submit(() -> api.ok("POST", "/api/organizations", body, key()));
                var unkeyed = pool.submit(() -> api.ok("POST", "/api/organizations", body, null));
                api.track(keyed.get(5, TimeUnit.SECONDS)); api.track(unkeyed.get(5, TimeUnit.SECONDS));
                assertFalse(blocked.isDone(), "Unrelated requests must finish before the blocked key is released");
            } finally { lock.rollback(); }
            var result = blocked.get(20, TimeUnit.SECONDS);
            assertEquals(201, result.statusCode(), result.body()); api.track(ApiIT.parse(result.body()));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS)); }
    }

    static Connection db() throws SQLException {
        var db = DriverManager.getConnection(DatabaseIT.env("TEST_DB_URL"), DatabaseIT.env("TEST_DB_USER"), DatabaseIT.env("TEST_DB_PASSWORD"));
        try { execute(db, "SET statement_timeout TO '15s'"); return db; }
        catch (SQLException error) { db.close(); throw error; }
    }
    static long records(Connection db, String operation, String key) throws SQLException {
        try (var statement = db.prepareStatement("SELECT count(*) FROM api_idempotency WHERE owner_name=? AND operation=? AND request_key=?")) {
            statement.setString(1, DatabaseIT.env("TEST_AUTH_USER")); statement.setString(2, operation); statement.setString(3, key);
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); }
        }
    }
    static void verifySameApp(Connection db, JsonObject object) throws SQLException {
        try (var statement = db.prepareStatement("SELECT count(*) FROM organizations WHERE id=? AND name=?")) {
            statement.setInt(1, object.getInt("id")); statement.setString(2, object.getString("name"));
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals(1, rows.getLong(1), "HTTP and JDBC must share the isolated test schema"); }
        }
    }
    static void execute(Connection db, String sql) throws SQLException { try (var s = db.createStatement()) { s.execute(sql); } }
    static long scalar(Connection db, String sql) throws SQLException {
        try (var s = db.createStatement(); var rows = s.executeQuery(sql)) { assertTrue(rows.next()); return rows.getLong(1); }
    }
}
