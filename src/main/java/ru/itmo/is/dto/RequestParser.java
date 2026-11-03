package ru.itmo.is.dto;

import jakarta.json.*;
import ru.itmo.is.exception.ApiValidationException;
import ru.itmo.is.model.OrganizationType;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.List;

public final class RequestParser {
    private RequestParser() {}
    public static JsonObject object(String text) {
        if (text == null || text.length() > 65536) throw error("body", "JSON object required (max 64 KiB)");
        try (var parser = Json.createParser(new StringReader(text))) {
            if (!parser.hasNext() || parser.next() != jakarta.json.stream.JsonParser.Event.START_OBJECT)
                throw error("body", "JSON object required");
            JsonObject result = parser.getObject();
            if (parser.hasNext()) throw error("body", "only one JSON object is allowed");
            return result;
        } catch (RuntimeException e) { throw error("body", "invalid JSON object"); }
    }
    public static OrganizationUpsertDto organization(String text) {
        JsonObject j = object(text);
        OrganizationUpsertDto d = new OrganizationUpsertDto();
        d.setVersion(version(j));
        d.setName(string(j, "name", "name"));
        d.setAnnualTurnover(decimal(j, "annualTurnover", "annualTurnover", false).floatValue());
        if (!Float.isFinite(d.getAnnualTurnover()) || d.getAnnualTurnover() <= 0)
            throw error("annualTurnover", "must be a finite positive float");
        try { d.setEmployeesCount(decimal(j,"employeesCount","employeesCount",true).longValueExact()); }
        catch (ArithmeticException e) { throw error("employeesCount", "integer from 1 to 9223372036854775807 required"); }
        d.setRating(integer(j, "rating", "rating"));
        String type = string(j,"type","type");
        try { d.setType(OrganizationType.valueOf(type == null ? "" : type)); }
        catch (IllegalArgumentException e) { throw error("type", "unknown OrganizationType"); }
        JsonObject c = child(j,"coordinates","coordinates");
        if (c != null) {
            CoordinatesInputDto v = new CoordinatesInputDto();
            v.setId(integer(c,"id","coordinates.id"));
            if (v.getId() == null) { v.setX(integer(c,"x","coordinates.x")); v.setY(integer(c,"y","coordinates.y")); }
            d.setCoordinates(v);
        }
        d.setOfficialAddress(address(child(j,"officialAddress","officialAddress"),"officialAddress"));
        d.setPostalAddress(address(child(j,"postalAddress","postalAddress"),"postalAddress"));
        return d;
    }
    public static MergeOrganizationsRequestDto merge(String text) {
        JsonObject j=object(text);
        MergeOrganizationsRequestDto d=new MergeOrganizationsRequestDto();
        d.setFirstOrganizationId(integer(j,"firstOrganizationId","firstOrganizationId"));
        d.setSecondOrganizationId(integer(j,"secondOrganizationId","secondOrganizationId"));
        d.setName(string(j,"name","name"));
        d.setOfficialAddress(address(child(j,"officialAddress","officialAddress"),"officialAddress"));
        return d;
    }
    private static Long version(JsonObject j) {
        if (!j.containsKey("version") || j.isNull("version")) return null;
        try {
            long value = decimal(j, "version", "version", true).longValueExact();
            if (value < 0) throw error("version", "non-negative long required");
            return value;
        } catch (ArithmeticException e) {
            throw error("version", "integer from 0 to 9223372036854775807 required");
        }
    }
    private static AddressInputDto address(JsonObject j,String path) {
        if(j==null) return null;
        AddressInputDto d=new AddressInputDto();
        d.setId(integer(j,"id",path+".id"));
        if(d.getId()!=null) return d;
        d.setZipCode(string(j,"zipCode",path+".zipCode"));
        JsonObject t=child(j,"town",path+".town");
        if(t!=null) {
            LocationInputDto l=new LocationInputDto();
            l.setId(integer(t,"id",path+".town.id"));
            if(l.getId()==null) {
                l.setX(finiteDouble(t,"x",path+".town.x"));
                l.setY(finiteDouble(t,"y",path+".town.y"));
                l.setZ(integer(t,"z",path+".town.z"));
                l.setName(string(t,"name",path+".town.name"));
            }
            d.setTown(l);
        }
        return d;
    }
    private static JsonObject child(JsonObject j,String key,String path) {
        if(!j.containsKey(key) || j.isNull(key)) return null;
        if(j.get(key) instanceof JsonObject o) return o;
        throw error(path,"object required");
    }
    public static String string(JsonObject j,String key,String path) {
        if(!j.containsKey(key) || j.isNull(key)) return null;
        if(j.get(key) instanceof JsonString s && s.getString().length()<=255) return s.getString();
        throw error(path,"string up to 255 characters required");
    }
    private static BigDecimal decimal(JsonObject j,String key,String path,boolean allowString) {
        JsonValue value=j.get(key);
        if(value instanceof JsonNumber n) return n.bigDecimalValue();
        if(allowString && value instanceof JsonString s && s.getString().matches("-?[0-9]{1,19}"))
            return new BigDecimal(s.getString());
        throw error(path,"number required");
    }
    private static Integer integer(JsonObject j,String key,String path) {
        if(!j.containsKey(key) || j.isNull(key)) return null;
        try { return decimal(j,key,path,false).intValueExact(); }
        catch(ArithmeticException e) { throw error(path,"integer from -2147483648 to 2147483647 required"); }
    }
    private static Double finiteDouble(JsonObject j,String key,String path) {
        if(!j.containsKey(key) || j.isNull(key)) return null;
        BigDecimal exact=decimal(j,key,path,false);
        double value=exact.doubleValue();
        if(!Double.isFinite(value) || (value==0 && exact.signum()!=0)) throw error(path,"finite double required without underflow");
        return value;
    }
    private static ApiValidationException error(String field,String message) {
        return new ApiValidationException(List.of(field+": "+message));
    }
}
