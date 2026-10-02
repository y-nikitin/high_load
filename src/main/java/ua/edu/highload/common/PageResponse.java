package ua.edu.highload.common;

import java.util.List;

public record PageResponse<T>(List<T> items, int page, int size, boolean hasNext) {
    public static int offset(int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) {
            throw ApiException.badRequest("page must be 0..10000 and size must be 1..100");
        }
        return page * size;
    }

    public static <T> PageResponse<T> from(List<T> rows, int page, int size) {
        return new PageResponse<>(List.copyOf(rows.subList(0, Math.min(rows.size(), size))),
                page, size, rows.size() > size);
    }
}
