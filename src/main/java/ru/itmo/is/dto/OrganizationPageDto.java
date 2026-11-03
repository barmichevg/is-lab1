package ru.itmo.is.dto;

import java.util.List;

public class OrganizationPageDto {
    private final List<OrganizationResponseDto> items;
    private final long total;
    private final int page;
    private final int size;

    public OrganizationPageDto(List<OrganizationResponseDto> items, long total, int page, int size) {
        this.items = List.copyOf(items);
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public List<OrganizationResponseDto> getItems() { return items; }
    public long getTotal() { return total; }
    public int getPage() { return page; }
    public int getSize() { return size; }
}
