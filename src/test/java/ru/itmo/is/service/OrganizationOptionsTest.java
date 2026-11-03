package ru.itmo.is.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.servlet.annotation.WebFilter;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;
import ru.itmo.is.dto.OrganizationOptionDto;
import ru.itmo.is.dto.OrganizationResponseDto;
import ru.itmo.is.mapper.OrganizationMapper;
import ru.itmo.is.repository.OrganizationRepository;
import ru.itmo.is.resource.OrganizationResource;
import ru.itmo.is.security.AuthFilter;

import java.beans.Introspector;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrganizationOptionsTest {
    @Test void compactDtoExposesOnlyThreePropertiesAndPreservesExactLong() throws Exception {
        var dto = new OrganizationOptionDto(7, "Организация <&> №7", Long.MAX_VALUE);
        assertEquals(7, dto.getId());
        assertEquals("Организация <&> №7", dto.getName());
        assertEquals("9223372036854775807", dto.getEmployeesCount());
        var properties = Arrays.stream(Introspector.getBeanInfo(OrganizationOptionDto.class, Object.class)
                        .getPropertyDescriptors())
                .map(java.beans.PropertyDescriptor::getName).sorted().toList();
        assertEquals(List.of("employeesCount", "id", "name"), properties);
    }

    @Test void repositoryUsesOneScalarProjectionWithoutJoinsLimitsOffsetsOrEntityLoading() throws Exception {
        var rows = new ArrayList<OrganizationOptionDto>();
        for (int id = 1; id <= 201; id++) rows.add(new OrganizationOptionDto(id, "Org " + id, id));
        var calls = new ArrayList<String>();
        TypedQuery<?> query = (TypedQuery<?>) Proxy.newProxyInstance(TypedQuery.class.getClassLoader(),
                new Class<?>[]{TypedQuery.class}, (proxy, method, args) -> {
                    assertEquals("getResultList", method.getName(), "No offset/limit or per-row lookup is allowed");
                    calls.add("select");
                    return rows;
                });
        EntityManager em = (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(),
                new Class<?>[]{EntityManager.class}, (proxy, method, args) -> {
                    assertEquals("createQuery", method.getName());
                    assertEquals("select new ru.itmo.is.dto.OrganizationOptionDto(o.id, o.name, o.employeesCount) "
                            + "from Organization o order by o.id asc", args[0]);
                    assertEquals(OrganizationOptionDto.class, args[1]);
                    calls.add("projection");
                    return query;
                });
        var repository = new OrganizationRepository();
        inject(repository, "em", em);
        var result = repository.findOptions();
        assertEquals(List.of("projection", "select"), calls);
        assertSame(rows, result);
        assertEquals(201, result.size());
        assertEquals(101, result.get(100).getId());
    }

    @Test void serviceStartsReadSnapshotAndMaterializesCompactRowsWithoutCountOrMapper() {
        var events = new ArrayList<String>();
        var rows = new ArrayList<OrganizationOptionDto>();
        for (int id = 1; id <= 10_000; id++) rows.add(new OrganizationOptionDto(id, "Org " + id, Long.MAX_VALUE));
        var service = new OrganizationPageService();
        service.repository = new OrganizationRepository() {
            @Override public void beginPageSnapshot() { events.add("read-only snapshot"); }
            @Override public List<OrganizationOptionDto> findOptions() {
                assertEquals(List.of("read-only snapshot"), events);
                events.add("one scalar select");
                return rows;
            }
            @Override public long count(String field, String value) { fail("Selector does not need COUNT"); return 0; }
            @Override public List<ru.itmo.is.model.Organization> findAll(int page, int size, String field,
                    String value, String sort, String direction) { fail("No paginated entity graph reads"); return List.of(); }
        };
        service.mapper = new OrganizationMapper() {
            @Override public OrganizationResponseDto toDto(ru.itmo.is.model.Organization entity) {
                fail("No full DTO/entity mapping for selector rows"); return null;
            }
        };
        var result = service.options();
        assertEquals(List.of("read-only snapshot", "one scalar select"), events);
        assertEquals(10_000, result.size());
        assertEquals("9223372036854775807", result.get(9999).getEmployeesCount());
        assertThrows(UnsupportedOperationException.class, result::clear);
        rows.clear();
        assertEquals(10_000, result.size(), "Result must be fully materialized before returning");
    }

    @Test void optionsUsesRequiresNewAndProjectionRequiresTransaction() throws Exception {
        assertEquals(Transactional.TxType.REQUIRES_NEW,
                OrganizationPageService.class.getMethod("options").getAnnotation(Transactional.class).value());
        assertEquals(Transactional.TxType.MANDATORY,
                OrganizationRepository.class.getMethod("findOptions").getAnnotation(Transactional.class).value());
    }

    @Test void resourceHasLiteralGetPathAndDelegatesOnlyToSelectorService() throws Exception {
        var expected = List.of(new OrganizationOptionDto(13, "Option", 4));
        var resource = new OrganizationResource();
        inject(resource, "pageService", new OrganizationPageService() {
            @Override public List<OrganizationOptionDto> options() { return expected; }
        });
        assertSame(expected, resource.options());
        var endpoint = OrganizationResource.class.getMethod("options");
        assertEquals("/options", endpoint.getAnnotation(Path.class).value());
        assertNotNull(endpoint.getAnnotation(GET.class));
        assertEquals("/organizations", OrganizationResource.class.getAnnotation(Path.class).value());
        assertEquals("/{id}", OrganizationResource.class.getMethod("get", int.class).getAnnotation(Path.class).value());
        assertTrue(List.of(AuthFilter.class.getAnnotation(WebFilter.class).urlPatterns()).contains("/api/*"),
                "The existing authentication filter must continue covering the static selector endpoint");
    }

    @Test void emptySnapshotIsACompleteEmptyList() {
        var service = new OrganizationPageService();
        var events = new ArrayList<String>();
        service.repository = new OrganizationRepository() {
            @Override public void beginPageSnapshot() { events.add("snapshot"); }
            @Override public List<OrganizationOptionDto> findOptions() {
                assertEquals(List.of("snapshot"), events); return List.of();
            }
        };
        assertEquals(List.of(), service.options());
    }

    static void inject(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
