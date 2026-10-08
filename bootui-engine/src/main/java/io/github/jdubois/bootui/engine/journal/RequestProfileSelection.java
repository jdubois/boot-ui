package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.core.dto.RequestJournalProfileDto;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.core.dto.RequestProfileSelectionDto;
import io.github.jdubois.bootui.engine.mcp.McpToolClientException;
import io.github.jdubois.bootui.engine.web.ExecutionProfileAssembler;
import java.util.function.Function;

/**
 * Selects the retained journal evidence first, and the HTTP-exchange buffer when it cannot answer: the agent read
 * behind {@code get_request_profile} and {@code bootui request-profile}.
 *
 * <p>An id the enabled journal and the buffer both do not have is unknown or expired, which is a statement about the
 * request: it is refused as a tool error ({@link McpToolClientException}, 404). {@code available=false} is kept for
 * what the caller cannot fix by choosing another id: the journal is off, the id's panel hides it, or its request
 * carries nothing to correlate.
 */
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
        if (RequestJournalProfiles.notRetained(recorded, id) && ExecutionProfileAssembler.notInBuffer(legacy, id)) {
            throw new McpToolClientException(404, unknownIdMessage(id));
        }
        return new RequestProfileSelectionDto(
                false,
                "Neither the runtime journal nor the HTTP-exchange buffer retains " + id + ". Journal: "
                        + recorded.unavailableReason() + " Buffer: " + legacy.unavailableReason(),
                "none",
                null,
                null);
    }

    /** The refusal of an id neither source records. */
    static String unknownIdMessage(String id) {
        return "Unknown request or execution id " + id + ": neither the runtime journal nor the HTTP-exchange buffer"
                + " retains it, so it was evicted, cleared, or never recorded. Use an id from get_live_activity or a"
                + " get_runtime_insights exemplar.";
    }
}
