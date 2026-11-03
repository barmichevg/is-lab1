package ru.itmo.is;

import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Own temporary schema; never truncates the application's tables. */
class DatabaseIT {
    static Connection db;
    static String schema;
    static boolean schemaCreated;

    static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " required for integration tests");
        return value;
    }

    @BeforeAll static void setup() throws Exception {
        db = DriverManager.getConnection(env("TEST_DB_URL"), env("TEST_DB_USER"), env("TEST_DB_PASSWORD"));
        schema = "lab1_test_" + UUID.randomUUID().toString().replace("-", "");
        try {
            execute("SET statement_timeout TO '15s'");
            execute("CREATE SCHEMA " + schema);
            schemaCreated = true; // Never drop a schema unless this connection created it successfully.
            execute("SET search_path TO " + schema);
            execute(Files.readString(Path.of("db/schema.sql")));
            execute(Files.readString(Path.of("db/schema.sql"))); // Repeatable migration.
        } catch (Exception failure) {
            try { cleanup(); } catch (Exception cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    @AfterAll static void cleanup() throws Exception {
        if (db == null) return;
        try {
            if (schemaCreated && !db.isClosed()) {
                if (!db.getAutoCommit()) {
                    db.rollback();
                    db.setAutoCommit(true);
                }
                execute("DROP SCHEMA " + schema + " CASCADE");
                schemaCreated = false;
            }
        } finally {
            db.close();
            db = null;
        }
    }

    static void execute(String sql) throws SQLException {
        try (var statement = db.createStatement()) { statement.execute(sql); }
    }

    static long scalar(String sql) throws SQLException {
        try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    static SQLException state(String expected, String sql) {
        SQLException failure = assertThrows(SQLException.class, () -> execute(sql));
        assertEquals(expected, failure.getSQLState(), failure.getMessage());
        return failure;
    }

    @BeforeEach void seed() throws Exception {
        assertTrue(schemaCreated);
        try (var statement = db.createStatement(); var result = statement.executeQuery("SELECT current_schema()")) {
            assertTrue(result.next());
            assertEquals(schema, result.getString(1), "TRUNCATE is allowed only in this class's own schema");
        }
        execute("TRUNCATE organizations,addresses,coordinates,locations RESTART IDENTITY CASCADE");
        execute("INSERT INTO coordinates(x,y) VALUES(1,2);"
                + "INSERT INTO locations(x,y,z,name) VALUES(1,2,3,'town');"
                + "INSERT INTO addresses(zip_code,town_id) VALUES('zip',1)");
        execute("INSERT INTO organizations(name,coordinates_id,official_address_id,annual_turnover,employees_count,rating,type) "
                + "VALUES ('%_!literal',1,1,10,2,1,'COMMERCIAL'),('other',1,1,20,3,2,'GOVERNMENT')");
    }

    @Test void allFiveFunctionsAndLiteralPrefix() throws Exception {
        assertEquals(1, scalar("SELECT id FROM lab1_organization_with_min_employees()"));
        assertEquals(1, scalar("SELECT count(*) FROM lab1_organizations_name_starts_with('%_!')"));
        assertEquals(0, scalar("SELECT count(*) FROM lab1_organizations_name_starts_with('_')"));
        assertEquals(1, scalar("SELECT count(*) FROM lab1_organizations_type_greater_than('COMMERCIAL')"));
        assertEquals(1, scalar("SELECT lab1_add_employee(1)"));
        assertEquals(3, scalar("SELECT employees_count FROM organizations WHERE id=1"));
        assertEquals(2, scalar("SELECT version FROM organizations WHERE id=1"));
        scalar("SELECT lab1_add_employee(1)");
        assertEquals(3, scalar("SELECT version FROM organizations WHERE id=1"));

        long merged = scalar("SELECT lab1_merge_organizations(1,2,'merged',1,NULL,NULL,NULL,NULL,NULL,NULL)");
        assertNotEquals(1, merged);
        assertNotEquals(2, merged);
        assertEquals(7, scalar("SELECT employees_count FROM organizations WHERE id=" + merged));
        assertEquals(1, scalar("SELECT version FROM organizations WHERE id=" + merged));
        assertEquals(0, scalar("SELECT count(*) FROM organizations WHERE id IN (1,2)"));
        assertEquals(1, scalar("SELECT count(*) FROM organizations"));
        assertEquals(1, scalar("SELECT count(*) FROM addresses"));
        assertEquals(1, scalar("SELECT count(*) FROM locations"));
        assertEquals(1, scalar("SELECT count(*) FROM coordinates"));
    }

    @Test void mergeDeletesOnlySourcesAndPreservesTheirNoLongerReferencedObjects() throws Exception {
        execute("INSERT INTO coordinates(x,y) VALUES(3,4);"
                + "INSERT INTO locations(x,y,z,name) VALUES(4,5,6,'second town');"
                + "INSERT INTO addresses(zip_code,town_id) VALUES('second zip',2);"
                + "UPDATE organizations SET coordinates_id=2,official_address_id=2,postal_address_id=2 WHERE id=2");
        long merged = scalar("SELECT lab1_merge_organizations(1,2,'merged',1,NULL,NULL,NULL,NULL,NULL,NULL)");
        assertEquals(0, scalar("SELECT count(*) FROM organizations WHERE id IN (1,2)"));
        assertEquals(1, scalar("SELECT coordinates_id FROM organizations WHERE id=" + merged));
        assertEquals(1, scalar("SELECT official_address_id FROM organizations WHERE id=" + merged));
        assertEquals(1, scalar("SELECT count(*) FROM organizations WHERE id=" + merged + " AND postal_address_id IS NULL"));
        assertEquals(2, scalar("SELECT count(*) FROM coordinates"));
        assertEquals(2, scalar("SELECT count(*) FROM addresses"));
        assertEquals(2, scalar("SELECT count(*) FROM locations"));
        assertEquals(1, scalar("SELECT count(*) FROM coordinates WHERE id=2 AND x=3 AND y=4"));
        assertEquals(1, scalar("SELECT count(*) FROM addresses WHERE id=2 AND town_id=2"));
    }

    @Test void repeatableVersionMigrationPreservesExistingVersionsAndRepairsLegacyRows() throws Exception {
        execute("ALTER TABLE organizations DROP COLUMN version");
        execute(Files.readString(Path.of("db/schema.sql")));
        assertEquals(2, scalar("SELECT count(*) FROM organizations WHERE version=1"));
        execute("ALTER TABLE organizations ALTER COLUMN version DROP NOT NULL; "
                + "ALTER TABLE organizations ALTER COLUMN version DROP DEFAULT");
        execute("UPDATE organizations SET version=77 WHERE id=1; UPDATE organizations SET version=NULL WHERE id=2");
        execute(Files.readString(Path.of("db/schema.sql")));
        execute(Files.readString(Path.of("db/schema.sql")));
        assertEquals(77, scalar("SELECT version FROM organizations WHERE id=1"));
        assertEquals(1, scalar("SELECT version FROM organizations WHERE id=2"));
        state("23502", "UPDATE organizations SET version=NULL WHERE id=1");
        long merged = scalar("SELECT lab1_merge_organizations(1,2,'versioned merge',1,NULL,NULL,NULL,NULL,NULL,NULL)");
        assertEquals(1, scalar("SELECT version FROM organizations WHERE id=" + merged));
        assertEquals(0, scalar("SELECT count(*) FROM organizations WHERE id IN (1,2)"));
    }

    @Test void floatOverflowDuringMergeRollsBackSourcesAndNewReferences() throws Exception {
        execute("UPDATE organizations SET annual_turnover=3e38");
        state("P1003", "SELECT lab1_merge_organizations(1,2,'overflow',NULL,'new zip',NULL,1,2,3,'new town')");
        assertEquals(2, scalar("SELECT count(*) FROM organizations WHERE id IN (1,2) AND version=1"));
        assertEquals(5, scalar("SELECT sum(employees_count) FROM organizations"));
        assertEquals(1, scalar("SELECT count(*) FROM addresses"));
        assertEquals(1, scalar("SELECT count(*) FROM locations"));
    }

    @Test void constraintsAndEmployeesOverflowRollbackUseExactStates() throws Exception {
        for (String value : new String[]{"'Infinity'", "'NaN'", "0", "-1"}) {
            state("23514", "UPDATE organizations SET annual_turnover=" + value + " WHERE id=1");
        }
        state("23514", "UPDATE locations SET x='NaN'");
        state("23514", "UPDATE organizations SET rating=0");
        state("23514", "UPDATE organizations SET name=' '");
        state("23503", "UPDATE organizations SET coordinates_id=9999");
        execute("UPDATE organizations SET employees_count=9223372036854775807 WHERE id=1");
        state("P1001", "SELECT lab1_add_employee(1)");
        state("P1002", "SELECT lab1_merge_organizations(1,2,'overflow',NULL,'new zip',NULL,1,2,3,'new town')");
        assertEquals(2, scalar("SELECT count(*) FROM organizations WHERE id IN (1,2) AND version=1"));
        assertEquals(Long.MAX_VALUE, scalar("SELECT employees_count FROM organizations WHERE id=1"));
        assertEquals(3, scalar("SELECT employees_count FROM organizations WHERE id=2"));
        assertEquals(1, scalar("SELECT count(*) FROM addresses"));
        assertEquals(1, scalar("SELECT count(*) FROM locations"));
    }

    @Test void exhaustedVersionDoesNotPartiallyAddEmployee() throws Exception {
        execute("UPDATE organizations SET version=9223372036854775807 WHERE id=1");
        state("P1004", "SELECT lab1_add_employee(1)");
        assertEquals(2, scalar("SELECT employees_count FROM organizations WHERE id=1"));
        assertEquals(Long.MAX_VALUE, scalar("SELECT version FROM organizations WHERE id=1"));
    }

    @Test void missingSourcesAndReferencesHaveStableStatesAndPreserveSources() throws Exception {
        state("P1005", "SELECT lab1_add_employee(9999)");
        state("P1005", "SELECT lab1_merge_organizations(9999,2,'missing',1,NULL,NULL,NULL,NULL,NULL,NULL)");
        state("P1005", "SELECT lab1_merge_organizations(1,9999,'missing',1,NULL,NULL,NULL,NULL,NULL,NULL)");
        state("P1006", "SELECT lab1_merge_organizations(1,2,'missing',9999,NULL,NULL,NULL,NULL,NULL,NULL)");
        state("P1006", "SELECT lab1_merge_organizations(1,2,'missing',NULL,'new zip',9999,NULL,NULL,NULL,NULL)");
        assertEquals(2, scalar("SELECT count(*) FROM organizations WHERE id IN (1,2) AND version=1"));
        assertEquals(5, scalar("SELECT sum(employees_count) FROM organizations"));
        assertEquals(1, scalar("SELECT count(*) FROM addresses"));
        assertEquals(1, scalar("SELECT count(*) FROM locations"));
    }

    @Test void invalidMergeInputHasStableStateAndNoSideEffects() throws Exception {
        for (String arguments : new String[]{
                "1,1,'same',1,NULL,NULL,NULL,NULL,NULL,NULL",
                "NULL,2,'missing id',1,NULL,NULL,NULL,NULL,NULL,NULL",
                "1,2,' ',1,NULL,NULL,NULL,NULL,NULL,NULL",
                "1,2,'missing town fields',NULL,'new zip',NULL,1,2,NULL,'new town'"}) {
            state("P1007", "SELECT lab1_merge_organizations(" + arguments + ")");
        }
        assertEquals(2, scalar("SELECT count(*) FROM organizations WHERE id IN (1,2) AND version=1"));
        assertEquals(1, scalar("SELECT count(*) FROM addresses"));
        assertEquals(1, scalar("SELECT count(*) FROM locations"));
    }

    @Test void rollingBackSuccessfulMergeRestoresBothSourcesAndReferences() throws Exception {
        db.setAutoCommit(false);
        try {
            long merged = scalar("SELECT lab1_merge_organizations(1,2,'rolled back',NULL,'new zip',NULL,1,2,3,'new town')");
            assertEquals(1, scalar("SELECT count(*) FROM organizations WHERE id=" + merged));
            assertEquals(0, scalar("SELECT count(*) FROM organizations WHERE id IN (1,2)"));
            assertEquals(2, scalar("SELECT count(*) FROM addresses"));
            assertEquals(2, scalar("SELECT count(*) FROM locations"));
            db.rollback();
        } finally {
            db.rollback();
            db.setAutoCommit(true);
        }
        assertEquals(2, scalar("SELECT count(*) FROM organizations WHERE id IN (1,2) AND version=1"));
        assertEquals(5, scalar("SELECT sum(employees_count) FROM organizations"));
        assertEquals(1, scalar("SELECT count(*) FROM addresses"));
        assertEquals(1, scalar("SELECT count(*) FROM locations"));
    }
}
