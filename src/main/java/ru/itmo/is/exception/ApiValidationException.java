package ru.itmo.is.exception;

import java.util.List;

public class ApiValidationException extends RuntimeException {
    private final List<String> errors;

    public ApiValidationException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
