package ru.itmo.is;

import jakarta.json.*;
import org.junit.jupiter.api.*;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP/JPA projection and concurrent commits. Run only on the isolated integration app. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(120)
class OrganizationOptionsIT {
    ApiIT api;
    final List<JsonObject> fixtures = new ArrayList<>();
    final String prefix = "options-" + UUID.randomUUID();

    @BeforeAll void seedSharedReferenceFixtures() throws Exception {
        api = new ApiIT();
        api.setup();
        var first = api.create(prefix + "-000", Long.toString(Long.MAX_VALUE));
        fixtures.add(first);
        for (int i = 1; i <= 200; i++) {
            // Every row shares the first row's references: large selector test, not 600 nested fixtures.
            var body = Json.createObjectBuilder().add("name", prefix + "-" + i)
                    .add("annualTurnover", 10).add("employeesCount", Integer.toString(i + 1))
                    .add("rating", 1).add("type", "COMMERCIAL")
                    .add("coordinates", Json.createObjectBuilder().add("id", first.getJsonObject("coordinates").getInt("id")))
                    .add("officialAddress", Json.createObjectBuilder().add("id", first.getJsonObject("officialAddress").getInt("id")))
                    .build().toString();
            var created = api.ok("POST", "/api/organizations", body, null);
            api.track(created);
            fixtures.add(created);
        }
    }

    @AfterAll void cleanupFixtures() throws Exception {
        if (api != null) api.cleanup();
    }

    @Test @Order(1) void fullSelectorHasMoreThanOnePageExactStringsAndNoNestedFields() throws Exception {
        var options = options();
        var byId = byId(options);
        assertTrue(options.size() >= 201, "Selector must not inherit list endpoint's 100-row limit");
        int previous = 0;
        for (var value : options) {
            var option = value.asJsonObject();
            assertEquals(Set.of("id", "name", "employeesCount"), option.keySet(), "Only compact scalar fields are allowed");
            assertTrue(option.getInt("id") > previous, "IDs must be unique and strictly ascending");
            previous = option.getInt("id");
            assertInstanceOf(JsonString.class, option.get("employeesCount"));
        }
        for (var fixture : fixtures) {
            var option = byId.get(fixture.getInt("id"));
            assertNotNull(option, "Every own ID, including the old offset boundary, must be present");
            assertEquals(fixture.getString("name"), option.getString("name"));
            assertEquals(fixture.getString("employeesCount"), option.getString("employeesCount"));
        }
        assertEquals("9223372036854775807", byId.get(fixtures.get(0).getInt("id")).getString("employeesCount"));
        // The static /options route must not replace or interfere with the existing full GET-by-ID contract.
        var full = api.get(fixtures.get(100).getInt("id"));
        assertNotNull(full.getJsonObject("officialAddress"));
        assertNotNull(full.getJsonObject("coordinates"));
    }

