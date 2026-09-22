package ru.itmo.is.exception;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.LinkedHashMap;
import java.util.Map;

@Provider
public class ApiExceptionMapper implements ExceptionMapper<Throwable> {
    @Override
    public Response toResponse(Throwable exception) {
        if (exception instanceof NotFoundException) {
            return json(Response.Status.NOT_FOUND, exception.getMessage(), null);
        }

        if (exception instanceof ApiValidationException validation) {
            return json(Response.Status.BAD_REQUEST, "Validation failed", validation.getErrors());
        }

        return json(Response.Status.INTERNAL_SERVER_ERROR,
                exception.getMessage() == null ? "Internal server error" : exception.getMessage(),
                null);
    }

    private Response json(Response.Status status, String message, Object details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        if (details != null) {
            body.put("details", details);
        }
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(body)
                .build();
    }
}
