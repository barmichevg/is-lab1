package ru.itmo.is.dto;

import ru.itmo.is.model.OrganizationType;

import java.time.LocalDateTime;

public class OrganizationResponseDto {
    private Integer id;
    private String version;
    private String name;
    private CoordinatesDto coordinates;
    private LocalDateTime creationDate;
    private AddressDto officialAddress;
    private float annualTurnover;
    private String employeesCount;
    private Integer rating;
    private OrganizationType type;
    private AddressDto postalAddress;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getVersion() { return version; }
    public void setVersion(long version) { this.version = Long.toString(version); }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public CoordinatesDto getCoordinates() { return coordinates; }
    public void setCoordinates(CoordinatesDto coordinates) { this.coordinates = coordinates; }
    public LocalDateTime getCreationDate() { return creationDate; }
    public void setCreationDate(LocalDateTime creationDate) { this.creationDate = creationDate; }
    public AddressDto getOfficialAddress() { return officialAddress; }
    public void setOfficialAddress(AddressDto officialAddress) { this.officialAddress = officialAddress; }
    public float getAnnualTurnover() { return annualTurnover; }
    public void setAnnualTurnover(float annualTurnover) { this.annualTurnover = annualTurnover; }
    public String getEmployeesCount() { return employeesCount; }
    public void setEmployeesCount(long employeesCount) { this.employeesCount = Long.toString(employeesCount); }
    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }
    public OrganizationType getType() { return type; }
    public void setType(OrganizationType type) { this.type = type; }
    public AddressDto getPostalAddress() { return postalAddress; }
    public void setPostalAddress(AddressDto postalAddress) { this.postalAddress = postalAddress; }
}
