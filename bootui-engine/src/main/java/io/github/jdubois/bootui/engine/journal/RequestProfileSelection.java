package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.core.dto.RequestJournalProfileDto;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.core.dto.RequestProfileSelectionDto;
import java.util.function.Function;

/** Selects the retained journal evidence first, and the HTTP-exchange buffer when it cannot answer. */
public final class RequestProfileSelection {

    private RequestProfileSelection() {}

    public static RequestProfileSelectionDto select(
            String id,
            Function<String, RequestJournalProfileDto> journal,
            Function<String, RequestProfileDto> buffers) {
        if (id == null || id.isBlank()) {
            return new RequestProfileSelectionDto(false, "No request or execution id was given.", "none", null, null);
        }
        RequestJournalProfileDto recorded = journal.apply(id);
        if (recorded.available()) {
            RequestProfileDto detail = recorded.status() == null ? null : buffers.apply(id);
            return new RequestProfileSelectionDto(
                    true, null, "journal", recorded, detail != null && detail.available() ? detail : null);
        }
        RequestProfileDto legacy = buffers.apply(id);
        if (legacy.available()) {
            // The HTTP Exchanges panel also links by exchange id, whereas the journal indexes by request id.
            String requestId =
                    legacy.request() == null ? null : legacy.request().requestId();
            if (requestId != null && !requestId.equals(id)) {
                RequestJournalProfileDto byRequestId = journal.apply(requestId);
                if (byRequestId.available()) {
                    return new RequestProfileSelectionDto(true, null, "journal", byRequestId, legacy);
                }
            }
            return new RequestProfileSelectionDto(true, null, "buffers", null, legacy);
        }
        return new RequestProfileSelectionDto(
                false,
                "Neither the runtime journal nor the HTTP-exchange buffer retains " + id + ". Journal: "
                        + recorded.unavailableReason() + " Buffer: " + legacy.unavailableReason(),
                "none",
                null,
                null);
    }
}
