package ru.itmo.is;

import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL transactions, not ORM mocks. Every connection explicitly selects this
 * class's unique schema. SQL below models the service guard-before-read contract; tests
 * of the Java service call order belong in the service unit tests.
 *
 * No sleeps: latches/barriers establish the schedule, pg_try_advisory_xact_lock[_shared]
 * checks compatibility, and pg_locks confirms a statement is actually waiting before
 * the transaction holding the lock is released. All waits/SQL statements are bounded.
 */
@Timeout(60)
class DatabaseConcurrencyIT {
    private static Connection admin;
    private static String schema;
    private static boolean schemaCreated;
    private ExecutorService workers;
    private final List<Future<?>> tasks = new ArrayList<>();

    @BeforeAll static void setupSchema() throws Exception {
        schema = "lab1_concurrency_" + UUID.randomUUID().toString().replace("-", "");
        admin = connect();
        try {
            execute(admin, "CREATE SCHEMA " + schema);
            schemaCreated = true;
            execute(admin, "SET search_path TO " + schema);
            execute(admin, Files.readString(Path.of("db/schema.sql")));
        } catch (Exception failure) {
            try { dropSchema(); } catch (Exception cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    @AfterAll static void dropSchema() throws Exception {
        if (admin == null) return;
        try {
            if (schemaCreated && !admin.isClosed()) {
                if (!admin.getAutoCommit()) {
                    admin.rollback();
                    admin.setAutoCommit(true);
                }
                execute(admin, "DROP SCHEMA " + schema + " CASCADE");
                schemaCreated = false;
            }
        } finally {
            admin.close();
            admin = null;
        }
    }

    private static Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(DatabaseIT.env("TEST_DB_URL"),
                DatabaseIT.env("TEST_DB_USER"), DatabaseIT.env("TEST_DB_PASSWORD"));
        try {
            execute(connection, "SET statement_timeout TO '12s'");
            execute(connection, "SET lock_timeout TO '12s'");
            return connection;
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
    }

    private static Transaction transaction() throws SQLException {
        Connection connection = connect();
        try {
            execute(connection, "SET search_path TO " + schema);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            return new Transaction(connection);
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
    }

    /** Always roll back unfinished work before closing, including failed assertions. */
    private record Transaction(Connection db) implements AutoCloseable {
        @Override public void close() throws SQLException {
            try { db.rollback(); } finally { db.close(); }
        }
    }

    @BeforeEach void seed() throws Exception {
        assertTrue(schemaCreated);
        try (var statement = admin.createStatement(); var result = statement.executeQuery("SELECT current_schema()")) {
            assertTrue(result.next());
            assertEquals(schema, result.getString(1), "Never truncate the application schema");
        }
        execute(admin, "TRUNCATE organizations,addresses,coordinates,locations RESTART IDENTITY CASCADE");
        execute(admin, "INSERT INTO coordinates(x,y) VALUES(1,2);"
                + "INSERT INTO locations(x,y,z,name) VALUES(1,2,3,'old town'),(4,5,6,'replacement town');"
                + "INSERT INTO addresses(zip_code,town_id) VALUES('source address',1),('other address',2);"
                + "INSERT INTO organizations(name,coordinates_id,official_address_id,postal_address_id,"
                + "annual_turnover,employees_count,rating,type) VALUES"
                + "('first',1,1,1,10,10,1,'COMMERCIAL'),"
                + "('second',1,2,2,20,20,2,'GOVERNMENT'),"
                + "('unrelated',1,2,NULL,30,30,3,'PRIVATE_LIMITED_COMPANY')");
        workers = Executors.newFixedThreadPool(3);
    }

    @AfterEach void stopWorkers() throws Exception {
        if (workers == null) return;
        for (Future<?> task : tasks) if (!task.isDone()) task.cancel(true);
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS), "JDBC workers did not close within statement timeout");
    }

    private <T> Future<T> submit(Callable<T> task) {
        Future<T> future = workers.submit(task);
        tasks.add(future);
        return future;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(15, TimeUnit.SECONDS), "Timed out waiting for the scheduled transaction");
    }

    private static <T> T result(Future<T> future) throws Exception {
        return future.get(20, TimeUnit.SECONDS);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static int update(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { return statement.executeUpdate(sql); }
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next(), sql);
            return rows.getLong(1);
        }
    }

