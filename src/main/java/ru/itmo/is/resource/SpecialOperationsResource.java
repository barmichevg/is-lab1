package ru.itmo.is.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import ru.itmo.is.dto.MergeOrganizationsRequestDto;
import ru.itmo.is.dto.OrganizationResponseDto;
import ru.itmo.is.service.SpecialOperationsService;

import java.util.List;

@Path("/special-operations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SpecialOperationsResource {
    @Inject
    SpecialOperationsService service;
    @Inject ru.itmo.is.service.OrganizationCreationGuard guard;
    @jakarta.ws.rs.core.Context jakarta.servlet.http.HttpServletRequest http;

    @GET
    @Path("/min-employees")
    public OrganizationResponseDto minEmployees() {
        return service.findWithMinEmployees();
    }

    @GET
    @Path("/name-starts-with")
    public List<OrganizationResponseDto> nameStartsWith(@QueryParam("prefix") @DefaultValue("") String prefix) {
        return service.findNameStartsWith(prefix);
    }

    @GET
    @Path("/type-greater-than")
    public List<OrganizationResponseDto> typeGreaterThan(@QueryParam("type") String type) {
        return service.findTypeGreaterThan(type);
    }

    @POST
    @Path("/merge")
    public OrganizationResponseDto merge(String body, @HeaderParam("X-Idempotency-Key") String key) {
        return guard.createOnce((String) http.getSession(false).getAttribute("user"), "merge", key, body,
                () -> service.merge(ru.itmo.is.dto.RequestParser.merge(body)));
    }

    @POST
    @Path("/{id}/add-employee")
    public OrganizationResponseDto addEmployee(@PathParam("id") int id, @HeaderParam("X-Idempotency-Key") String key) {
        return guard.createOnce((String) http.getSession(false).getAttribute("user"), "add-employee", key,
                String.valueOf(id), () -> service.addEmployee(id));
    }
}
