package ru.itmo.is.dto;

/** Scalar-only selector row: no managed entity or nested reference can trigger extra reads. */
public final class OrganizationOptionDto {
    private final Integer id;
    private final String name;
    private final String employeesCount;

    // JPA constructor projection receives the entity's exact Java long before JSON serialization.
    public OrganizationOptionDto(Integer id, String name, long employeesCount) {
        this.id = id;
        this.name = name;
        this.employeesCount = Long.toString(employeesCount);
    }

    public Integer getId() { return id; }
    public String getName() { return name; }
    public String getEmployeesCount() { return employeesCount; }
}
