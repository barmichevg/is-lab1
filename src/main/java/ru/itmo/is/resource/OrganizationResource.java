package ru.itmo.is.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import ru.itmo.is.dto.OrganizationResponseDto;
import ru.itmo.is.dto.OrganizationPageDto;
import ru.itmo.is.dto.OrganizationOptionDto;
import ru.itmo.is.service.OrganizationPageService;
import ru.itmo.is.dto.OrganizationUpsertDto;
import ru.itmo.is.dto.RequestParser;
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
    @Context jakarta.servlet.http.HttpServletRequest http;

    @Inject
    OrganizationPageService pageService;

    @GET
    public OrganizationPageDto list(@QueryParam("page") @DefaultValue("0") int page,
                                    @QueryParam("size") @DefaultValue("10") int size,
                                    @QueryParam("filterField") @DefaultValue("name") String filterField,
                                    @QueryParam("filterValue") String filterValue,
                                    @QueryParam("sort") @DefaultValue("id") String sort,
                                    @QueryParam("direction") @DefaultValue("asc") String direction,
                                    @QueryParam("name") String legacyName) {
        page = Math.max(0, page);
        size = Math.min(100, Math.max(1, size));
        if ((long) page * size > Integer.MAX_VALUE) throw new BadRequestException();

        if ((filterValue == null || filterValue.isBlank()) && legacyName != null && !legacyName.isBlank()) {
            filterField = "name";
            filterValue = legacyName;
        }

        return pageService.page(page, size, filterField, filterValue, sort, direction);
    }

    @GET
    @Path("/options")
    public List<OrganizationOptionDto> options() {
        return pageService.options();
    }

    @GET
    @Path("/{id}")
    public OrganizationResponseDto get(@PathParam("id") int id) {
        return service.findById(id);
    }

    @POST
    public Response create(String body,
                           @HeaderParam("X-Idempotency-Key") String idempotencyKey) {
        OrganizationResponseDto created = creationGuard.createOnce(
                (String) http.getSession(false).getAttribute("user"), "create", idempotencyKey, body,
                () -> service.create(RequestParser.organization(body))
        );
        URI uri = uriInfo.getAbsolutePathBuilder().path(String.valueOf(created.getId())).build();
        return Response.created(uri).entity(created).build();
    }

    @PUT
    @Path("/{id}")
    public OrganizationResponseDto update(@PathParam("id") int id, String body) {
        return service.update(id, RequestParser.organization(body));
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") int id) {
        service.delete(id);
        return Response.noContent().build();
    }
}
