package ru.itmo.is.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import ru.itmo.is.dto.OrganizationPageDto;
import ru.itmo.is.dto.OrganizationOptionDto;

import java.util.List;
import ru.itmo.is.mapper.OrganizationMapper;
import ru.itmo.is.repository.OrganizationRepository;

@ApplicationScoped
public class OrganizationPageService {
    @Inject
    OrganizationRepository repository;

    @Inject
    OrganizationMapper mapper;

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<OrganizationOptionDto> options() {
        repository.beginPageSnapshot();
        return List.copyOf(repository.findOptions());
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public OrganizationPageDto page(int page, int size, String filterField, String filterValue,
                                    String sortField, String direction) {
        repository.beginPageSnapshot();
        long total = repository.count(filterField, filterValue);
        var items = repository.findAll(page, size, filterField, filterValue, sortField, direction)
                .stream().map(mapper::toDto).toList();

        return new OrganizationPageDto(items, total, page, size);
    }
}
