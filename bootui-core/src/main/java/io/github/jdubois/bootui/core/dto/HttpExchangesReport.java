package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record HttpExchangesReport(
        int total,
        int recorded,
        int hiddenSelf,
        List<HttpExchangeDto> exchanges,
        PageMetadata page,
        String unavailableReason) {
    public static HttpExchangesReport unavailable(String reason) {
        return new HttpExchangesReport(0, 0, 0, List.of(), new PageMetadata(0, 0, 0, 0, 0, false), reason);
    }
}
