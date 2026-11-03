package ru.itmo.is;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Snapshot demonstrations use only this class's private UUID schema. API checks
 * create/clean their own fixtures and never pause production code or install hooks.
 * Full run requires an isolated test app and TEST_DB_* plus TEST_BASE_URL/AUTH_*.
 */
@Timeout(60)
class PaginationIT {
    static Connection admin;
    static String schema;
    static boolean schemaCreated;

    @BeforeAll static void schema() throws Exception {
        schema = "lab1_pagination_" + UUID.randomUUID().toString().replace("-", "");
        admin = connect();
        try {
            execute(admin, "CREATE SCHEMA " + schema);
            schemaCreated = true;
            execute(admin, "SET search_path TO " + schema);
            execute(admin, Files.readString(Path.of("db/schema.sql")));
        } catch (Exception error) {
            try { cleanupSchema(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    @AfterAll static void cleanupSchema() throws Exception {
        if (admin == null) return;
        try {
            if (schemaCreated && !admin.isClosed()) {
                execute(admin, "DROP SCHEMA " + schema + " CASCADE");
                schemaCreated = false;
            }
        } finally {
            admin.close();
            admin = null;
        }
    }

    @BeforeEach void seedOnlyPrivateSchema() throws Exception {
        try (var statement = admin.createStatement(); var rows = statement.executeQuery("SELECT current_schema()")) {
            assertTrue(rows.next());
            assertEquals(schema, rows.getString(1));
        }
        assertTrue(schemaCreated);
        execute(admin, "TRUNCATE organizations,addresses,coordinates,locations RESTART IDENTITY CASCADE");
        execute(admin, "INSERT INTO coordinates(x,y) VALUES(1,2);"
                + "INSERT INTO locations(x,y,z,name) VALUES(1,2,3,'before town'),(4,5,6,'after town');"
                + "INSERT INTO addresses(zip_code,town_id) VALUES('before zip',1),('after zip',2);"
                + "INSERT INTO organizations(name,coordinates_id,official_address_id,annual_turnover,employees_count,rating,type)"
                + " VALUES('page first',1,1,10,2,1,'COMMERCIAL'),('page second',1,1,20,3,1,'GOVERNMENT')");
    }

    @Test void countItemsAndNestedLoadsKeepOneSnapshotWhileWriterCommits() throws Exception {
        snapshotAcrossWriterCommit(0);
    }

    @Test void outOfRangePageIsEmptyButRetainsPreWriteTotal() throws Exception {
        snapshotAcrossWriterCommit(100);
    }

    private void snapshotAcrossWriterCommit(int offset) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        var countFinished = new CountDownLatch(1);
        var writerCommitted = new CountDownLatch(1);
        Future<Void> write = null;
        try (Connection read = privateConnection()) {
            read.setAutoCommit(false);
            // Exactly the statement executed first by OrganizationRepository.beginPageSnapshot.
            execute(read, "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
            try {
                long total = scalar(read, "SELECT count(*) FROM organizations");
                assertEquals(2, total);
                countFinished.countDown();
                write = worker.submit(() -> {
                    assertTrue(countFinished.await(10, TimeUnit.SECONDS));
                    try (Connection db = privateConnection()) {
                        db.setAutoCommit(false);
                        try {
                            execute(db, "SELECT lab1_lock_writes(FALSE)");
                            execute(db, "UPDATE organizations SET name='changed',version=version+1 WHERE id=1");
                            execute(db, "UPDATE addresses SET town_id=2 WHERE id=1");
                            execute(db, "DELETE FROM organizations WHERE id=2");
                            execute(db, "INSERT INTO organizations(name,coordinates_id,official_address_id,annual_turnover,employees_count,rating,type)"
                                    + " VALUES('new first',1,2,10,1,1,'COMMERCIAL'),('new second',1,2,10,1,1,'COMMERCIAL')");
                            db.commit();
                            writerCommitted.countDown();
                            return null;
                        } finally { db.rollback(); }
                    }
                });
                // This must finish WHILE the page transaction remains open: SELECT
                // takes no advisory mutation guard and does not block ordinary DML.
                assertTrue(writerCommitted.await(10, TimeUnit.SECONDS), "Read-only page blocked writer commit");
                write.get(10, TimeUnit.SECONDS);
                List<Long> ids = ids(read, "SELECT id FROM organizations ORDER BY id LIMIT 10 OFFSET " + offset);
                assertEquals(offset == 0 ? List.of(1L, 2L) : List.of(), ids);
                assertEquals(2, total, "An empty/out-of-range result must not erase total");
                // Separate statements model deferred JPA nested loads after items.
                assertEquals(1, scalar(read, "SELECT version FROM organizations WHERE id=1"));
                assertEquals(1, scalar(read, "SELECT town_id FROM addresses WHERE id=1"));
                assertEquals(2, scalar(read, "SELECT count(*) FROM organizations"));
                assertEquals(3, scalar(admin, "SELECT count(*) FROM organizations"));
                assertEquals(2, scalar(admin, "SELECT town_id FROM addresses WHERE id=1"));
                read.commit();
            } finally { read.rollback(); }
        } finally {
            if (write != null && !write.isDone()) write.cancel(true);
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test void emptySnapshotStillReturnsZeroWhenWriterAddsMatchingRow() throws Exception {
        try (Connection read = privateConnection(); Connection write = privateConnection()) {
            read.setAutoCommit(false);
            execute(read, "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
            try {
                String predicate = " WHERE name='not yet present'";
                assertEquals(0, scalar(read, "SELECT count(*) FROM organizations" + predicate));
                execute(write, "INSERT INTO organizations(name,coordinates_id,official_address_id,annual_turnover,employees_count,rating,type)"
                        + " VALUES('not yet present',1,1,10,1,1,'COMMERCIAL')");
                assertTrue(ids(read, "SELECT id FROM organizations" + predicate + " ORDER BY id LIMIT 10").isEmpty());
                assertEquals(0, scalar(read, "SELECT count(*) FROM organizations" + predicate));
                assertEquals(1, scalar(write, "SELECT count(*) FROM organizations" + predicate));
            } finally { read.rollback(); }
        }
    }

    @Test void snapshotIsReadOnlyAndDoesNotLeakIsolationToFollowingTransactions() throws Exception {
        try (Connection db = privateConnection()) {
            String originalIsolation = setting(db, "transaction_isolation");
            db.setAutoCommit(false);
            execute(db, "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
            assertEquals("repeatable read", setting(db, "transaction_isolation"));
            assertEquals("on", setting(db, "transaction_read_only"));
            SQLException error = assertThrows(SQLException.class,
                    () -> execute(db, "UPDATE organizations SET rating=2 WHERE id=1"));
            assertEquals("25006", error.getSQLState());
            db.rollback();
            assertEquals(originalIsolation, setting(db, "transaction_isolation"));
            assertEquals("off", setting(db, "transaction_read_only"));
            db.rollback();
        }
    }

    @Test void realApiPageHasConsistentTotalAndFilteringDuringMembershipChanges() throws Exception {
        ApiIT api = new ApiIT();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<?> write = null;
        var start = new CountDownLatch(1);
        var writes = new AtomicInteger();
        try {
            api.setup();
            String marker = "pagination_" + UUID.randomUUID();
            String outside = "unmatched_" + UUID.randomUUID();
            var stable = api.create("%_!" + marker + " stable", "2");
            var variable = api.create(outside, "3");
            int variableId = variable.getInt("id");
            int stableAddress = ApiIT.referenceId(stable, "addresses");
            int otherAddress = ApiIT.referenceId(variable, "addresses");
            String query = "/api/organizations?size=100&filterField=name&filterValue=" + encode(marker);
            write = worker.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                JsonObject current = variable;
                for (int i = 0; i < 60; i++) {
                    boolean inside = i % 2 == 0;
                    String body = api.updateBody(current, inside ? marker + " variable" : outside)
                            .add("officialAddress", Json.createObjectBuilder().add("id", inside ? stableAddress : otherAddress))
                            .build().toString();
                    current = api.ok("PUT", "/api/organizations/" + variableId, body, null);
                    writes.incrementAndGet();
                }
                return current;
            });
            start.countDown();
            for (int i = 0; i < 60; i++) {
                var page = api.ok("GET", query, null, null);
                JsonArray items = page.getJsonArray("items");
                long total = page.getJsonNumber("total").longValueExact();
                assertEquals(items.size(), total, "For this unique <=2 row filter, total and items must agree under writes");
                assertTrue(total == 1 || total == 2);
                assertEquals(0, page.getInt("page"));
                assertEquals(100, page.getInt("size"));
                for (var value : items) {
                    var item = value.asJsonObject();
                    assertTrue(item.getString("name").contains(marker));
                    if (item.getInt("id") == variableId) {
                        assertEquals(stableAddress, ApiIT.referenceId(item, "addresses"), "DTO must include its own snapshot's link");
                    }
                }
            }
            write.get(25, TimeUnit.SECONDS);
            assertEquals(60, writes.get());
            // Check real JSONB DTO shape, empty/out-of-range handling and existing
            // literal LIKE escaping, left-join NULL sorting and enum filtering.
            var first = api.ok("GET", query, null, null);
            long total = first.getJsonNumber("total").longValueExact();
            var beyond = api.ok("GET", query + "&page=100", null, null);
            assertTrue(beyond.getJsonArray("items").isEmpty());
            assertEquals(total, beyond.getJsonNumber("total").longValueExact());
            var empty = api.ok("GET", "/api/organizations?filterValue=" + encode("absent_" + UUID.randomUUID()), null, null);
            assertEquals(0, empty.getJsonNumber("total").longValueExact());
            assertTrue(empty.getJsonArray("items").isEmpty());
            var literal = api.ok("GET", "/api/organizations?filterValue=" + encode("%_!" + marker), null, null);
            assertEquals(1, literal.getJsonNumber("total").longValueExact());
            assertEquals(stable.getInt("id"), literal.getJsonArray("items").getJsonObject(0).getInt("id"));
            var nullSorted = api.ok("GET", query + "&sort=postalTown&direction=desc", null, null);
            assertEquals(total, nullSorted.getJsonNumber("total").longValueExact());
            assertTrue(nullSorted.getJsonArray("items").stream().allMatch(v -> !v.asJsonObject().containsKey("postalAddress")
                    || v.asJsonObject().isNull("postalAddress")));
            var enums = api.ok("GET", "/api/organizations?size=100&filterField=type&filterValue=mercial", null, null);
            assertTrue(enums.getJsonArray("items").stream().allMatch(v -> v.asJsonObject().getString("type").equals("COMMERCIAL")));
            assertTrue(enums.getJsonNumber("total").longValueExact() >= 2);
        } finally {
            start.countDown();
            if (write != null && !write.isDone()) write.cancel(true);
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(25, TimeUnit.SECONDS));
            api.cleanup();
        }
    }

    static Connection connect() throws SQLException {
        Connection db = DriverManager.getConnection(DatabaseIT.env("TEST_DB_URL"),
                DatabaseIT.env("TEST_DB_USER"), DatabaseIT.env("TEST_DB_PASSWORD"));
        try {
            execute(db, "SET statement_timeout TO '12s'");
            execute(db, "SET lock_timeout TO '12s'");
            return db;
        } catch (SQLException error) { db.close(); throw error; }
    }

    static Connection privateConnection() throws SQLException {
        Connection db = connect();
        try { execute(db, "SET search_path TO " + schema); return db; }
        catch (SQLException error) { db.close(); throw error; }
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

    static List<Long> ids(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) {
            List<Long> result = new ArrayList<>();
            while (rows.next()) result.add(rows.getLong(1));
            return result;
        }
    }

    static String setting(Connection db, String setting) throws SQLException {
        try (var statement = db.createStatement(); var rows = statement.executeQuery("SHOW " + setting)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
