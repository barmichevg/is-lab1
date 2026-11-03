package ru.itmo.is.service;

import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import ru.itmo.is.repository.MutationLock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Fail at the first DB lock: no entity repository/validator has been injected. */
class MutationLockOrderingTest {
    static final class Acquired extends RuntimeException {}

    @Test void everyMutationAcquiresCorrectLockBeforeAnyEntityRead() throws Exception {
        Object[][] entries = {
                {new OrganizationService(), "create", new Class<?>[]{ru.itmo.is.dto.OrganizationUpsertDto.class}, new Object[]{null}, "shared"},
                {new OrganizationService(), "update", new Class<?>[]{int.class, ru.itmo.is.dto.OrganizationUpsertDto.class}, new Object[]{1, null}, "shared"},
                {new OrganizationService(), "delete", new Class<?>[]{int.class}, new Object[]{1}, "shared"},
                {new ReferenceDataService(), "deleteCoordinates", new Class<?>[]{int.class, Integer.class}, new Object[]{1, 2}, "exclusive"},
                {new ReferenceDataService(), "deleteAddress", new Class<?>[]{int.class, Integer.class}, new Object[]{1, 2}, "exclusive"},
                {new ReferenceDataService(), "deleteLocation", new Class<?>[]{int.class, Integer.class}, new Object[]{1, 2}, "exclusive"},
                {new SpecialOperationsService(), "merge", new Class<?>[]{ru.itmo.is.dto.MergeOrganizationsRequestDto.class}, new Object[]{null}, "exclusive"},
                {new SpecialOperationsService(), "addEmployee", new Class<?>[]{int.class}, new Object[]{1}, "shared"}
        };
        for (Object[] entry : entries) {
            Object service = entry[0];
            var acquired = new AtomicReference<String>();
            var stop = new Acquired();
            Field field = service.getClass().getDeclaredField("mutationLock");
            field.setAccessible(true);
            field.set(service, new MutationLock() {
                @Override public void shared() { acquired.set("shared"); throw stop; }
                @Override public void exclusive() { acquired.set("exclusive"); throw stop; }
            });
            Method method = service.getClass().getMethod((String) entry[1], (Class<?>[]) entry[2]);
            assertNotNull(method.getAnnotation(Transactional.class), method.toString());
            var error = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> method.invoke(service, (Object[]) entry[3]));
            assertSame(stop, error.getCause(), "DB/entity access happened before mutation lock: " + method);
            assertEquals(entry[4], acquired.get(), method.toString());
        }
    }

    @Test void sharedReferencesCannotBeLoadedFromStaleSecondLevelCache() throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var xml = factory.newDocumentBuilder().parse(
                java.nio.file.Path.of("src/main/resources/META-INF/persistence.xml").toFile());
        var modes = xml.getElementsByTagNameNS("https://jakarta.ee/xml/ns/persistence", "shared-cache-mode");
        assertEquals(1, modes.getLength());
        assertEquals("NONE", modes.item(0).getTextContent().trim());
    }

    @Test void lockRequiresExistingServiceTransaction() throws Exception {
        for (String name : new String[]{"shared", "exclusive"}) {
            assertEquals(Transactional.TxType.MANDATORY,
                    MutationLock.class.getMethod(name).getAnnotation(Transactional.class).value());
        }
    }
}
