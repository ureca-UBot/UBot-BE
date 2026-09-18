package com.ubot.common;

import java.util.List;

public record PageResponseDto<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public static <T> PageResponseDto<T> of(
            List<T> content,
            int page,
            int size,
            long totalElements
    ) {
        int totalPages = (int) Math.ceil((double) totalElements / size);
        return new PageResponseDto<>(
                content,
                page,
                size,
                totalElements,
                totalPages,
                page == 0,
                page + 1 >= totalPages
        );
    }
}
