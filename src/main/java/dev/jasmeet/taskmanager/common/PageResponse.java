package dev.jasmeet.taskmanager.common;

import org.springframework.data.domain.Page;

import java.util.List;

/** Our own page wrapper. Spring's Page is never serialized because its JSON shape is not stable (ADR-006). */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
