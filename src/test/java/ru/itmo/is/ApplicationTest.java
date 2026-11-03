package ru.itmo.is;

import org.junit.jupiter.api.Test;
import ru.itmo.is.dto.AddressInputDto;
import ru.itmo.is.dto.OrganizationResponseCodec;
import ru.itmo.is.dto.OrganizationUpsertDto;
import ru.itmo.is.dto.RequestParser;
import ru.itmo.is.exception.ApiValidationException;
import ru.itmo.is.mapper.OrganizationMapper;
import ru.itmo.is.model.Address;
import ru.itmo.is.model.Coordinates;
import ru.itmo.is.model.Location;
import ru.itmo.is.model.Organization;
import ru.itmo.is.model.OrganizationType;
import ru.itmo.is.service.OrganizationRequestValidator;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationTest {
    private static final String PAYLOAD = """
            {
              "version": "9223372036854775807",
              "name": "Организация // <&> /* данные */",
              "coordinates": {"x": -2147483648, "y": 2147483647},
              "officialAddress": {
                "zipCode": "197101",
                "town": {"x": 59.93863, "y": 30.31413, "z": 3, "name": "Санкт-Петербург"}
              },
              "annualTurnover": 1250000.5,
              "employeesCount": "9223372036854775807",
              "rating": 9,
              "type": "COMMERCIAL",
              "postalAddress": {
                "zipCode": "",
                "town": {"x": -12.5, "y": 7.25, "z": -4, "name": "Почтовый город"}
              }
            }
            """;

    @Test
    void applicationContract() {
        var input = RequestParser.organization(PAYLOAD);
        var validator = new OrganizationRequestValidator();
        assertDoesNotThrow(() -> validator.validate(input));
        assertEquals(Long.MAX_VALUE, input.getVersion());
        assertEquals(Long.MAX_VALUE, input.getEmployeesCount());
        assertEquals(OrganizationType.COMMERCIAL, input.getType());
        assertEquals(Integer.MIN_VALUE, input.getCoordinates().getX());
        assertEquals(Integer.MAX_VALUE, input.getCoordinates().getY());

        var organization = organization(input);
        organization.setCreationDate(LocalDateTime.parse("2026-11-03T12:34:56.123456789"));
        organization.prePersist();
        var mapper = new OrganizationMapper();
        var response = mapper.toDto(organization);
        assertEquals("0", response.getVersion());
        assertEquals(input.getName(), response.getName());
        assertEquals("9223372036854775807", response.getEmployeesCount());
        assertEquals(LocalDateTime.parse("2026-11-03T12:34:56.123456"), response.getCreationDate());
        assertEquals(input.getOfficialAddress().getTown().getName(), response.getOfficialAddress().getTown().getName());
        assertEquals(input.getPostalAddress().getTown().getX(), response.getPostalAddress().getTown().getX());
        response.setVersion(input.getVersion());

        var encoded = OrganizationResponseCodec.encode(response);
        var json = RequestParser.object(encoded);
        var stored = json.getJsonObject("organization");
        assertEquals(1, json.getInt("formatVersion"));
        assertEquals("9223372036854775807", stored.getString("version"));
        assertEquals("9223372036854775807", stored.getString("employeesCount"));
        assertEquals("2026-11-03T12:34:56.123456", stored.getString("creationDate"));
        assertEquals(input.getName(), stored.getString("name"));
        var decoded = OrganizationResponseCodec.decode(encoded);
        assertEquals(json, RequestParser.object(OrganizationResponseCodec.encode(decoded)));

        organization.setPostalAddress(null);
        var withoutPostal = OrganizationResponseCodec.decode(OrganizationResponseCodec.encode(mapper.toDto(organization)));
        assertNull(withoutPostal.getPostalAddress());
        assertEquals(response.getCreationDate(), withoutPostal.getCreationDate());

        rejected(PAYLOAD.replace("\"employeesCount\": \"9223372036854775807\"", "\"employeesCount\": \"9223372036854775808\""), "employeesCount");
        rejected(PAYLOAD.replace("\"employeesCount\": \"9223372036854775807\"", "\"employeesCount\": 1.5"), "employeesCount");
        rejected(PAYLOAD.replace("\"annualTurnover\": 1250000.5", "\"annualTurnover\": 1e500"), "annualTurnover");
        rejected(PAYLOAD.replace("\"x\": -2147483648", "\"x\": 1.5"), "coordinates.x");
        rejected(PAYLOAD.replace("Организация // <&> /* данные */", " "), "name");
        rejected(PAYLOAD.replace("\"name\": \"Санкт-Петербург\"", "\"name\": \" \""), "officialAddress.town.name");
        rejected(PAYLOAD.replace("\"rating\": 9", "\"rating\": 0"), "rating");
        rejected(PAYLOAD.replace("\"type\": \"COMMERCIAL\"", "\"type\": \"UNKNOWN\""), "type");
        rejected("[]", "body");
        rejected(PAYLOAD + " {}", "body");
    }

    private static Organization organization(OrganizationUpsertDto input) {
        var result = new Organization();
        result.setId(42);
        result.setName(input.getName());
        result.setAnnualTurnover(input.getAnnualTurnover());
        result.setEmployeesCount(input.getEmployeesCount());
        result.setRating(input.getRating());
        result.setType(input.getType());
        var coordinates = new Coordinates();
        coordinates.setId(43);
        coordinates.setX(input.getCoordinates().getX());
        coordinates.setY(input.getCoordinates().getY());
        result.setCoordinates(coordinates);
        result.setOfficialAddress(address(input.getOfficialAddress(), 44));
        result.setPostalAddress(address(input.getPostalAddress(), 46));
        return result;
    }

    private static Address address(AddressInputDto input, int id) {
        var location = new Location();
        location.setId(id + 1);
        location.setX(input.getTown().getX());
        location.setY(input.getTown().getY());
        location.setZ(input.getTown().getZ());
        location.setName(input.getTown().getName());
        var result = new Address();
        result.setId(id);
        result.setZipCode(input.getZipCode());
        result.setTown(location);
        return result;
    }

    private static void rejected(String payload, String field) {
        var error = assertThrows(ApiValidationException.class,
                () -> new OrganizationRequestValidator().validate(RequestParser.organization(payload)));
        assertTrue(error.getErrors().stream().anyMatch(message -> message.startsWith(field + ":")), error.getMessage());
    }
}
