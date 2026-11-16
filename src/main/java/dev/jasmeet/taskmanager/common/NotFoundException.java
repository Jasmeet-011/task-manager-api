package dev.jasmeet.taskmanager.common;

/** Thrown by services when a resource does not exist. Mapped to 404 NOT_FOUND. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String resource, Object id) {
        super(resource + " " + id + " not found");
    }
}
