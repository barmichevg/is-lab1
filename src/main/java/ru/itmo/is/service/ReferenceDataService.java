package ru.itmo.is.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import ru.itmo.is.dto.AddressDto;
import ru.itmo.is.dto.CoordinatesDto;
import ru.itmo.is.dto.LocationDto;
import ru.itmo.is.exception.ApiValidationException;
import ru.itmo.is.exception.NotFoundException;
import ru.itmo.is.mapper.OrganizationMapper;
import ru.itmo.is.model.Address;
import ru.itmo.is.model.Coordinates;
import ru.itmo.is.model.Location;
import ru.itmo.is.repository.ReferenceDataRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ReferenceDataService {
    @Inject
    ReferenceDataRepository repository;

    @Inject
    OrganizationMapper mapper;

    @Inject
    OrganizationChangeNotifier notifier;

    public List<CoordinatesDto> coordinates() {
        return repository.findAllCoordinates().stream().map(mapper::toDto).toList();
    }

    public List<AddressDto> addresses() {
        return repository.findAllAddresses().stream().map(mapper::toDto).toList();
    }

    public List<LocationDto> locations() {
        return repository.findAllLocations().stream().map(mapper::toDto).toList();
    }

    public Map<String, Object> coordinatesUsage(int id) {
        Coordinates coordinates = requireCoordinates(id);
        return usage("organizations", repository.countOrganizationsUsingCoordinates(coordinates));
    }

    public Map<String, Object> addressUsage(int id) {
        Address address = requireAddress(id);
        long official = repository.countOfficialAddressReferences(address);
        long postal = repository.countPostalAddressReferences(address);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("officialAddressReferences", official);
        result.put("postalAddressReferences", postal);
        result.put("totalReferences", official + postal);
        return result;
    }

    public Map<String, Object> locationUsage(int id) {
        Location location = requireLocation(id);
        return usage("addresses", repository.countAddressesUsingLocation(location));
    }

    @Transactional
    public void deleteCoordinates(int id, Integer replacementId) {
        Coordinates source = requireCoordinates(id);
        long references = repository.countOrganizationsUsingCoordinates(source);

        if (references > 0) {
            Coordinates replacement = requireCoordinatesReplacement(id, replacementId);
            repository.replaceCoordinates(source, replacement);
        }

        repository.flush();
        repository.clear();
        repository.remove(requireCoordinates(id));
        repository.flush();
        repository.evictAll();
        notifier.afterCommit("REFRESH", null);
    }

    @Transactional
    public void deleteAddress(int id, Integer replacementId) {
        Address source = requireAddress(id);
        long officialReferences = repository.countOfficialAddressReferences(source);
        long postalReferences = repository.countPostalAddressReferences(source);

        if (officialReferences + postalReferences > 0) {
            Address replacement = requireAddressReplacement(id, replacementId);
            repository.replaceAddress(source, replacement);
        }

        repository.flush();
        repository.clear();
        repository.remove(requireAddress(id));
        repository.flush();
        repository.evictAll();
        notifier.afterCommit("REFRESH", null);
    }

    @Transactional
    public void deleteLocation(int id, Integer replacementId) {
        Location source = requireLocation(id);
        long references = repository.countAddressesUsingLocation(source);

        if (references > 0) {
            Location replacement = requireLocationReplacement(id, replacementId);
            repository.replaceLocation(source, replacement);
        }

        repository.flush();
        repository.clear();
        repository.remove(requireLocation(id));
        repository.flush();
        repository.evictAll();
        notifier.afterCommit("REFRESH", null);
    }

    private Map<String, Object> usage(String key, long references) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, references);
        result.put("totalReferences", references);
        return result;
    }

    private Coordinates requireCoordinates(int id) {
        Coordinates entity = repository.findCoordinates(id);
        if (entity == null) throw new NotFoundException("Coordinates with id=" + id + " not found");
        return entity;
    }

    private Address requireAddress(int id) {
        Address entity = repository.findAddress(id);
        if (entity == null) throw new NotFoundException("Address with id=" + id + " not found");
        return entity;
    }

    private Location requireLocation(int id) {
        Location entity = repository.findLocation(id);
        if (entity == null) throw new NotFoundException("Location with id=" + id + " not found");
        return entity;
    }

    private Coordinates requireCoordinatesReplacement(int sourceId, Integer replacementId) {
        validateReplacement(sourceId, replacementId);
        return requireCoordinates(replacementId);
    }

    private Address requireAddressReplacement(int sourceId, Integer replacementId) {
        validateReplacement(sourceId, replacementId);
        return requireAddress(replacementId);
    }

    private Location requireLocationReplacement(int sourceId, Integer replacementId) {
        validateReplacement(sourceId, replacementId);
        return requireLocation(replacementId);
    }

    private void validateReplacement(int sourceId, Integer replacementId) {
        if (replacementId == null) {
            throw new ApiValidationException(List.of("replacementId: required for a referenced object"));
        }
        if (replacementId <= 0) {
            throw new ApiValidationException(List.of("replacementId: must be greater than 0"));
        }
        if (replacementId == sourceId) {
            throw new ApiValidationException(List.of("replacementId: must be different from deleted object"));
        }
    }
}
