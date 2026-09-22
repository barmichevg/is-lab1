package ru.itmo.is.dto;

/**
 * Reference-or-create DTO.
 * If id is specified, the existing Address entity is used and the other fields are ignored.
 * Otherwise a new Address is created; its town can itself be existing or new.
 */
public class AddressInputDto {
    private Integer id;
    private String zipCode;
    private LocationInputDto town;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getZipCode() { return zipCode; }
    public void setZipCode(String zipCode) { this.zipCode = zipCode; }
    public LocationInputDto getTown() { return town; }
    public void setTown(LocationInputDto town) { this.town = town; }
}
