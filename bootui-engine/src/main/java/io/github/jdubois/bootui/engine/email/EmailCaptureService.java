package io.github.jdubois.bootui.engine.email;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.EmailMessageDto;
import io.github.jdubois.bootui.core.dto.EmailsReport;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import java.util.List;

/**
 * Framework-neutral service backing the Email Viewer panel: wraps an {@link EmailStore} with masking
 * (via the shared {@link ExposurePolicy}) and dev-trap policy, and assembles the browser-facing
 * {@link EmailsReport}/{@link EmailMessageDto} DTOs.
 *
 * <p>Recipients, subject, and body are masked at <em>read</em> time (not at capture time), exactly like
 * BootUI's other masked panels, so a live change to {@code bootui.expose-values} takes effect on the
 * next request without needing to re-capture anything.</p>
 */
public final class EmailCaptureService {

    private final EmailStore store;
    private final ExposurePolicy exposurePolicy;
    private final boolean devTrapEnabled;

    public EmailCaptureService(EmailStore store, ExposurePolicy exposurePolicy, boolean devTrapEnabled) {
        this.store = store;
        this.exposurePolicy = exposurePolicy;
        this.devTrapEnabled = devTrapEnabled;
    }

    /** Whether dev-trap mode is enabled: captured messages are recorded but never actually sent. */
    public boolean isDevTrapEnabled() {
        return devTrapEnabled;
    }

    /** Installs the trace-id provider used when stamping captured messages. */
    public void setTraceIdProvider(TraceIdProvider traceIdProvider) {
        this.store.setTraceIdProvider(traceIdProvider);
    }

    /** Registers a listener notified whenever the captured-message store changes. */
    public Runnable subscribe(Runnable listener) {
        return store.subscribe(listener);
    }

    /**
     * Captures one outgoing email.
     *
     * @param email the raw captured email
     * @return {@code true} when the caller should still hand the message to the real mail transport
     *     (pass-through, the default), or {@code false} when dev-trap mode intercepted it
     */
    public boolean capture(CapturedEmail email) {
        boolean sent = !devTrapEnabled;
        store.capture(email, sent);
        return sent;
    }

    /** Lists all captured messages, newest-first, masked according to the live exposure policy. */
    public EmailsReport list() {
        List<EmailStore.Entry> entries = store.list();
        return new EmailsReport(
                true,
                null,
                devTrapEnabled,
                store.maxEntries(),
                entries.size(),
                entries.stream().map(this::toDto).toList());
    }

    /** Returns one captured message by id, masked according to the live exposure policy. */
    public EmailMessageDto get(String id) {
        return store.get(id).map(this::toDto).orElse(null);
    }

    /** Discards all captured messages. */
    public void clear() {
        store.clear();
    }

    private EmailMessageDto toDto(EmailStore.Entry entry) {
        CapturedEmail email = entry.email();
        boolean reveal = exposurePolicy.valueExposure() == ValueExposure.FULL;
        String masked = SecretMasker.MASKED_VALUE;
        return new EmailMessageDto(
                entry.id(),
                entry.timestamp(),
                reveal ? email.from() : masked,
                reveal ? email.to() : maskEach(email.to()),
                reveal ? email.cc() : maskEach(email.cc()),
                reveal ? email.bcc() : maskEach(email.bcc()),
                reveal ? email.subject() : masked,
                reveal ? email.textBody() : maskIfPresent(email.textBody()),
                reveal ? email.htmlBody() : maskIfPresent(email.htmlBody()),
                email.attachments().stream().map(CapturedAttachment::toDto).toList(),
                entry.sent(),
                entry.traceId(),
                entry.thread());
    }

    private static List<String> maskEach(List<String> addresses) {
        return addresses.stream().map(address -> SecretMasker.MASKED_VALUE).toList();
    }

    private static String maskIfPresent(String value) {
        return value == null ? null : SecretMasker.MASKED_VALUE;
    }
}
