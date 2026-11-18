package dev.jasmeet.taskmanager.common;

import java.util.List;

/**
 * Thrown by services when a request is invalid in a way only the service can tell (for example a
 * date range that is backwards, or a sort field that is not allowed). Mapped to 400 VALIDATION_FAILED
 * with the given field errors. Spring's own checks (types, @Valid, @Min) never reach a service.
 */
public class InvalidRequestException extends RuntimeException {

    private final List<FieldViolation> fieldErrors;

    public InvalidRequestException(List<FieldViolation> fieldErrors) {
        super("Invalid request: " + fieldErrors);
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public InvalidRequestException(String field, String message) {
        this(List.of(new FieldViolation(field, message)));
    }

    public List<FieldViolation> getFieldErrors() {
        return fieldErrors;
    }
}
