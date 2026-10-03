package io.github.jdubois.bootui.spi;

/**
 * A BootUI-owned in-memory store that <b>Free BootUI memory</b> may empty, so the JVM memory panels measure the
 * application rather than what BootUI buffers about it.
 *
 * <p>Adapters never list implementations by type. They hand every live singleton they already hold to
 * {@code MemoryOffloadService}, which keeps only instances of this interface. A store whose optional integration is
 * absent is therefore never created nor named, so no optional class is ever linked to find it.</p>
 *
 * <p>Implementations drop retained diagnostic data only. They keep their configuration, their recording or pause state,
 * their subscribers, and any user decision, so capture resumes from live traffic exactly as before.</p>
 */
public interface MemoryOffloadable {

    /** A stable kebab-case id, such as {@code sql-trace}. */
    String offloadId();

    /** A short human-readable name, such as {@code SQL Trace statements}. */
    String offloadLabel();

    /**
     * Drops every retained entry. Must be safe to call from a request thread while the store keeps recording.
     *
     * @return the number of retained entries dropped
     */
    long offloadRetainedData();
}
