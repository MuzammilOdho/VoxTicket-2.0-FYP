package com.voxticket.admin.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/** Uniform paged envelope for every admin list endpoint. */
public record PageDto<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageDto<T> of(Page<T> page) {
        return new PageDto<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    public static <T> PageDto<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageDto<>(List.copyOf(content), page, size, totalElements, totalPages);
    }
}
