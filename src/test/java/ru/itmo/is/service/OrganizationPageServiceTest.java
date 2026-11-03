package ru.itmo.is.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import ru.itmo.is.dto.OrganizationResponseDto;
import ru.itmo.is.mapper.OrganizationMapper;
import ru.itmo.is.model.Organization;
import ru.itmo.is.repository.OrganizationRepository;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrganizationPageServiceTest {
    static class Repository extends OrganizationRepository {
        final List<String> events = new ArrayList<>();
        final List<Organization> items = new ArrayList<>();
        long total;
        int page, size;
        String filterField, filterValue, sortField, direction;

        @Override public void beginPageSnapshot() { events.add("snapshot"); }
        @Override public long count(String field, String value) {
            assertEquals(List.of("snapshot"), events);
            filterField = field;
            filterValue = value;
            events.add("count");
            return total;
        }
        @Override public List<Organization> findAll(int page, int size, String field, String value,
                                                   String sort, String direction) {
            assertEquals(List.of("snapshot", "count"), events);
            assertEquals(filterField, field);
            assertEquals(filterValue, value);
            this.page = page;
            this.size = size;
            sortField = sort;
            this.direction = direction;
            events.add("items");
            return items;
        }
    }

    @Test void initializesNewSnapshotBeforeQueriesAndMapsEveryEntityBeforeReturning() {
        var repository = new Repository();
        repository.total = 11;
        var entity = new Organization();
        entity.setId(17);
        repository.items.add(entity);
        var service = service(repository);
        service.mapper = new OrganizationMapper() {
            @Override public OrganizationResponseDto toDto(Organization organization) {
                assertEquals(List.of("snapshot", "count", "items"), repository.events);
                repository.events.add("nested DTO mapping");
                var dto = new OrganizationResponseDto();
                dto.setId(organization.getId());
                return dto;
            }
        };
        var result = service.page(1, 10, "postalTown", " %_!Казань ", "postalZip", "desc");
        assertEquals(List.of("snapshot", "count", "items", "nested DTO mapping"), repository.events);
        assertEquals(11, result.getTotal());
        assertEquals(17, result.getItems().get(0).getId());
        assertEquals(1, result.getPage());
        assertEquals(10, result.getSize());
        assertEquals("postalTown", repository.filterField);
        assertEquals(" %_!Казань ", repository.filterValue);
        assertEquals("postalZip", repository.sortField);
        assertEquals("desc", repository.direction);
        assertThrows(UnsupportedOperationException.class, () -> result.getItems().clear());
    }

    @Test void emptyDatasetAndOutOfRangePageRetainTheirIndependentTotal() {
        for (long total : new long[]{0, 23}) {
            var repository = new Repository();
            repository.total = total;
            var result = service(repository).page(999, 10, "type", "company", "officialTown", "asc");
            assertTrue(result.getItems().isEmpty());
            assertEquals(total, result.getTotal());
            assertEquals(999, result.getPage());
            assertEquals(10, result.getSize());
            assertEquals(List.of("snapshot", "count", "items"), repository.events);
            // Still run findAll so sort validation is not silently bypassed for empty pages.
            assertEquals("officialTown", repository.sortField);
        }
    }

    @Test void pageUsesRequiresNewAndRepositoryInitializationRequiresTransaction() throws Exception {
        var page = OrganizationPageService.class.getMethod("page", int.class, int.class,
                String.class, String.class, String.class, String.class);
        assertEquals(Transactional.TxType.REQUIRES_NEW, page.getAnnotation(Transactional.class).value());
        assertEquals(Transactional.TxType.MANDATORY,
                OrganizationRepository.class.getMethod("beginPageSnapshot")
                        .getAnnotation(Transactional.class).value());
    }

    @Test void initializationExecutesOnlyTransactionLocalReadOnlyRepeatableReadStatement() throws Exception {
        var events = new ArrayList<String>();
        Query query = (Query) Proxy.newProxyInstance(Query.class.getClassLoader(), new Class<?>[]{Query.class},
                (proxy, method, args) -> {
                    assertEquals("executeUpdate", method.getName());
                    events.add("execute");
                    return 0;
                });
        EntityManager em = (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(),
                new Class<?>[]{EntityManager.class}, (proxy, method, args) -> {
                    assertEquals("createNativeQuery", method.getName());
                    assertEquals("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY", args[0]);
                    events.add("statement");
                    return query;
                });
        var repository = new OrganizationRepository();
        var field = OrganizationRepository.class.getDeclaredField("em");
        field.setAccessible(true);
        field.set(repository, em);
        repository.beginPageSnapshot();
        assertEquals(List.of("statement", "execute"), events);
    }

    @Test void resourceUsesDedicatedBeanAndPreservesLegacyAliasAndPageLimits() throws Exception {
        var resource = new ru.itmo.is.resource.OrganizationResource();
        var seen = new ArrayList<Object>();
        var bean = new OrganizationPageService() {
            @Override public ru.itmo.is.dto.OrganizationPageDto page(int page, int size,
                    String filterField, String filterValue, String sort, String direction) {
                seen.addAll(List.of(page, size, filterField, filterValue, sort, direction));
                return new ru.itmo.is.dto.OrganizationPageDto(List.of(), 27, page, size);
            }
        };
        var field = resource.getClass().getDeclaredField("pageService");
        field.setAccessible(true);
        field.set(resource, bean);
        var result = resource.list(-8, 1000, "name", "", "postalTown", "desc", "%_!legacy");
        assertEquals(List.of(0, 100, "name", "%_!legacy", "postalTown", "desc"), seen);
        assertEquals(27, result.getTotal());
        assertTrue(result.getItems().isEmpty());
        assertThrows(jakarta.ws.rs.BadRequestException.class,
                () -> resource.list(Integer.MAX_VALUE, 100, "name", "", "id", "asc", null));
        assertEquals(6, seen.size(), "Invalid offset must fail before entering the page service");
    }

    static OrganizationPageService service(Repository repository) {
        var service = new OrganizationPageService();
        service.repository = repository;
        service.mapper = new OrganizationMapper();
        return service;
    }
}
