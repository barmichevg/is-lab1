package ru.itmo.is.dto;

import ru.itmo.is.model.OrganizationType;

public class OrganizationUpsertDto {
    private String name;
    private CoordinatesInputDto coordinates;
    private AddressInputDto officialAddress;
    private float annualTurnover;
    private long employeesCount;
    private Integer rating;
    private OrganizationType type;
    private AddressInputDto postalAddress;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public CoordinatesInputDto getCoordinates() { return coordinates; }
    public void setCoordinates(CoordinatesInputDto coordinates) { this.coordinates = coordinates; }
    public AddressInputDto getOfficialAddress() { return officialAddress; }
    public void setOfficialAddress(AddressInputDto officialAddress) { this.officialAddress = officialAddress; }
    public float getAnnualTurnover() { return annualTurnover; }
    public void setAnnualTurnover(float annualTurnover) { this.annualTurnover = annualTurnover; }
    public long getEmployeesCount() { return employeesCount; }
    public void setEmployeesCount(long employeesCount) { this.employeesCount = employeesCount; }
    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }
    public OrganizationType getType() { return type; }
    public void setType(OrganizationType type) { this.type = type; }
    public AddressInputDto getPostalAddress() { return postalAddress; }
    public void setPostalAddress(AddressInputDto postalAddress) { this.postalAddress = postalAddress; }
}
