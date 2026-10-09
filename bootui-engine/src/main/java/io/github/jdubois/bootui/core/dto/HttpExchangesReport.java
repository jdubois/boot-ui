package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The HTTP Exchanges panel report.
 *
 * @param total visible exchanges after self-exclusion, before filtering and paging
 * @param recorded exchanges read from the recorder
 * @param hiddenSelf BootUI's own exchanges hidden at read time
 * @param exchanges the requested page of visible exchanges, newest first
 * @param page paging metadata for {@code exchanges}
 * @param unavailableReason populated when no recorder is available
 * @param retention how the recorder retains exchanges, or {@code null} when no recorder is available
 */
public record HttpExchangesReport(
        int total,
        int recorded,
        int hiddenSelf,
        List<HttpExchangeDto> exchanges,
        PageMetadata page,
        String unavailableReason,
        CaptureRetentionDto retention) {

    public HttpExchangesReport {
        exchanges = DtoCollections.immutableCopy(exchanges);
    }

    /** A report without retention counts, for callers that assemble exchanges from no known recorder. */
    public HttpExchangesReport(
            int total,
            int recorded,
            int hiddenSelf,
            List<HttpExchangeDto> exchanges,
            PageMetadata page,
            String unavailableReason) {
        this(total, recorded, hiddenSelf, exchanges, page, unavailableReason, null);
    }

    public static HttpExchangesReport unavailable(String reason) {
        return new HttpExchangesReport(0, 0, 0, List.of(), new PageMetadata(0, 0, 0, 0, 0, false), reason, null);
    }
}
