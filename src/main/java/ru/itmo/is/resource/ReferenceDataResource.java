package ru.itmo.is.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import ru.itmo.is.dto.AddressDto;
import ru.itmo.is.dto.CoordinatesDto;
import ru.itmo.is.dto.LocationDto;
import ru.itmo.is.service.ReferenceDataService;

import java.util.List;
import java.util.Map;

@Path("/reference")
@Produces(MediaType.APPLICATION_JSON)
public class ReferenceDataResource {
    @Inject
    ReferenceDataService service;

    @GET
    @Path("/coordinates")
    public List<CoordinatesDto> coordinates() {
        return service.coordinates();
    }

    @GET
    @Path("/coordinates/{id}/usage")
    public Map<String, Object> coordinatesUsage(@PathParam("id") int id) {
        return service.coordinatesUsage(id);
    }

    @DELETE
    @Path("/coordinates/{id}")
    public Response deleteCoordinates(@PathParam("id") int id,
                                      @QueryParam("replacementId") Integer replacementId) {
        service.deleteCoordinates(id, replacementId);
        return Response.noContent().build();
    }

    @GET
    @Path("/addresses")
    public List<AddressDto> addresses() {
        return service.addresses();
    }

    @GET
    @Path("/addresses/{id}/usage")
    public Map<String, Object> addressUsage(@PathParam("id") int id) {
        return service.addressUsage(id);
    }

    @DELETE
    @Path("/addresses/{id}")
    public Response deleteAddress(@PathParam("id") int id,
                                  @QueryParam("replacementId") Integer replacementId) {
        service.deleteAddress(id, replacementId);
        return Response.noContent().build();
    }

    @GET
    @Path("/locations")
    public List<LocationDto> locations() {
        return service.locations();
    }

    @GET
    @Path("/locations/{id}/usage")
    public Map<String, Object> locationUsage(@PathParam("id") int id) {
        return service.locationUsage(id);
    }

    @DELETE
    @Path("/locations/{id}")
    public Response deleteLocation(@PathParam("id") int id,
                                   @QueryParam("replacementId") Integer replacementId) {
        service.deleteLocation(id, replacementId);
        return Response.noContent().build();
    }
}
