package ru.itmo.is.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import ru.itmo.is.dto.AddressInputDto;
import ru.itmo.is.dto.CoordinatesInputDto;
import ru.itmo.is.dto.LocationInputDto;
import ru.itmo.is.dto.OrganizationResponseDto;
import ru.itmo.is.dto.OrganizationUpsertDto;
import ru.itmo.is.exception.NotFoundException;
import ru.itmo.is.exception.ApiValidationException;
import jakarta.persistence.OptimisticLockException;
import ru.itmo.is.mapper.OrganizationMapper;
import ru.itmo.is.model.Address;
import ru.itmo.is.model.Coordinates;
import ru.itmo.is.model.Location;
import ru.itmo.is.model.Organization;
import ru.itmo.is.repository.OrganizationRepository;
import ru.itmo.is.repository.ReferenceDataRepository;

import java.util.List;

@ApplicationScoped
public class OrganizationService {
    @Inject
    OrganizationRepository organizationRepository;

    @Inject
    ReferenceDataRepository referenceDataRepository;

    @Inject
    OrganizationMapper mapper;

    @Inject
    OrganizationRequestValidator validator;

    @Inject
    OrganizationChangeNotifier notifier;

    @Inject
    ru.itmo.is.repository.MutationLock mutationLock;

    public List<OrganizationResponseDto> findAll(int page, int size,
                                                  String filterField, String filterValue,
                                                  String sortField, String direction) {
        return organizationRepository.findAll(page, size, filterField, filterValue, sortField, direction)
                .stream()
                .map(mapper::toDto)
                .toList();
    }

    public long count(String filterField, String filterValue) {
        return organizationRepository.count(filterField, filterValue);
    }

    public OrganizationResponseDto findById(int id) {
        return mapper.toDto(findOrganization(id));
    }

    @Transactional
    public OrganizationResponseDto create(OrganizationUpsertDto input) {
        mutationLock.shared();
        validator.validate(input);

        Organization organization = new Organization();
        applySimpleFields(organization, input);
        organization.setCoordinates(resolveCoordinates(input.getCoordinates()));
        organization.setOfficialAddress(resolveAddress(input.getOfficialAddress()));
        organization.setPostalAddress(input.getPostalAddress() == null
                ? null
                : resolveAddress(input.getPostalAddress()));

        organizationRepository.persist(organization);
        organizationRepository.flush();
        OrganizationResponseDto result = mapper.toDto(organization);
        notifier.afterCommit("CREATED", organization.getId());
        return result;
    }

    @Transactional
    public OrganizationResponseDto update(int id, OrganizationUpsertDto input) {
        mutationLock.shared();
        validator.validate(input);

        if (input.getVersion() == null || input.getVersion() < 0) {
            throw new ApiValidationException(List.of("version: required for update; use the version from the last loaded organization"));
        }
        Organization existing = findOrganization(id);
        if (existing.getVersion() != input.getVersion()) {
            throw new OptimisticLockException("Organization version is stale");
        }

        organizationRepository.checkVersion(existing);
        applySimpleFields(existing, input);
        existing.setCoordinates(resolveCoordinates(input.getCoordinates()));
        existing.setOfficialAddress(resolveAddress(input.getOfficialAddress()));
        existing.setPostalAddress(input.getPostalAddress() == null
                ? null
                : resolveAddress(input.getPostalAddress()));

        organizationRepository.flush();
        OrganizationResponseDto result = mapper.toDto(existing);
        notifier.afterCommit("UPDATED", id);
        return result;
    }

    @Transactional
    public void delete(int id) {
        mutationLock.shared();
        Organization existing = findOrganization(id);
        organizationRepository.remove(existing);
        organizationRepository.flush();
        notifier.afterCommit("DELETED", id);
    }

    private Organization findOrganization(int id) {
        Organization organization = organizationRepository.findById(id);
        if (organization == null) {
            throw new NotFoundException("Organization with id=" + id + " not found");
        }
        return organization;
    }

    private void applySimpleFields(Organization target, OrganizationUpsertDto input) {
        target.setName(input.getName().trim());
        target.setAnnualTurnover(input.getAnnualTurnover());
        target.setEmployeesCount(input.getEmployeesCount());
        target.setRating(input.getRating());
        target.setType(input.getType());
    }

    private Coordinates resolveCoordinates(CoordinatesInputDto dto) {
        if (dto.getId() != null) {
            Coordinates coordinates = referenceDataRepository.findCoordinates(dto.getId());
            if (coordinates == null) {
                throw new NotFoundException("Coordinates with id=" + dto.getId() + " not found");
            }
            return coordinates;
        }

        Coordinates coordinates = new Coordinates();
        coordinates.setX(dto.getX());
        coordinates.setY(dto.getY());
        referenceDataRepository.persist(coordinates);
        return coordinates;
    }

    private Address resolveAddress(AddressInputDto dto) {
        if (dto.getId() != null) {
            Address address = referenceDataRepository.findAddress(dto.getId());
            if (address == null) {
                throw new NotFoundException("Address with id=" + dto.getId() + " not found");
            }
            return address;
        }

        Address address = new Address();
        address.setZipCode(dto.getZipCode());
        address.setTown(resolveLocation(dto.getTown()));
        referenceDataRepository.persist(address);
        return address;
    }

    private Location resolveLocation(LocationInputDto dto) {
        if (dto.getId() != null) {
            Location location = referenceDataRepository.findLocation(dto.getId());
            if (location == null) {
                throw new NotFoundException("Location with id=" + dto.getId() + " not found");
            }
            return location;
        }

        Location location = new Location();
        location.setX(dto.getX());
        location.setY(dto.getY());
        location.setZ(dto.getZ());
        location.setName(dto.getName().trim());
        referenceDataRepository.persist(location);
        return location;
    }
}
