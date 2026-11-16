package dev.jasmeet.taskmanager.common;

/** One entry in the ADR-005 "fieldErrors" array. */
public record FieldViolation(String field, String message) {
}
