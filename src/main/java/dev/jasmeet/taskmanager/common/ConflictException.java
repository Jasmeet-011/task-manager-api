package dev.jasmeet.taskmanager.common;

import java.util.List;

/**
 * Thrown by services when a request conflicts with the current state (for example a duplicate name).
 * Mapped to 409 with the given code. It is a RuntimeException, so the transaction rolls back.
 */
public class ConflictException extends RuntimeException {

    private final String code;
    private final List<FieldViolation> fieldErrors;

    public ConflictException(String code, String message, List<FieldViolation> fieldErrors) {
        super(message);
        this.code = code;
        this.fieldErrors = fieldErrors;
    }

    public String getCode() {
        return code;
    }

    public List<FieldViolation> getFieldErrors() {
        return fieldErrors;
    }
}
