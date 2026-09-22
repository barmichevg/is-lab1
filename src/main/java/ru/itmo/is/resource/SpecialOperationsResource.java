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
    public OrganizationResponseDto merge(MergeOrganizationsRequestDto request) {
        return service.merge(request);
    }

    @POST
    @Path("/{id}/add-employee")
    public OrganizationResponseDto addEmployee(@PathParam("id") int id) {
        return service.addEmployee(id);
    }
}
