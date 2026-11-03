package ru.itmo.is;

import jakarta.json.Json;
import org.junit.jupiter.api.*;

import java.net.http.HttpResponse;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real JTA/CDI/EclipseLink lock-before-read regression. Needs HTTP + JDBC to the SAME test schema. */
@Timeout(60)
class MutationApiIT {
    ApiIT api;
    ExecutorService worker;

    @BeforeEach void setup() throws Exception {
        api = new ApiIT();
        api.setup();
        worker = Executors.newSingleThreadExecutor();
    }

    @AfterEach void cleanup() throws Exception {
        if (worker != null) {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(25, TimeUnit.SECONDS));
        }
        if (api != null) api.cleanup();
    }

    @Test void createWaitsBeforeResolvingAddressAndReturnsReboundTown() throws Exception {
        rebindBeforeHttpWriter(true);
    }

    @Test void updateWaitsBeforeResolvingAddressAndReturnsReboundTown() throws Exception {
        rebindBeforeHttpWriter(false);
    }

    private void rebindBeforeHttpWriter(boolean create) throws Exception {
        var source = api.create("location race source " + UUID.randomUUID(), "10");
        var replacement = api.create("location race replacement " + UUID.randomUUID(), "20");
        // Warm the provider's read paths BEFORE the native rebind: L2 cache must not
        // resurrect the old Address.town when a writer gets through the guard.
        api.get(source.getInt("id"));
        int address = ApiIT.referenceId(source, "addresses");
        int oldTown = ApiIT.referenceId(source, "locations");
        int newTown = ApiIT.referenceId(replacement, "locations");
        String body;
        if (create) {
            body = Json.createObjectBuilder(ApiIT.parse(api.fixture("created after rebind", "5")))
                    .add("coordinates", Json.createObjectBuilder().add("id", ApiIT.referenceId(source, "coordinates")))
                    .add("officialAddress", Json.createObjectBuilder().add("id", address)).build().toString();
        } else {
            body = api.updateBody(replacement, "linked after rebind")
                    .add("officialAddress", Json.createObjectBuilder().add("id", address)).build().toString();
        }
        Future<HttpResponse<String>> response;
        try (Connection lock = connect(); Connection observer = connect()) {
            lock.setAutoCommit(false);
            try {
                execute(lock, "SELECT lab1_lock_writes(TRUE)");
                try (var check = lock.prepareStatement("SELECT count(*) FROM organizations WHERE id=? AND name=?")) {
                    check.setInt(1, source.getInt("id"));
                    check.setString(2, source.getString("name"));
                    try (var rows = check.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals(1, rows.getLong(1), "HTTP and JDBC must point to the same isolated test schema");
                    }
                }
                int blocker = (int) scalar(lock, "SELECT pg_backend_pid()");
                execute(lock, "UPDATE organizations SET version=version+1 WHERE official_address_id=" + address
                        + " OR postal_address_id=" + address);
                execute(lock, "UPDATE addresses SET town_id=" + newTown + " WHERE id=" + address);
                execute(lock, "DELETE FROM locations WHERE id=" + oldTown);
                response = worker.submit(() -> api.send(create ? "POST" : "PUT",
                        create ? "/api/organizations" : "/api/organizations/" + replacement.getInt("id"),
                        body, create ? UUID.randomUUID().toString() : null));
                // Confirm the actual Payara connection, not a JDBC model of a service,
                // is blocked on our transaction's advisory key before releasing it.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean queued = false;
                while (System.nanoTime() < deadline) {
                    if (scalar(observer, "SELECT count(*) FROM pg_locks waiting WHERE waiting.locktype='advisory'"
                            + " AND NOT waiting.granted AND " + blocker + "=ANY(pg_blocking_pids(waiting.pid))") > 0) {
                        queued = true;
                        break;
                    }
                    if (response.isDone()) fail("HTTP writer bypassed lock: " + response.get().body());
                }
                assertTrue(queued, "HTTP/JPA writer did not wait for schema mutation guard; check matching HTTP/JDBC schemas");
                lock.commit();
            } finally {
                lock.rollback();
            }
        }
        var result = response.get(25, TimeUnit.SECONDS);
        assertEquals(create ? 201 : 200, result.statusCode(), result.body());
        var organization = ApiIT.parse(result.body());
        api.track(organization);
        assertEquals(newTown, ApiIT.referenceId(organization, "locations"));
        assertEquals(create ? "1" : "2", organization.getString("version"));
        var persisted = api.get(organization.getInt("id"));
        assertEquals(newTown, ApiIT.referenceId(persisted, "locations"));
        assertEquals(organization.getString("version"), persisted.getString("version"));
        assertEquals(organization.getString("employeesCount"), persisted.getString("employeesCount"));
        var changedSource = api.get(source.getInt("id"));
        assertEquals(ApiIT.version(source) + 1, ApiIT.version(changedSource));
        assertEquals(newTown, ApiIT.referenceId(changedSource, "locations"));
        assertEquals(409, api.send("PUT", "/api/organizations/" + source.getInt("id"),
                api.updateBody(source, "stale source").build().toString(), null).statusCode());
    }

    static Connection connect() throws SQLException {
        Connection db = DriverManager.getConnection(DatabaseIT.env("TEST_DB_URL"),
                DatabaseIT.env("TEST_DB_USER"), DatabaseIT.env("TEST_DB_PASSWORD"));
        execute(db, "SET statement_timeout TO '15s'");
        return db;
    }

    static void execute(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement()) { statement.execute(sql); }
    }

    static long scalar(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
