package ru.itmo.is.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import ru.itmo.is.model.Organization;

import java.util.List;

@ApplicationScoped
public class SpecialOperationsRepository {
    @PersistenceContext(unitName = "lab1PU")
    EntityManager em;

    @SuppressWarnings("unchecked")
    public List<Organization> findWithMinEmployees() {
        return em.createNativeQuery(
                        "SELECT * FROM lab1_organization_with_min_employees()",
                        Organization.class)
                .getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<Organization> findNameStartsWith(String prefix) {
        return em.createNativeQuery(
                        "SELECT * FROM lab1_organizations_name_starts_with(CAST(?1 AS TEXT))",
                        Organization.class)
                .setParameter(1, prefix)
                .getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<Organization> findTypeGreaterThan(String typeName) {
        return em.createNativeQuery(
                        "SELECT * FROM lab1_organizations_type_greater_than(CAST(?1 AS TEXT))",
                        Organization.class)
                .setParameter(1, typeName)
                .getResultList();
    }

    public int mergeOrganizations(Integer firstOrganizationId,
                                  Integer secondOrganizationId,
                                  String name,
                                  Integer existingAddressId,
                                  String zipCode,
                                  Integer existingTownId,
                                  Double townX,
                                  Double townY,
                                  Integer townZ,
                                  String townName) {
        Object result = em.createNativeQuery("""
                SELECT lab1_merge_organizations(
                    CAST(?1 AS INTEGER), CAST(?2 AS INTEGER), CAST(?3 AS TEXT),
                    CAST(?4 AS INTEGER), CAST(?5 AS TEXT), CAST(?6 AS INTEGER),
                    CAST(?7 AS DOUBLE PRECISION), CAST(?8 AS DOUBLE PRECISION),
                    CAST(?9 AS INTEGER), CAST(?10 AS TEXT)
                )
                """)
                .setParameter(1, firstOrganizationId)
                .setParameter(2, secondOrganizationId)
                .setParameter(3, name)
                .setParameter(4, existingAddressId)
                .setParameter(5, zipCode)
                .setParameter(6, existingTownId)
                .setParameter(7, townX)
                .setParameter(8, townY)
                .setParameter(9, townZ)
                .setParameter(10, townName)
                .getSingleResult();
        return ((Number) result).intValue();
    }

    public int addEmployee(int organizationId) {
        Object result = em.createNativeQuery(
                        "SELECT lab1_add_employee(CAST(?1 AS INTEGER))")
                .setParameter(1, organizationId)
                .getSingleResult();
        return ((Number) result).intValue();
    }
}
