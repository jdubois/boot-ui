package io.github.jdubois.bootui.engine.journal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Supplies request metadata from HTTP journal events while deliberately omitting the concrete request path and all
 * resource/timing evidence.
 */
public final class HttpPayloadDurableJournalDetailProvider implements DurableJournalDetailProvider {

    private static final JournalTextExposure EXPOSURE = JournalTextExposure.masked();

    @Override
    public List<DurableJournalDetail> details(DurableJournalScope scope, List<JournalEntry> entries) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(entries, "entries must not be null");
        List<DurableJournalDetail> details = new ArrayList<>();
        for (JournalEntry entry : entries) {
            if (entry == null || entry.event() == null || !(entry.event().payload() instanceof HttpPayload request)) {
                continue;
            }
            details.add(DurableJournalDetail.request(
                    scope,
                    entry.sequence(),
                    bounded(EXPOSURE.message(request.method())),
                    bounded(EXPOSURE.path(request.routeTemplate())),
                    bounded(EXPOSURE.message(request.operation())),
                    request.status()));
        }
        return List.copyOf(details);
    }

    private static String bounded(String value) {
        if (value == null || value.length() <= DurableJournalDetail.MAX_TEXT_LENGTH) {
            return value;
        }
        return value.substring(0, DurableJournalDetail.MAX_TEXT_LENGTH);
    }
}
