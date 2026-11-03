package ru.itmo.is;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.itmo.is.dto.RequestParser;
import ru.itmo.is.exception.ApiValidationException;
import static org.junit.jupiter.api.Assertions.*;

class RequestParserTest {
    static String body(String turnover,String employees,String rating) {
        return "{\"name\":\"Test\",\"annualTurnover\":"+turnover+",\"employeesCount\":"+employees+",\"rating\":"+rating+",\"type\":\"COMMERCIAL\",\"coordinates\":{\"x\":1,\"y\":2},\"officialAddress\":{\"id\":1}}";
    }
    @Test void fullLongRangePreserved() {
        assertEquals(Long.MAX_VALUE,RequestParser.organization(body("1","9223372036854775807","1")).getEmployeesCount());
        assertEquals(Long.MAX_VALUE,RequestParser.organization(body("1","\"9223372036854775807\"","1")).getEmployeesCount());
    }
    @ParameterizedTest @ValueSource(strings={"9223372036854775808","1.5","1e100","\"1.5\"","true"})
    void invalidLong(String value) { assertThrows(ApiValidationException.class,()->RequestParser.organization(body("1",value,"1"))); }
    @ParameterizedTest @ValueSource(strings={"1e100","1e-100","0","-1","\"Infinity\"","null"})
    void invalidFloat(String value) { assertThrows(ApiValidationException.class,()->RequestParser.organization(body(value,"1","1"))); }
    @ParameterizedTest @ValueSource(strings={"1.5","2147483648","-2147483649","true"})
    void invalidInteger(String value) { assertThrows(ApiValidationException.class,()->RequestParser.organization(body("1","1",value))); }
    @Test void malformedJsonAndEnum() {
        assertThrows(ApiValidationException.class,()->RequestParser.organization("{"));
        assertThrows(ApiValidationException.class,()->RequestParser.organization(body("1","1","1").replace("COMMERCIAL","UNKNOWN")));
    }
    @Test void nestedNumbersAreStrict() {
        assertThrows(ApiValidationException.class,()->RequestParser.organization(body("1","1","1").replace("\"x\":1","\"x\":1.5")));
        assertThrows(ApiValidationException.class,()->RequestParser.merge("{\"officialAddress\":{\"town\":{\"x\":1e400}}}"));
    }
    @Test void trailingGarbageRejected() {
        assertThrows(ApiValidationException.class,()->RequestParser.object("{} {}"));
        assertThrows(ApiValidationException.class,()->RequestParser.object("{} trailing"));
    }
    @Test void exactVersionParsingAndOptionalityForCreation() {
        String original=body("1","1","1");
        assertNull(RequestParser.organization(original).getVersion());
        assertNull(RequestParser.organization(withVersion("null")).getVersion());
        for(String value:new String[]{"0","\"0\"","9223372036854775807","\"9223372036854775807\"","\"9007199254740993\""}) {
            long expected=Long.parseLong(value.replace("\"",""));
            assertEquals(expected,RequestParser.organization(withVersion(value)).getVersion());
        }
    }
    static String withVersion(String value) {return body("1","1","1").replaceFirst("\\{","{\"version\":"+value+",");}
    @ParameterizedTest @ValueSource(strings={"-1","\"-1\"","9223372036854775808","\"9223372036854775808\"","1.5","\"1.5\"","true","{}","[]","\"\""})
    void invalidVersion(String value) {
        var error=assertThrows(ApiValidationException.class,()->RequestParser.organization(withVersion(value)));
        assertTrue(error.getErrors().get(0).startsWith("version:"));
    }
    @Test void absentOptionalAddress() { assertNull(RequestParser.organization(body("1","1","1")).getPostalAddress()); }
    @Test void literalLike() { assertEquals("x!%!_!!\\",ru.itmo.is.repository.OrganizationRepository.escapeLike("x%_!\\")); }
}
