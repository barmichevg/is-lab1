package ru.itmo.is.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import ru.itmo.is.dto.OrganizationPageDto;
import ru.itmo.is.dto.OrganizationOptionDto;

import java.util.List;
import ru.itmo.is.mapper.OrganizationMapper;
import ru.itmo.is.repository.OrganizationRepository;

/** A single read-only PostgreSQL snapshot for the whole REST page response. */
@ApplicationScoped
public class OrganizationPageService {
    @Inject
    OrganizationRepository repository;

    @Inject
    OrganizationMapper mapper;

    /** Full, compact dropdown snapshot; one SELECT, no count/offset or lazy entity mapping. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<OrganizationOptionDto> options() {
        repository.beginPageSnapshot();
        return List.copyOf(repository.findOptions());
    }

    // Do not join a caller's READ COMMITTED transaction or preloaded persistence
    // context. The resource calls this separate CDI bean so the interceptor runs.
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public OrganizationPageDto page(int page, int size, String filterField, String filterValue,
                                    String sortField, String direction) {
        repository.beginPageSnapshot(); // FIRST DB access, before count/items/nested loads.
        long total = repository.count(filterField, filterValue);
        var items = repository.findAll(page, size, filterField, filterValue, sortField, direction)
                .stream().map(mapper::toDto).toList();
        // Always count, even for empty/out-of-range pages; do not derive total
        // from the last returned row or defer entity mapping past the transaction.
        return new OrganizationPageDto(items, total, page, size);
    }
}
