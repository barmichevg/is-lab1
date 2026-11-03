package ru.itmo.is.model;

import jakarta.persistence.Column;
import jakarta.persistence.PrePersist;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class OrganizationCreationDateTest {
    @Test void generatedDateUsesMicrosecondPrecision() {
        LocalDateTime before = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        var organization = new Organization();
        organization.prePersist();
        LocalDateTime after = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        assertNotNull(organization.getCreationDate());
        assertEquals(0, organization.getCreationDate().getNano() % 1000);
        assertFalse(organization.getCreationDate().isBefore(before));
        assertFalse(organization.getCreationDate().isAfter(after));
    }

    @Test void preassignedDateIsTruncatedBeforeInsertWithoutRoundingToNextSecond() {
        var organization = new Organization();
        organization.setCreationDate(LocalDateTime.of(2026, 11, 1, 23, 59, 59, 999_999_999));
        organization.prePersist();
        assertEquals(LocalDateTime.of(2026, 11, 1, 23, 59, 59, 999_999_000), organization.getCreationDate());
    }

    @Test void dateAlreadyAtMicrosecondPrecisionIsPreserved() {
        var organization = new Organization();
        LocalDateTime supplied = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123_456_000);
        organization.setCreationDate(supplied);
        organization.prePersist();
        assertEquals(supplied, organization.getCreationDate());
    }

    @Test void repeatedPrePersistDoesNotReplaceGeneratedDate() {
        var organization = new Organization();
        organization.prePersist();
        LocalDateTime generated = organization.getCreationDate();
        organization.prePersist();
        assertEquals(generated, organization.getCreationDate());
    }

    @Test void lifecycleAndReadOnlyColumnContractRemainUnchanged() throws Exception {
        assertNotNull(Organization.class.getMethod("prePersist").getAnnotation(PrePersist.class));
        var column = Organization.class.getDeclaredField("creationDate").getAnnotation(Column.class);
        assertFalse(column.updatable());
        assertFalse(column.nullable());
        assertEquals("creation_date", column.name());
    }
}
