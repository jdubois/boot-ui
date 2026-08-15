package io.github.jdubois.bootui.engine.email;

import io.github.jdubois.bootui.spi.TraceIdProvider;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Framework-neutral, in-memory, bounded store of outgoing emails captured by whichever adapter binding
 * wraps the host application's mail sender ({@code JavaMailSender} on Spring,
 * {@code Mailer}/{@code ReactiveMailer} on Quarkus). Both adapters intercept the same way: capture
 * first, then either send for real (pass-through, the default) or skip sending (dev-trap mode, strictly
 * opt-in), so this store never itself decides whether to send.
 *
 * <p>Capped at {@code maxEntries}; the oldest entry is evicted once full so the buffer never grows
 * unbounded. Attachment content is never captured (only metadata), but a single message's text/HTML
 * body is otherwise an unbounded application-supplied string; each body is additionally truncated at
 * {@code maxBodyLength} characters so one oversized message cannot spike memory before the entry-count
 * cap would evict it (the same "bounded, no unbounded growth" discipline Mailtraxx documents via its
 * own {@code --max-size} flag). Safe to call concurrently: {@link #capture} may run on the thread
 * sending mail while {@link #list} / {@link #get} run on an HTTP request thread.</p>
 */
public final class EmailStore {

    /** Default cap (characters) applied to each captured text/HTML body when none is configured. */
    public static final int DEFAULT_MAX_BODY_LENGTH = 200_000;

    /** One captured email, stamped with a stable id, capture timestamp, trace id, and thread. */
    public record Entry(String id, long timestamp, CapturedEmail email, boolean sent, String traceId, String thread) {}

    private final int maxEntries;
    private final int maxBodyLength;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Object lock = new Object();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile TraceIdProvider traceIdProvider = EmailStore::mdcTraceId;

    public EmailStore(int maxEntries) {
        this(maxEntries, DEFAULT_MAX_BODY_LENGTH);
    }

    public EmailStore(int maxEntries, int maxBodyLength) {
        this.maxEntries = Math.max(1, maxEntries);
        this.maxBodyLength = Math.max(1, maxBodyLength);
    }

    public int maxEntries() {
        return maxEntries;
    }

    /** Maximum number of characters retained per captured text/HTML body before truncation. */
    public int maxBodyLength() {
        return maxBodyLength;
    }

    /**
     * Replaces the trace-id source used to stamp each captured email. Defaults to the SLF4J MDC
     * {@code traceId} key that Micrometer Tracing publishes on Spring MVC. Passing {@code null}
     * restores that default.
     */
    public void setTraceIdProvider(TraceIdProvider traceIdProvider) {
        this.traceIdProvider = traceIdProvider == null ? EmailStore::mdcTraceId : traceIdProvider;
    }

    /**
     * Captures one email, assigning it a stable id and the current timestamp.
     *
     * @param email the raw captured email
     * @param sent whether it was (or, for a batch, will be) handed to the real mail transport
     * @return the stored entry, including its assigned id
     */
    public Entry capture(CapturedEmail email, boolean sent) {
        Entry entry = new Entry(
                "email-" + sequence.incrementAndGet(),
                System.currentTimeMillis(),
                boundBodies(email),
                sent,
                resolveTraceId(),
                Thread.currentThread().getName());
        synchronized (lock) {
            entries.addFirst(entry);
            while (entries.size() > maxEntries) {
                entries.removeLast();
            }
        }
        notifyListeners();
        return entry;
    }

    /** Returns all captured entries, newest-first. */
    public List<Entry> list() {
        synchronized (lock) {
            return new ArrayList<>(entries);
        }
    }

    /** Returns the entry with the given id, if still retained. */
    public Optional<Entry> get(String id) {
        synchronized (lock) {
            return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
        }
    }

    /** Number of entries currently retained. */
    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    /**
     * Registers a listener invoked whenever the store changes. Returns a handle that removes the
     * listener when run.
     */
    public Runnable subscribe(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Discards all captured entries. */
    public void clear() {
        synchronized (lock) {
            entries.clear();
        }
        notifyListeners();
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // A misbehaving stream subscriber must never disrupt email capture.
            }
        }
    }

    private String resolveTraceId() {
        try {
            String traceId = traceIdProvider.currentTraceId();
            return traceId == null || traceId.isBlank() ? null : traceId;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Returns {@code email} unchanged when both bodies are within {@link #maxBodyLength}, otherwise
     * returns a copy with the oversized body/bodies truncated. Attachments are never touched here: their
     * content is never captured in the first place (metadata only), so they carry no unbounded payload.
     */
    private CapturedEmail boundBodies(CapturedEmail email) {
        boolean textTruncated = email.textBody() != null && email.textBody().length() > maxBodyLength;
        boolean htmlTruncated = email.htmlBody() != null && email.htmlBody().length() > maxBodyLength;
        if (!textTruncated && !htmlTruncated) {
            return email;
        }
        return CapturedEmail.builder()
                .from(email.from())
                .to(email.to())
                .cc(email.cc())
                .bcc(email.bcc())
                .subject(email.subject())
                .textBody(textTruncated ? truncate(email.textBody()) : email.textBody())
                .htmlBody(htmlTruncated ? truncate(email.htmlBody()) : email.htmlBody())
                .attachments(email.attachments())
                .build();
    }

    private String truncate(String body) {
        return body.substring(0, maxBodyLength) + "\n…[truncated, showing " + maxBodyLength + " of " + body.length()
                + " characters]";
    }

    private static String mdcTraceId() {
        try {
            String traceId = org.slf4j.MDC.get("traceId");
            return traceId == null || traceId.isBlank() ? null : traceId;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
