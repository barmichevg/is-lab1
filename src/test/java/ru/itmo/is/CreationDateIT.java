package ru.itmo.is;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Uses only HTTP and the same isolated-fixture cleanup as ApiIT. */
class CreationDateIT {
    private ApiIT api;

    @BeforeEach void setup() throws Exception {
        api = new ApiIT();
        api.setup();
    }

    @AfterEach void cleanup() throws Exception {
        if (api != null) api.cleanup();
    }

    @Test void creationResponseEqualsSubsequentGetIncludingDateAndVersion() throws Exception {
        for (int i = 0; i < 3; i++) {
            var created = api.create("creation-date-" + UUID.randomUUID(), "7");
            assertEquals(0, LocalDateTime.parse(created.getString("creationDate")).getNano() % 1000);
            assertEquals(created, api.get(created.getInt("id")), "POST DTO must equal committed GET DTO without removing creationDate");
            assertEquals(created, api.get(created.getInt("id")), "Repeated GET must preserve the complete DTO");
        }
    }
}
