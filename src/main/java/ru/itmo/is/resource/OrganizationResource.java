package ru.itmo.is.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import ru.itmo.is.dto.OrganizationResponseDto;
import ru.itmo.is.dto.OrganizationUpsertDto;
import ru.itmo.is.service.OrganizationCreationGuard;
import ru.itmo.is.service.OrganizationService;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/organizations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrganizationResource {
    @Inject
    OrganizationService service;

    @Inject
    OrganizationCreationGuard creationGuard;

    @Context
    UriInfo uriInfo;

    @GET
    public Map<String, Object> list(@QueryParam("page") @DefaultValue("0") int page,
                                    @QueryParam("size") @DefaultValue("10") int size,
                                    @QueryParam("filterField") @DefaultValue("name") String filterField,
                                    @QueryParam("filterValue") String filterValue,
                                    @QueryParam("sort") @DefaultValue("id") String sort,
                                    @QueryParam("direction") @DefaultValue("asc") String direction,
                                    @QueryParam("name") String legacyName) {
        page = Math.max(0, page);
        size = Math.min(100, Math.max(1, size));

        if ((filterValue == null || filterValue.isBlank()) && legacyName != null && !legacyName.isBlank()) {
            filterField = "name";
            filterValue = legacyName;
        }

        List<OrganizationResponseDto> items = service.findAll(page, size, filterField, filterValue, sort, direction);
        long total = service.count(filterField, filterValue);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("page", page);
        result.put("size", size);
        result.put("total", total);
        return result;
    }

    @GET
    @Path("/{id}")
    public OrganizationResponseDto get(@PathParam("id") int id) {
        return service.findById(id);
    }

    @POST
    public Response create(OrganizationUpsertDto input,
                           @HeaderParam("X-Idempotency-Key") String idempotencyKey) {
        OrganizationResponseDto created = creationGuard.createOnce(
                idempotencyKey,
                () -> service.create(input)
        );
        URI uri = uriInfo.getAbsolutePathBuilder().path(String.valueOf(created.getId())).build();
        return Response.created(uri).entity(created).build();
    }

    @PUT
    @Path("/{id}")
    public OrganizationResponseDto update(@PathParam("id") int id, OrganizationUpsertDto input) {
        return service.update(id, input);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") int id) {
        service.delete(id);
        return Response.noContent().build();
    }
}
