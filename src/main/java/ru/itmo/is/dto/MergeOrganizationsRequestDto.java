package ru.itmo.is.dto;

/** Request for the database-level organization merge operation. */
public class MergeOrganizationsRequestDto {
    private Integer firstOrganizationId;
    private Integer secondOrganizationId;
    private String name;
    private AddressInputDto officialAddress;

    public Integer getFirstOrganizationId() { return firstOrganizationId; }
    public void setFirstOrganizationId(Integer firstOrganizationId) { this.firstOrganizationId = firstOrganizationId; }
    public Integer getSecondOrganizationId() { return secondOrganizationId; }
    public void setSecondOrganizationId(Integer secondOrganizationId) { this.secondOrganizationId = secondOrganizationId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public AddressInputDto getOfficialAddress() { return officialAddress; }
    public void setOfficialAddress(AddressInputDto officialAddress) { this.officialAddress = officialAddress; }
}
