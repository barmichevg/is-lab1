package ru.itmo.is.service;

import jakarta.persistence.Cacheable;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;
import ru.itmo.is.dto.OrganizationUpsertDto;
import ru.itmo.is.dto.RequestParser;
import ru.itmo.is.exception.ApiValidationException;
import ru.itmo.is.mapper.OrganizationMapper;
import ru.itmo.is.model.*;
import ru.itmo.is.repository.OrganizationRepository;
import ru.itmo.is.repository.ReferenceDataRepository;

import static org.junit.jupiter.api.Assertions.*;

/** Service contract tests; real provider/database races are covered over HTTP in ApiIT. */
class OrganizationConcurrencyTest {
    static OrganizationUpsertDto input(Long version) {
        var input=RequestParser.organization("{\"name\":\"edited\",\"annualTurnover\":10,\"employeesCount\":5,\"rating\":1,\"type\":\"COMMERCIAL\",\"coordinates\":{\"id\":1},\"officialAddress\":{\"id\":1}}");
        input.setVersion(version);
        return input;
    }

    static Organization organization(long version) throws Exception {
        var o=new Organization();o.setId(1);o.setName("original");o.setEmployeesCount(6);
        var field=Organization.class.getDeclaredField("version");field.setAccessible(true);field.setLong(o,version);
        return o;
    }

    static class Repository extends OrganizationRepository {
        final Organization existing;
        boolean loaded,checked,flushed;
        RuntimeException flushFailure;
        Repository(Organization existing){this.existing=existing;}
        @Override public Organization findById(int id){loaded=true;return existing;}
        @Override public void checkVersion(Organization o){assertSame(existing,o);checked=true;}
        @Override public void flush(){flushed=true;if(flushFailure!=null)throw flushFailure;}
    }

    static class References extends ReferenceDataRepository {
        int resolved;
        @Override public Coordinates findCoordinates(int id){resolved++;var c=new Coordinates();c.setId(id);c.setX(1);c.setY(2);return c;}
        @Override public Address findAddress(int id){resolved++;var a=new Address();a.setId(id);a.setZipCode("zip");a.setTown(new Location());return a;}
    }

    static OrganizationService service(Repository repository,References references) {
        var service=new OrganizationService();service.organizationRepository=repository;
        service.referenceDataRepository=references;service.validator=new OrganizationRequestValidator();
        service.mapper=new OrganizationMapper();
        service.mutationLock=new ru.itmo.is.repository.MutationLock(){@Override public void shared(){}};
        service.notifier=new OrganizationChangeNotifier(){@Override public void afterCommit(String type,Integer id){}};
        return service;
    }

    @Test void missingVersionIsRejectedWithoutLoadingCurrentVersion() throws Exception {
        var repository=new Repository(organization(8));var references=new References();
        var error=assertThrows(ApiValidationException.class,()->service(repository,references).update(1,input(null)));
        assertTrue(error.getErrors().get(0).startsWith("version:"));
        assertFalse(repository.loaded);assertFalse(repository.flushed);assertEquals(0,references.resolved);
    }

    @Test void staleAndFutureVersionsCannotModifyFieldsOrResolveReferences() throws Exception {
        for(long supplied:new long[]{7,9}) {
            var existing=organization(8);var repository=new Repository(existing);var references=new References();
            assertThrows(OptimisticLockException.class,()->service(repository,references).update(1,input(supplied)));
            assertEquals("original",existing.getName());assertEquals(6,existing.getEmployeesCount());
            assertFalse(repository.checked);assertFalse(repository.flushed);assertEquals(0,references.resolved);
        }
    }

    @Test void concurrentChangeAfterComparisonPropagatesProviderOptimisticFailure() throws Exception {
        var repository=new Repository(organization(8));var references=new References();
        var providerFailure=new OptimisticLockException("changed during flush");repository.flushFailure=providerFailure;
        assertSame(providerFailure,assertThrows(OptimisticLockException.class,()->service(repository,references).update(1,input(8L))));
        assertTrue(repository.checked);assertTrue(repository.flushed);assertEquals(2,references.resolved);
    }

    @Test void currentVersionUpdatesAndReturnsExactStringToken() throws Exception {
        long version=9007199254740993L;var repository=new Repository(organization(version));var references=new References();
        var result=service(repository,references).update(1,input(version));
        assertEquals("edited",result.getName());assertEquals("5",result.getEmployeesCount());
        assertEquals("9007199254740993",result.getVersion());assertTrue(repository.checked);assertTrue(repository.flushed);
    }

    @Test void ormOwnsVersionAndOrganizationDoesNotUseSharedCache() throws Exception {
        assertNotNull(Organization.class.getDeclaredField("version").getAnnotation(Version.class));
        assertFalse(Organization.class.getAnnotation(Cacheable.class).value());
    }
}
