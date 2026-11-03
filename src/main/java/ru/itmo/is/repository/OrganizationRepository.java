package ru.itmo.is.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import ru.itmo.is.dto.OrganizationOptionDto;
import ru.itmo.is.exception.ApiValidationException;
import ru.itmo.is.model.Organization;
import ru.itmo.is.model.OrganizationType;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class OrganizationRepository {
    private static final String FROM_WITH_JOINS = """
            from Organization o
            join o.officialAddress oa
            join oa.town ot
            left join o.postalAddress pa
            left join pa.town pt
            """;

    private static final Map<String, String> STRING_PATHS = Map.of(
            "name", "o.name",
            "officialZip", "oa.zipCode",
            "officialTown", "ot.name",
            "postalZip", "pa.zipCode",
            "postalTown", "pt.name"
    );

    private static final Map<String, String> SORT_PATHS = Map.of(
            "id", "o.id",
            "name", "o.name",
            "type", "o.type",
            "officialZip", "oa.zipCode",
            "officialTown", "ot.name",
            "postalZip", "pa.zipCode",
            "postalTown", "pt.name"
    );

    @PersistenceContext(unitName = "lab1PU")
    EntityManager em;

    @jakarta.transaction.Transactional(jakarta.transaction.Transactional.TxType.MANDATORY)
    public void beginPageSnapshot() {
        em.createNativeQuery("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY")
                .executeUpdate();
    }

    @jakarta.transaction.Transactional(jakarta.transaction.Transactional.TxType.MANDATORY)
    public List<OrganizationOptionDto> findOptions() {
        return em.createQuery("select new ru.itmo.is.dto.OrganizationOptionDto(o.id, o.name, o.employeesCount) "
                        + "from Organization o order by o.id asc", OrganizationOptionDto.class)
                .getResultList();
    }

    public List<Organization> findAll(int page, int size,
                                      String filterField, String filterValue,
                                      String sortField, String direction) {
        QueryFilter filter = buildFilter(filterField, filterValue);
        String sortExpression = resolveSort(sortField);
        String sortDirection = resolveDirection(direction);

        String jpql = "select o " + FROM_WITH_JOINS
                + (filter.clause().isEmpty() ? "" : " where " + filter.clause())
                + " order by " + sortExpression + " " + sortDirection + ", o.id asc";

        TypedQuery<Organization> query = em.createQuery(jpql, Organization.class)
                .setFirstResult(page * size)
                .setMaxResults(size);
        applyFilterParameter(query, filter);
        return query.getResultList();
    }

    public long count(String filterField, String filterValue) {
        QueryFilter filter = buildFilter(filterField, filterValue);
        String jpql = "select count(o) " + FROM_WITH_JOINS
                + (filter.clause().isEmpty() ? "" : " where " + filter.clause());

        TypedQuery<Long> query = em.createQuery(jpql, Long.class);
        applyFilterParameter(query, filter);
        return query.getSingleResult();
    }

    public Organization findById(int id) {
        return em.find(Organization.class, id);
    }

    public void checkVersion(Organization organization) {
        em.lock(organization, jakarta.persistence.LockModeType.OPTIMISTIC);
    }

    public void persist(Organization organization) {
        em.persist(organization);
    }

    public void remove(Organization organization) {
        em.remove(organization);
    }

    public void flush() {
        em.flush();
    }

    public void clear() {
        em.clear();
    }

    public void evict(int id) {
        em.getEntityManagerFactory().getCache().evict(Organization.class, id);
    }

    public static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private QueryFilter buildFilter(String field, String value) {
        if (value == null || value.isBlank()) {
            return new QueryFilter("", null);
        }

        String normalizedField = field == null || field.isBlank() ? "name" : field;
        String term = value.trim().toLowerCase(java.util.Locale.ROOT);

        if ("type".equals(normalizedField)) {
            List<OrganizationType> types = Arrays.stream(OrganizationType.values())
                    .filter(type -> type.name().toLowerCase(java.util.Locale.ROOT).contains(term))
                    .toList();
            if (types.isEmpty()) {
                return new QueryFilter("1 = 0", null);
            }
            return new QueryFilter("o.type in :filterTypes", types);
        }

        String path = STRING_PATHS.get(normalizedField);
        if (path == null) {
            throw new ApiValidationException(List.of("filterField: unsupported field"));
        }
        return new QueryFilter("lower(" + path + ") like :filterValue escape '!'", "%" + escapeLike(term) + "%");
    }

    private <T> void applyFilterParameter(TypedQuery<T> query, QueryFilter filter) {
        if (filter.parameter() instanceof String value) {
            query.setParameter("filterValue", value);
        } else if (filter.parameter() instanceof List<?> values) {
            query.setParameter("filterTypes", values);
        }
    }

    private String resolveSort(String field) {
        String normalized = field == null || field.isBlank() ? "id" : field;
        String path = SORT_PATHS.get(normalized);
        if (path == null) {
            throw new ApiValidationException(List.of("sort: unsupported field"));
        }
        return path;
    }

    private String resolveDirection(String direction) {
        if (direction == null || direction.isBlank() || "asc".equalsIgnoreCase(direction)) {
            return "asc";
        }
        if ("desc".equalsIgnoreCase(direction)) {
            return "desc";
        }
        throw new ApiValidationException(List.of("direction: must be asc or desc"));
    }

    private record QueryFilter(String clause, Object parameter) {
    }
}
