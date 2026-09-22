package ru.itmo.is.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public class AddressDto {
    private Integer id;

    @NotNull
    private String zipCode;

    @Valid
    @NotNull
    private LocationDto town;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getZipCode() { return zipCode; }
    public void setZipCode(String zipCode) { this.zipCode = zipCode; }
    public LocationDto getTown() { return town; }
    public void setTown(LocationDto town) { this.town = town; }
}
