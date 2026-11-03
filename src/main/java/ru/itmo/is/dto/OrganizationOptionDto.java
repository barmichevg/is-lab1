package ru.itmo.is.dto;

public final class OrganizationOptionDto {
    private final Integer id;
    private final String name;
    private final String employeesCount;

    public OrganizationOptionDto(Integer id, String name, long employeesCount) {
        this.id = id;
        this.name = name;
        this.employeesCount = Long.toString(employeesCount);
    }

    public Integer getId() { return id; }
    public String getName() { return name; }
    public String getEmployeesCount() { return employeesCount; }
}
