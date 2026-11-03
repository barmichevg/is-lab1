package ru.itmo.is.dto;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import ru.itmo.is.model.OrganizationType;

import java.io.StringReader;
import java.time.LocalDateTime;

/** Versioned, explicit durable response format. Long counters never pass through double. */
public final class OrganizationResponseCodec {
    private OrganizationResponseCodec() {}

    public static String encode(OrganizationResponseDto value) {
        JsonObjectBuilder organization = Json.createObjectBuilder()
                .add("id", value.getId()).add("version", value.getVersion())
                .add("name", value.getName()).add("creationDate", value.getCreationDate().toString())
                .add("annualTurnover", (double) value.getAnnualTurnover())
                .add("employeesCount", value.getEmployeesCount()).add("rating", value.getRating())
                .add("type", value.getType().name())
                .add("coordinates", coordinates(value.getCoordinates()))
                .add("officialAddress", address(value.getOfficialAddress()));
        if (value.getPostalAddress() == null) organization.addNull("postalAddress");
        else organization.add("postalAddress", address(value.getPostalAddress()));
        return Json.createObjectBuilder().add("formatVersion", 1).add("organization", organization).build().toString();
    }

    public static OrganizationResponseDto decode(String encoded) {
        try (var reader = Json.createReader(new StringReader(encoded))) {
            JsonObject envelope = reader.readObject();
            if (envelope.getInt("formatVersion") != 1) throw new IllegalStateException("Unknown stored response format");
            JsonObject json = envelope.getJsonObject("organization");
            var value = new OrganizationResponseDto();
            value.setId(json.getInt("id"));
            value.setVersion(Long.parseLong(json.getString("version")));
            value.setName(json.getString("name"));
            value.setCreationDate(LocalDateTime.parse(json.getString("creationDate")));
            value.setAnnualTurnover((float) json.getJsonNumber("annualTurnover").doubleValue());
            value.setEmployeesCount(Long.parseLong(json.getString("employeesCount")));
            value.setRating(json.getInt("rating"));
            value.setType(OrganizationType.valueOf(json.getString("type")));
            JsonObject coordinates = json.getJsonObject("coordinates");
            var c = new CoordinatesDto();
            c.setId(coordinates.getInt("id")); c.setX(coordinates.getInt("x")); c.setY(coordinates.getInt("y"));
            value.setCoordinates(c);
            value.setOfficialAddress(readAddress(json.getJsonObject("officialAddress")));
            if (!json.isNull("postalAddress")) value.setPostalAddress(readAddress(json.getJsonObject("postalAddress")));
            return value;
        }
    }

    private static JsonObjectBuilder coordinates(CoordinatesDto value) {
        return Json.createObjectBuilder().add("id", value.getId()).add("x", value.getX()).add("y", value.getY());
    }

    private static JsonObjectBuilder address(AddressDto value) {
        LocationDto town = value.getTown();
        return Json.createObjectBuilder().add("id", value.getId()).add("zipCode", value.getZipCode())
                .add("town", Json.createObjectBuilder().add("id", town.getId())
                        .add("x", town.getX()).add("y", town.getY()).add("z", town.getZ()).add("name", town.getName()));
    }

    private static AddressDto readAddress(JsonObject json) {
        var value = new AddressDto(); value.setId(json.getInt("id")); value.setZipCode(json.getString("zipCode"));
        JsonObject town = json.getJsonObject("town");
        var location = new LocationDto();
        location.setId(town.getInt("id")); location.setName(town.getString("name"));
        location.setX(town.getJsonNumber("x").doubleValue()); location.setY(town.getJsonNumber("y").doubleValue());
        location.setZ(town.getInt("z")); value.setTown(location);
        return value;
    }
}
