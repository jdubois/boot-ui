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
 * and dev-trap policy, and assembles the browser-facing {@link EmailsReport}/{@link EmailMessageDto}
 * DTOs.
 *
 * <p>Unlike BootUI's config/secret-bearing panels, captured email content is revealed by default:
 * recipients, subject, and body are ordinary application-generated data (not credentials), matching
 * both Laravel Telescope's Mail watcher (no redaction) and this codebase's own established pattern for
 * other "captured data" panels (HTTP Exchanges, SQL Trace) which reveal by default and mask only
 * individually-flagged secret-shaped fields rather than blanket-masking everything. Content masking is
 * an explicit opt-in ({@code maskContent}), decoupled from the global {@code bootui.expose-values} /
 * {@link ExposurePolicy} that governs actual secrets elsewhere (Configuration, Beans, connection pool
 * URLs) — flipping that flag to read a config secret should not be required just to read a test email,
 * and vice versa. When {@code maskContent} is enabled, the previous behavior applies unchanged:
 * {@code from}/{@code to}/{@code cc}/{@code bcc}/{@code subject}/{@code textBody}/{@code htmlBody} are
 * masked unless {@link ExposurePolicy#valueExposure()} is {@link ValueExposure#FULL}. Masking (when
 * enabled) is applied at <em>read</em> time (not at capture time), so a live change takes effect on the
 * next request without needing to re-capture anything.</p>
 */
public final class EmailCaptureService {

    private final EmailStore store;
    private final ExposurePolicy exposurePolicy;
    private final boolean devTrapEnabled;
    private final boolean maskContent;

    public EmailCaptureService(
            EmailStore store, ExposurePolicy exposurePolicy, boolean devTrapEnabled, boolean maskContent) {
        this.store = store;
        this.exposurePolicy = exposurePolicy;
        this.devTrapEnabled = devTrapEnabled;
        this.maskContent = maskContent;
    }

    /** Whether dev-trap mode is enabled: captured messages are recorded but never actually sent. */
    public boolean isDevTrapEnabled() {
        return devTrapEnabled;
    }

    /**
     * Whether captured message content is masked by default (opt-in, off by default — see the class
     * Javadoc for the rationale).
     */
    public boolean isMaskContentEnabled() {
        return maskContent;
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

    /** Lists all captured messages, newest-first, revealed or masked per {@link #isMaskContentEnabled()}. */
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

    /** Returns one captured message by id, revealed or masked per {@link #isMaskContentEnabled()}. */
    public EmailMessageDto get(String id) {
        return store.get(id).map(this::toDto).orElse(null);
    }

    /** Discards all captured messages. */
    public void clear() {
        store.clear();
    }

    private EmailMessageDto toDto(EmailStore.Entry entry) {
        CapturedEmail email = entry.email();
        boolean reveal = !maskContent || exposurePolicy.valueExposure() == ValueExposure.FULL;
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
