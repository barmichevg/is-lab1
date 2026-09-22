package ru.itmo.is.mapper;

import jakarta.enterprise.context.ApplicationScoped;
import ru.itmo.is.dto.AddressDto;
import ru.itmo.is.dto.CoordinatesDto;
import ru.itmo.is.dto.LocationDto;
import ru.itmo.is.dto.OrganizationResponseDto;
import ru.itmo.is.model.Address;
import ru.itmo.is.model.Coordinates;
import ru.itmo.is.model.Location;
import ru.itmo.is.model.Organization;

@ApplicationScoped
public class OrganizationMapper {
    public OrganizationResponseDto toDto(Organization entity) {
        OrganizationResponseDto dto = new OrganizationResponseDto();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setCoordinates(toDto(entity.getCoordinates()));
        dto.setCreationDate(entity.getCreationDate());
        dto.setOfficialAddress(toDto(entity.getOfficialAddress()));
        dto.setAnnualTurnover(entity.getAnnualTurnover());
        dto.setEmployeesCount(entity.getEmployeesCount());
        dto.setRating(entity.getRating());
        dto.setType(entity.getType());
        dto.setPostalAddress(entity.getPostalAddress() == null ? null : toDto(entity.getPostalAddress()));
        return dto;
    }

    public CoordinatesDto toDto(Coordinates entity) {
        CoordinatesDto dto = new CoordinatesDto();
        dto.setId(entity.getId());
        dto.setX(entity.getX());
        dto.setY(entity.getY());
        return dto;
    }

    public AddressDto toDto(Address entity) {
        AddressDto dto = new AddressDto();
        dto.setId(entity.getId());
        dto.setZipCode(entity.getZipCode());
        dto.setTown(toDto(entity.getTown()));
        return dto;
    }

    public LocationDto toDto(Location entity) {
        LocationDto dto = new LocationDto();
        dto.setId(entity.getId());
        dto.setX(entity.getX());
        dto.setY(entity.getY());
        dto.setZ(entity.getZ());
        dto.setName(entity.getName());
        return dto;
    }
}
