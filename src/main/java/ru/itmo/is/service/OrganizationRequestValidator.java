package ru.itmo.is.service;

import jakarta.enterprise.context.ApplicationScoped;
import ru.itmo.is.dto.AddressInputDto;
import ru.itmo.is.dto.CoordinatesInputDto;
import ru.itmo.is.dto.LocationInputDto;
import ru.itmo.is.dto.OrganizationUpsertDto;
import ru.itmo.is.exception.ApiValidationException;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class OrganizationRequestValidator {

    public void validate(OrganizationUpsertDto input) {
        List<String> errors = new ArrayList<>();

        if (input == null) {
            throw new ApiValidationException(List.of("request body must not be null"));
        }

        if (input.getName() == null || input.getName().isBlank()) {
            errors.add("name: must not be blank");
        }
        if (input.getAnnualTurnover() <= 0) {
            errors.add("annualTurnover: must be greater than 0");
        }
        if (input.getEmployeesCount() <= 0) {
            errors.add("employeesCount: must be greater than 0");
        }
        if (input.getRating() == null) {
            errors.add("rating: must not be null");
        } else if (input.getRating() <= 0) {
            errors.add("rating: must be greater than 0");
        }
        if (input.getType() == null) {
            errors.add("type: must not be null");
        }

        validateCoordinates(input.getCoordinates(), "coordinates", errors);
        validateAddress(input.getOfficialAddress(), "officialAddress", false, errors);
        validateAddress(input.getPostalAddress(), "postalAddress", true, errors);

        if (!errors.isEmpty()) {
            throw new ApiValidationException(errors);
        }
    }

    private void validateCoordinates(CoordinatesInputDto value, String path, List<String> errors) {
        if (value == null) {
            errors.add(path + ": must not be null");
            return;
        }
        if (value.getId() != null) {
            if (value.getId() <= 0) {
                errors.add(path + ".id: must be greater than 0");
            }
            return;
        }
        if (value.getX() == null) {
            errors.add(path + ".x: must not be null when creating new coordinates");
        }
        if (value.getY() == null) {
            errors.add(path + ".y: must not be null when creating new coordinates");
        }
    }

    private void validateAddress(AddressInputDto value, String path, boolean nullable, List<String> errors) {
        if (value == null) {
            if (!nullable) {
                errors.add(path + ": must not be null");
            }
            return;
        }
        if (value.getId() != null) {
            if (value.getId() <= 0) {
                errors.add(path + ".id: must be greater than 0");
            }
            return;
        }
        if (value.getZipCode() == null) {
            errors.add(path + ".zipCode: must not be null when creating a new address");
        }
        validateLocation(value.getTown(), path + ".town", errors);
    }

    private void validateLocation(LocationInputDto value, String path, List<String> errors) {
        if (value == null) {
            errors.add(path + ": must not be null when creating a new address");
            return;
        }
        if (value.getId() != null) {
            if (value.getId() <= 0) {
                errors.add(path + ".id: must be greater than 0");
            }
            return;
        }
        if (value.getX() == null) {
            errors.add(path + ".x: must not be null when creating a new location");
        }
        if (value.getY() == null) {
            errors.add(path + ".y: must not be null when creating a new location");
        }
        if (value.getZ() == null) {
            errors.add(path + ".z: must not be null when creating a new location");
        }
        if (value.getName() == null || value.getName().isBlank()) {
            errors.add(path + ".name: must not be blank when creating a new location");
        }
    }
}
