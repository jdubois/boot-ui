package io.github.jdubois.bootui.engine.email;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Framework-neutral, in-memory, bounded store of outgoing emails captured by whichever adapter binding
 * wraps the host application's mail sender ({@code JavaMailSender} on Spring,
 * {@code Mailer}/{@code ReactiveMailer} on Quarkus). Both adapters intercept the same way: capture
 * first, then either send for real (pass-through, the default) or skip sending (dev-trap mode, strictly
 * opt-in), so this store never itself decides whether to send.
 *
 * <p>Capped at {@code maxEntries}; the oldest entry is evicted once full so the buffer never grows
 * unbounded. Safe to call concurrently: {@link #capture} may run on the thread sending mail while
 * {@link #list} / {@link #get} run on an HTTP request thread.</p>
 */
public final class EmailStore {

    /** One captured email, stamped with a stable id and capture timestamp. */
    public record Entry(String id, long timestamp, CapturedEmail email, boolean sent) {}

    private final int maxEntries;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Object lock = new Object();

    public EmailStore(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    public int maxEntries() {
        return maxEntries;
    }

    /**
     * Captures one email, assigning it a stable id and the current timestamp.
     *
     * @param email the raw captured email
     * @param sent whether it was (or, for a batch, will be) handed to the real mail transport
     * @return the stored entry, including its assigned id
     */
    public Entry capture(CapturedEmail email, boolean sent) {
        Entry entry = new Entry("email-" + sequence.incrementAndGet(), System.currentTimeMillis(), email, sent);
        synchronized (lock) {
            entries.addFirst(entry);
            while (entries.size() > maxEntries) {
                entries.removeLast();
            }
        }
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

    /** Discards all captured entries. */
    public void clear() {
        synchronized (lock) {
            entries.clear();
        }
    }
}
