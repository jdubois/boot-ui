package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.BootUiDtos.PageMetadata;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

final class PagedList {

    static final int DEFAULT_LIMIT = 200;

    static final int MAX_LIMIT = 1000;

    private PagedList() {}

    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    static boolean contains(String value, String query) {
        return query.isEmpty()
                || (value != null && value.toLowerCase(Locale.ROOT).contains(query));
    }

    static <T> Result<T> from(List<T> items, Predicate<T> matches, Integer offset, Integer limit) {
        List<T> matched = new ArrayList<>();
        for (T item : items) {
            if (matches.test(item)) {
                matched.add(item);
            }
        }

        int safeOffset = offset == null ? 0 : Math.max(0, offset);
        int safeLimit = limit == null ? matched.size() : Math.max(1, Math.min(MAX_LIMIT, limit));
        int fromIndex = Math.min(safeOffset, matched.size());
        int toIndex = Math.min(fromIndex + safeLimit, matched.size());
        List<T> page = List.copyOf(matched.subList(fromIndex, toIndex));
        return new Result<>(
                page,
                new PageMetadata(
                        items.size(), matched.size(), fromIndex, safeLimit, page.size(), toIndex < matched.size()));
    }

    static <T> Result<T> from(List<T> items, Integer offset, Integer limit) {
        return from(items, ignored -> true, offset, limit);
    }

    record Result<T>(List<T> items, PageMetadata page) {}
}