    @Test @Order(2) void selectorKeepsExistingSessionAuthenticationAndOriginProtection() throws Exception {
        var anonymous = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create(api.base + "/api/organizations/options")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, anonymous.statusCode(), anonymous.body());
        var foreignOrigin = api.client.send(HttpRequest.newBuilder(URI.create(api.base + "/api/organizations/options"))
                .header("Origin", "https://not-the-test-app.invalid").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, foreignOrigin.statusCode(), foreignOrigin.body());
        assertFalse(options().isEmpty(), "Authenticated same-origin/CLI caller must still be allowed");
    }

    @Test @Order(3) void singleSnapshotNeverMixesTwoRowsFromDifferentConcurrentCommits() throws Exception {
        int left = fixtures.get(1).getInt("id"), right = fixtures.get(2).getInt("id");
        try (var db = database()) {
            assertSameIsolatedApplication(db);
            writePair(db, left, right, false);
        }
        var stop = new AtomicBoolean();
        var commits = new AtomicInteger();
        var firstCommit = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        Future<?> writer = worker.submit(() -> {
            try (var db = database()) {
                boolean alternate = true;
                while (!stop.get()) {
                    writePair(db, left, right, alternate);
                    alternate = !alternate;
                    commits.incrementAndGet();
                    firstCommit.countDown();
                }
            } catch (Exception e) {
                throw new CompletionException(e);
            } finally { firstCommit.countDown(); }
        });
        try {
            assertTrue(firstCommit.await(10, TimeUnit.SECONDS));
            for (int i = 0; i < 40; i++) {
                var rows = byId(options());
                for (var fixture : fixtures) assertTrue(rows.containsKey(fixture.getInt("id")), "No live fixture may disappear at offset boundaries");
                var a = rows.get(left);
                var b = rows.get(right);
                boolean alternate = a.getString("name").equals(prefix + "-B-left");
                assertEquals(prefix + (alternate ? "-B-left" : "-A-left"), a.getString("name"));
                assertEquals(prefix + (alternate ? "-B-right" : "-A-right"), b.getString("name"), "One SELECT must see a single committed pair");
                assertEquals(alternate ? "23" : "17", a.getString("employeesCount"));
                assertEquals(alternate ? "29" : "19", b.getString("employeesCount"));
            }
        } finally {
            stop.set(true);
            try { writer.get(15, TimeUnit.SECONDS); }
            finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
        }
        assertTrue(commits.get() > 0);
        // Deleting an earlier row must not remove or shift the later live boundary ID in a new snapshot.
        int removed = fixtures.get(0).getInt("id"), boundary = fixtures.get(100).getInt("id");
        assertEquals(204, api.send("DELETE", "/api/organizations/" + removed, null, null).statusCode());
        var afterDelete = byId(options());
        assertFalse(afterDelete.containsKey(removed));
        assertTrue(afterDelete.containsKey(boundary));
        for (int i = 1; i < fixtures.size(); i++) assertTrue(afterDelete.containsKey(fixtures.get(i).getInt("id")));
    }

    JsonArray options() throws Exception {
        var response = api.send("GET", "/api/organizations/options", null, null);
        assertEquals(200, response.statusCode(), response.body());
        try (var reader = Json.createReader(new StringReader(response.body()))) { return reader.readArray(); }
    }

    static Map<Integer, JsonObject> byId(JsonArray array) {
        Map<Integer, JsonObject> result = new HashMap<>();
        for (var value : array) {
            var row = value.asJsonObject();
            assertNull(result.put(row.getInt("id"), row), "Selector must not contain duplicate IDs");
        }
        return result;
    }

    Connection database() throws SQLException {
        var db = DriverManager.getConnection(DatabaseIT.env("TEST_DB_URL"), DatabaseIT.env("TEST_DB_USER"), DatabaseIT.env("TEST_DB_PASSWORD"));
        try (var statement = db.createStatement()) { statement.execute("SET statement_timeout TO '10s'"); }
        db.setAutoCommit(false);
        return db;
    }

    void assertSameIsolatedApplication(Connection db) throws SQLException {
        var seed = fixtures.get(0);
        try (var statement = db.prepareStatement("SELECT count(*) FROM organizations WHERE id=? AND name=?")) {
            statement.setInt(1, seed.getInt("id")); statement.setString(2, seed.getString("name"));
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next()); assertEquals(1, rows.getLong(1), "HTTP and JDBC must share the isolated test schema");
            }
        }
        db.rollback();
    }

    void writePair(Connection db, int left, int right, boolean alternate) throws SQLException {
        try {
            // Test writer obeys the existing application protocol before changing its own fixture rows.
            try (var guard = db.createStatement()) { guard.execute("SELECT lab1_lock_writes(FALSE)"); }
            try (var update = db.prepareStatement("UPDATE organizations SET name=CASE WHEN id=? THEN ? ELSE ? END, "
                    + "employees_count=CASE WHEN id=? THEN ? ELSE ? END, version=version+1 WHERE id IN (?,?)")) {
                update.setInt(1, left); update.setString(2, prefix + (alternate ? "-B-left" : "-A-left"));
                update.setString(3, prefix + (alternate ? "-B-right" : "-A-right")); update.setInt(4, left);
                update.setLong(5, alternate ? 23 : 17); update.setLong(6, alternate ? 29 : 19);
                update.setInt(7, left); update.setInt(8, right);
                assertEquals(2, update.executeUpdate());
            }
            db.commit();
        } catch (SQLException | RuntimeException | AssertionError error) { db.rollback(); throw error; }
    }
}