    private static boolean bool(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next(), sql);
            boolean value = rows.getBoolean(1);
            assertFalse(rows.wasNull(), sql);
            return value;
        }
    }

    private static void guard(Connection connection, boolean exclusive) throws SQLException {
        assertTrue(bool(connection, "SELECT lab1_lock_writes(" + exclusive + ")"));
    }

    private static boolean tryGuard(Connection connection, boolean exclusive) throws SQLException {
        return bool(connection, "SELECT pg_try_advisory_xact_lock" + (exclusive ? "" : "_shared")
                + "(1364, hashtext(current_schema()))");
    }

    /** Database state is the barrier, not a scheduler delay or an optimistic isDone check. */
    private static void awaitAdvisoryWait(int pid, Future<?> pending) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (var statement = admin.prepareStatement("SELECT EXISTS (SELECT 1 FROM pg_locks "
                + "WHERE pid=? AND locktype='advisory' AND NOT granted)")) {
            statement.setInt(1, pid);
            do {
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    if (rows.getBoolean(1)) return;
                }
                if (pending.isDone()) {
                    Object outcome = result(pending);
                    fail("Statement completed instead of waiting for the transaction advisory lock: " + outcome);
                }
            } while (System.nanoTime() < deadline);
        }
        fail("Statement did not queue on the advisory lock (pid=" + pid + ")");
    }

    private record Snapshot(long version, int town, long employees, Integer postalTown) {}

    private static Snapshot snapshot(Connection connection, long id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT o.version,a.town_id,o.employees_count,p.town_id "
                + "FROM organizations o JOIN addresses a ON a.id=o.official_address_id "
                + "LEFT JOIN addresses p ON p.id=o.postal_address_id WHERE o.id=?")) {
            statement.setLong(1, id);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next(), "Organization " + id + " must exist");
                return new Snapshot(rows.getLong(1), rows.getInt(2), rows.getLong(3), rows.getObject(4, Integer.class));
            }
        }
    }

    private static long createUsingSourceAddress(Connection connection) throws SQLException {
        assertEquals(1, scalar(connection, "SELECT count(*) FROM addresses WHERE id=1"));
        return scalar(connection, "INSERT INTO organizations(name,coordinates_id,official_address_id,postal_address_id,"
                + "annual_turnover,employees_count,rating,type) VALUES('created',1,1,1,5,5,1,'COMMERCIAL') RETURNING id");
    }

    private static void linkExistingToSourceAddress(Connection connection) throws SQLException {
        assertEquals(1, update(connection, "UPDATE organizations SET official_address_id=1,postal_address_id=1,"
                + "version=version+1 WHERE id=2 AND version=1"));
    }

    // The same two SQL statements as ReferenceDataRepository.replaceLocation, followed by deletion.
    private static void bumpLocationDependents(Connection connection) throws SQLException {
        execute(connection, "UPDATE organizations SET version=version+1 "
                + "WHERE official_address_id IN (SELECT id FROM addresses WHERE town_id=1) "
                + "OR postal_address_id IN (SELECT id FROM addresses WHERE town_id=1)");
    }

    private static void finishLocationReplacement(Connection connection) throws SQLException {
        assertEquals(1, update(connection, "UPDATE addresses SET town_id=2 WHERE town_id=1"));
        assertEquals(1, update(connection, "DELETE FROM locations WHERE id=1"));
    }

    private static void assertReplacementComplete() throws SQLException {
        assertEquals(0, scalar(admin, "SELECT count(*) FROM locations WHERE id=1"));
        assertEquals(2, scalar(admin, "SELECT town_id FROM addresses WHERE id=1"));
        assertEquals(new Snapshot(2, 2, 10, 2), snapshot(admin, 1));
        assertEquals(new Snapshot(1, 2, 30, null), snapshot(admin, 3));
    }

    @Test void helperUsesTheSchemaKeySharedCompatibilityAndTransactionLifetime() throws Exception {
        try (var first = transaction(); var second = transaction()) {
            guard(first.db, true);
            assertFalse(tryGuard(second.db, false), "Exclusive helper must hold (1364, hashtext(current_schema()))");
            assertFalse(tryGuard(second.db, true));
            first.db.rollback();
            assertTrue(tryGuard(second.db, false), "Rollback must release the advisory transaction lock");
            second.db.rollback();

            guard(first.db, false);
            assertTrue(tryGuard(second.db, false), "Two ordinary writers may hold shared guards together");
            assertFalse(tryGuard(second.db, true), "Shared helper must exclude an exclusive reference writer");
            first.db.commit();
            second.db.rollback();
            assertTrue(tryGuard(second.db, true), "Commit must release the shared transaction lock");
        }
    }

    @Test void exclusiveLocationReplacementFinishesBeforeNewOrganizationReadsItsAddress() throws Exception {
        exclusiveLocationBeforeWriter(true);
    }

    @Test void exclusiveLocationReplacementFinishesBeforeExistingOrganizationChangesItsLinks() throws Exception {
        exclusiveLocationBeforeWriter(false);
    }

    private void exclusiveLocationBeforeWriter(boolean create) throws Exception {
        var pid = new CompletableFuture<Integer>();
        var probeChecked = new CountDownLatch(1);
        Future<Long> writer;
        try (var replacement = transaction()) {
            guard(replacement.db, true);
            bumpLocationDependents(replacement.db);
            writer = submit(() -> {
                try (var transaction = transaction()) {
                    pid.complete((int) scalar(transaction.db, "SELECT pg_backend_pid()"));
                    assertFalse(tryGuard(transaction.db, false), "Writer must be excluded between version bump and town update");
                    probeChecked.countDown();
                    guard(transaction.db, false); // Service guard MUST precede the first entity/address read.
                    assertEquals(2, scalar(transaction.db, "SELECT town_id FROM addresses WHERE id=1"));
                    long id;
                    if (create) id = createUsingSourceAddress(transaction.db);
                    else {
                        assertEquals(1, snapshot(transaction.db, 2).version);
                        linkExistingToSourceAddress(transaction.db);
                        id = 2;
                    }
                    assertEquals(new Snapshot(create ? 1 : 2, 2, create ? 5 : 20, 2), snapshot(transaction.db, id));
                    transaction.db.commit();
                    return id;
                }
            });
            await(probeChecked);
            awaitAdvisoryWait(result(pid), writer);
            finishLocationReplacement(replacement.db);
            replacement.db.commit();
        }
        long id = result(writer);
        assertReplacementComplete();
        assertEquals(new Snapshot(create ? 1 : 2, 2, create ? 5 : 20, 2), snapshot(admin, id));
        assertEquals(create ? 4 : 3, scalar(admin, "SELECT count(*) FROM organizations"));
    }

    @Test void locationReplacementInvalidatesANewOrganizationCommittedBeforeExclusiveGuard() throws Exception {
        writerBeforeExclusiveLocation(true);
    }

    @Test void locationReplacementInvalidatesExistingLinkUpdateCommittedBeforeExclusiveGuard() throws Exception {
        writerBeforeExclusiveLocation(false);
    }

    private void writerBeforeExclusiveLocation(boolean create) throws Exception {
        var pid = new CompletableFuture<Integer>();
        var probeChecked = new CountDownLatch(1);
        Future<Boolean> replacement;
        long id;
        long draftVersion;
        try (var writer = transaction()) {
            guard(writer.db, false);
            assertEquals(1, scalar(writer.db, "SELECT town_id FROM addresses WHERE id=1"));
            if (create) id = createUsingSourceAddress(writer.db);
            else {
                linkExistingToSourceAddress(writer.db);
                id = 2;
            }
            Snapshot draft = snapshot(writer.db, id);
            draftVersion = draft.version;
            assertEquals(new Snapshot(create ? 1 : 2, 1, create ? 5 : 20, 1), draft);
            replacement = submit(() -> {
                try (var transaction = transaction()) {
                    pid.complete((int) scalar(transaction.db, "SELECT pg_backend_pid()"));
                    assertFalse(tryGuard(transaction.db, true), "Exclusive rebind must wait for the ordinary writer");
                    probeChecked.countDown();
                    guard(transaction.db, true);
                    bumpLocationDependents(transaction.db);
                    finishLocationReplacement(transaction.db);
                    transaction.db.commit();
                    return true;
                }
            });
            await(probeChecked);
            awaitAdvisoryWait(result(pid), replacement);
            writer.db.commit();
        }
        assertTrue(result(replacement));
        assertReplacementComplete();
        assertEquals(new Snapshot(draftVersion + 1, 2, create ? 5 : 20, 2), snapshot(admin, id));
        assertEquals(0, update(admin, "UPDATE organizations SET name='stale form',version=version+1 "
                + "WHERE id=" + id + " AND version=" + draftVersion), "Old DTO must fail its optimistic version predicate");
    }

    private static long merge(Connection connection, int first, int second) throws SQLException {
        return scalar(connection, "SELECT lab1_merge_organizations(" + first + "," + second
                + ",'merged',1,NULL,NULL,NULL,NULL,NULL,NULL)");
    }

    @Test void completedButUncommittedMergeBlocksAnotherApiSharedWriter() throws Exception {
        var pid = new CompletableFuture<Integer>();
        var probeChecked = new CountDownLatch(1);
        Future<Snapshot> writer;
        try (var merger = transaction()) {
            long merged = merge(merger.db, 1, 2); // Function itself must take an exclusive xact guard first.
            assertEquals(30, snapshot(merger.db, merged).employees);
            assertEquals(0, scalar(merger.db, "SELECT count(*) FROM organizations WHERE id IN (1,2)"));
            writer = submit(() -> {
                try (var transaction = transaction()) {
                    pid.complete((int) scalar(transaction.db, "SELECT pg_backend_pid()"));
                    assertFalse(tryGuard(transaction.db, false), "Merge must retain its exclusive guard until commit");
                    probeChecked.countDown();
                    guard(transaction.db, false);
                    assertEquals(1, update(transaction.db, "UPDATE organizations SET employees_count=employees_count+1,"
                            + "version=version+1 WHERE id=3 AND version=1"));
                    Snapshot current = snapshot(transaction.db, 3);
                    transaction.db.commit();
                    return current;
                }
            });
            await(probeChecked);
            awaitAdvisoryWait(result(pid), writer);
            assertEquals(new Snapshot(1, 2, 30, null), snapshot(admin, 3));
            merger.db.commit();
        }
        assertEquals(new Snapshot(2, 2, 31, null), result(writer));
        assertEquals(new Snapshot(2, 2, 31, null), snapshot(admin, 3));
    }

    @Test void nativeAddEmployeeAlsoAcquiresTheSharedGuardBeforeItsUpdate() throws Exception {
        var pid = new CompletableFuture<Integer>();
        Future<Long> employee;
        try (var exclusive = transaction()) {
            guard(exclusive.db, true);
            employee = submit(() -> {
                try (var transaction = transaction()) {
                    pid.complete((int) scalar(transaction.db, "SELECT pg_backend_pid()"));
                    long id = scalar(transaction.db, "SELECT lab1_add_employee(3)");
                    transaction.db.commit();
                    return id;
                }
            });
            awaitAdvisoryWait(result(pid), employee);
            assertEquals(new Snapshot(1, 2, 30, null), snapshot(admin, 3));
            exclusive.db.commit();
        }
        assertEquals(3L, result(employee));
        assertEquals(new Snapshot(2, 2, 31, null), snapshot(admin, 3));
    }

    private record MergeOutcome(Long id, String state) {}

    @Test void concurrentReversedMergesHaveOneWinnerOneMissingSourceAndNoDeadlock() throws Exception {
        concurrentMerges(1, 2, 2, 1);
    }

    @Test void concurrentMergesSharingOneSourceHaveOneWinnerOneMissingSourceAndNoDeadlock() throws Exception {
        concurrentMerges(1, 2, 2, 3);
    }

    private void concurrentMerges(int firstA, int secondA, int firstB, int secondB) throws Exception {
        var start = new CyclicBarrier(3);
        var winnerReady = new CountDownLatch(1);
        var releaseWinner = new CountDownLatch(1);
        var winner = new AtomicInteger(-1);
        var pids = new AtomicIntegerArray(2);
        List<Future<MergeOutcome>> merges = new ArrayList<>();
        int[][] inputs = {{firstA, secondA}, {firstB, secondB}};
        for (int index = 0; index < 2; index++) {
            final int slot = index;
            merges.add(submit(() -> {
                try (var transaction = transaction()) {
                    pids.set(slot, (int) scalar(transaction.db, "SELECT pg_backend_pid()"));
                    start.await(15, TimeUnit.SECONDS);
                    try {
                        long id = merge(transaction.db, inputs[slot][0], inputs[slot][1]);
                        assertTrue(winner.compareAndSet(-1, slot), "Only one merge may consume shared source rows");
                        winnerReady.countDown();
                        await(releaseWinner); // Keep first merge's exclusive guard until loser is really waiting.
                        transaction.db.commit();
                        return new MergeOutcome(id, null);
                    } catch (SQLException failure) {
                        transaction.db.rollback();
                        return new MergeOutcome(null, failure.getSQLState());
                    }
                }
            }));
        }
        try {
            start.await(15, TimeUnit.SECONDS);
            await(winnerReady);
            int loser = 1 - winner.get();
            awaitAdvisoryWait(pids.get(loser), merges.get(loser));
        } finally {
            releaseWinner.countDown();
        }
        MergeOutcome a = result(merges.get(0));
        MergeOutcome b = result(merges.get(1));
        assertEquals(1, List.of(a, b).stream().filter(outcome -> outcome.id != null).count());
        assertEquals(1, List.of(a, b).stream().filter(outcome -> "P1005".equals(outcome.state)).count(),
                "A competing merge must report the consumed source, not deadlock, timeout, or succeed twice");
        assertEquals(2, scalar(admin, "SELECT count(*) FROM organizations"));
        assertEquals(60, scalar(admin, "SELECT sum(employees_count) FROM organizations"), "Employees must be conserved");
        assertEquals(2, scalar(admin, "SELECT count(*) FROM addresses"));
        assertEquals(2, scalar(admin, "SELECT count(*) FROM locations"));
        assertEquals(1, scalar(admin, "SELECT count(*) FROM coordinates"));
        MergeOutcome success = a.id != null ? a : b;
        assertEquals(1, snapshot(admin, success.id).version);
    }

    @Test void mergeWaitingForAWriterReadsBothSourcesAfterTheWritersCommit() throws Exception {
        var pid = new CompletableFuture<Integer>();
        Future<Long> merger;
        try (var writer = transaction()) {
            guard(writer.db, false);
            assertEquals(1, update(writer.db, "UPDATE organizations SET employees_count=15,annual_turnover=15,"
                    + "version=version+1 WHERE id=1"));
            merger = submit(() -> {
                try (var transaction = transaction()) {
                    pid.complete((int) scalar(transaction.db, "SELECT pg_backend_pid()"));
                    long id = merge(transaction.db, 1, 2);
                    transaction.db.commit();
                    return id;
                }
            });
            awaitAdvisoryWait(result(pid), merger);
            // Complete the second half while merge is definitely waiting at its first guard.
            assertEquals(1, update(writer.db, "UPDATE organizations SET employees_count=25,annual_turnover=25,"
                    + "version=version+1 WHERE id=2"));
            writer.db.commit();
        }
        long merged = result(merger);
        assertEquals(40, snapshot(admin, merged).employees, "Do not combine source rows from different snapshots");
        assertEquals(40, scalar(admin, "SELECT annual_turnover FROM organizations WHERE id=" + merged));
        assertEquals(0, scalar(admin, "SELECT count(*) FROM organizations WHERE id IN (1,2)"));
        assertEquals(2, scalar(admin, "SELECT count(*) FROM organizations"));
        assertEquals(new Snapshot(1, 2, 30, null), snapshot(admin, 3));
    }
}
