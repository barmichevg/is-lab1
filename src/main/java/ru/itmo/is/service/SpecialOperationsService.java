package ru.itmo.is.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import ru.itmo.is.dto.AddressInputDto;
import ru.itmo.is.dto.LocationInputDto;
import ru.itmo.is.dto.MergeOrganizationsRequestDto;
import ru.itmo.is.dto.OrganizationResponseDto;
import ru.itmo.is.exception.ApiValidationException;
import ru.itmo.is.exception.NotFoundException;
import ru.itmo.is.mapper.OrganizationMapper;
import ru.itmo.is.model.Organization;
import ru.itmo.is.model.OrganizationType;
import ru.itmo.is.repository.OrganizationRepository;
import ru.itmo.is.repository.SpecialOperationsRepository;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class SpecialOperationsService {
    @Inject
    SpecialOperationsRepository repository;

    @Inject
    OrganizationRepository organizationRepository;

    @Inject
    OrganizationMapper mapper;

    @Inject
    OrganizationChangeNotifier notifier;

    public OrganizationResponseDto findWithMinEmployees() {
        List<Organization> organizations = repository.findWithMinEmployees();
        if (organizations.isEmpty()) {
            throw new NotFoundException("There are no organizations");
        }
        return mapper.toDto(organizations.get(0));
    }

    public List<OrganizationResponseDto> findNameStartsWith(String prefix) {
        return repository.findNameStartsWith(prefix == null ? "" : prefix)
                .stream()
                .map(mapper::toDto)
                .toList();
    }

    public List<OrganizationResponseDto> findTypeGreaterThan(String typeValue) {
        OrganizationType type;
        try {
            type = OrganizationType.valueOf(typeValue == null ? "" : typeValue);
        } catch (IllegalArgumentException ex) {
            throw new ApiValidationException(List.of("type: unknown OrganizationType"));
        }

        return repository.findTypeGreaterThan(type.name())
                .stream()
                .map(mapper::toDto)
                .toList();
    }

    @Transactional
    public OrganizationResponseDto merge(MergeOrganizationsRequestDto input) {
        validateMerge(input);

        AddressInputDto address = input.getOfficialAddress();
        LocationInputDto town = address.getId() == null ? address.getTown() : null;

        int newId = repository.mergeOrganizations(
                input.getFirstOrganizationId(),
                input.getSecondOrganizationId(),
                input.getName().trim(),
                address.getId(),
                address.getId() == null ? address.getZipCode() : null,
                town == null ? null : town.getId(),
                town == null || town.getId() != null ? null : town.getX(),
                town == null || town.getId() != null ? null : town.getY(),
                town == null || town.getId() != null ? null : town.getZ(),
                town == null || town.getId() != null ? null : town.getName().trim()
        );

        organizationRepository.flush();
        organizationRepository.clear();
        Organization created = requireOrganization(newId);
        OrganizationResponseDto resultDto = mapper.toDto(created);
        notifier.afterCommit("CREATED", newId);
        return resultDto;
    }

    @Transactional
    public OrganizationResponseDto addEmployee(int organizationId) {
        if (organizationId <= 0) {
            throw new ApiValidationException(List.of("organizationId: must be greater than 0"));
        }
        requireOrganization(organizationId);

        int id = repository.addEmployee(organizationId);
        organizationRepository.flush();
        organizationRepository.clear();
        organizationRepository.evict(id);
        OrganizationResponseDto resultDto = mapper.toDto(requireOrganization(id));
        notifier.afterCommit("UPDATED", id);
        return resultDto;
    }

    private Organization requireOrganization(int id) {
        Organization organization = organizationRepository.findById(id);
        if (organization == null) {
            throw new NotFoundException("Organization with id=" + id + " not found");
        }
        return organization;
    }

    private void validateMerge(MergeOrganizationsRequestDto input) {
        List<String> errors = new ArrayList<>();
        if (input == null) {
            throw new ApiValidationException(List.of("request body is required"));
        }

        Integer firstId = input.getFirstOrganizationId();
        Integer secondId = input.getSecondOrganizationId();
        if (firstId == null || firstId <= 0) errors.add("firstOrganizationId: must be greater than 0");
        if (secondId == null || secondId <= 0) errors.add("secondOrganizationId: must be greater than 0");
        if (firstId != null && firstId.equals(secondId)) errors.add("source organizations must be different");
        if (input.getName() == null || input.getName().isBlank()) errors.add("name: must not be blank");

        AddressInputDto address = input.getOfficialAddress();
        if (address == null) {
            errors.add("officialAddress: must not be null");
        } else if (address.getId() != null) {
            if (address.getId() <= 0) errors.add("officialAddress.id: must be greater than 0");
        } else {
            if (address.getZipCode() == null) errors.add("officialAddress.zipCode: must not be null");
            LocationInputDto town = address.getTown();
            if (town == null) {
                errors.add("officialAddress.town: must not be null");
            } else if (town.getId() != null) {
                if (town.getId() <= 0) errors.add("officialAddress.town.id: must be greater than 0");
            } else {
                if (town.getX() == null) errors.add("officialAddress.town.x: must not be null");
                if (town.getY() == null) errors.add("officialAddress.town.y: must not be null");
                if (town.getZ() == null) errors.add("officialAddress.town.z: must not be null");
                if (town.getName() == null || town.getName().isBlank()) errors.add("officialAddress.town.name: must not be blank");
            }
        }

        if (!errors.isEmpty()) throw new ApiValidationException(errors);

        requireOrganization(firstId);
        requireOrganization(secondId);
    }
}
