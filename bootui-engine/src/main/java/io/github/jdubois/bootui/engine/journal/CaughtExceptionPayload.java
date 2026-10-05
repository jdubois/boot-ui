package io.github.jdubois.bootui.engine.journal;

import java.util.List;

/**
 * What the BootUI agent's {@code caught-exceptions} sensor reported ({@code docs/PLAN-v2.md} M5-6a): an exception
 * application code caught at one handler, a caught exception found again leaving a method by a throw or caught again,
 * occurrences of a site counted rather than reported one by one, or a caught exception whose fate the agent stopped
 * tracking. Its event's request and execution are the owner the exception was caught under. Never the exception's
 * message, stack trace, or fields.
 *
 * <p>{@code identity} is the exception's {@code System.identityHashCode}, which only joins a {@code thrown} event to
 * its {@code caught} one inside the engine: no read, render, export, or persisted row carries it.</p>
 *
 * @param kind {@value #CAUGHT}, {@value #THROWN}, {@value #UNTRACKED}, or {@value #EVICTED}
 * @param siteClass the class of the handler that caught it
 * @param siteMethod the handler's method, its name and descriptor
 * @param line the handler's line, or 0 when unknown
 * @param declaredTypes the types the handler declares, deduplicated
 * @param siteFlags the handler's flags as the agent read them ({@code CaughtExceptions.FLAG_*})
 * @param exceptionClass the caught exception's class, for {@value #CAUGHT} only
 * @param family {@code sql}, {@code io}, or {@code data-access} when the exception is or extends one of them, else
 *     {@code null}
 * @param count 1, or for {@value #UNTRACKED} how many occurrences were counted
 * @param foundBy for {@value #THROWN}: {@value #EXIT}, it left a method by a throw, or {@value #CAUGHT_AGAIN}, an
 *     instrumented handler caught it again; else {@code null}
 * @param foundAt for {@value #THROWN}: the method whose exit or handler found it, {@code class#method}; else {@code null}
 * @param identity the exception's identity hash, internal to the engine
 */
public record CaughtExceptionPayload(
        String kind,
        String siteClass,
        String siteMethod,
        int line,
        List<String> declaredTypes,
        int siteFlags,
        String exceptionClass,
        String family,
        long count,
        String foundBy,
        String foundAt,
        int identity)
        implements RuntimeEventPayload {

    public static final String CAUGHT = "caught";
    public static final String THROWN = "thrown";
    public static final String UNTRACKED = "untracked";
    public static final String EVICTED = "evicted";

    public static final String EXIT = "exit";
    public static final String CAUGHT_AGAIN = "caught-again";

    /** The most declared types kept. */
    public static final int MAX_TYPES = 8;

    public CaughtExceptionPayload {
        declaredTypes = declaredTypes == null
                ? List.of()
                : List.copyOf(declaredTypes.stream().limit(MAX_TYPES).toList());
    }

    /** This payload with its strings replaced by the run's shared copies. */
    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return new CaughtExceptionPayload(
                dictionary.shared(kind),
                dictionary.shared(siteClass),
                dictionary.shared(siteMethod),
                line,
                declaredTypes.stream().map(dictionary::shared).toList(),
                siteFlags,
                dictionary.shared(exceptionClass),
                dictionary.shared(family),
                count,
                dictionary.shared(foundBy),
                dictionary.shared(foundAt),
                identity);
    }

    @Override
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    @Override
    public int estimatedBytes(JournalDictionary dictionary) {
        return 56
                + JournalDictionary.REFERENCE_BYTES * declaredTypes.size()
                + JournalDictionary.retained(dictionary, kind)
                + JournalDictionary.retained(dictionary, siteClass)
                + JournalDictionary.retained(dictionary, siteMethod)
                + declaredTypes.stream()
                        .mapToInt(type -> JournalDictionary.retained(dictionary, type))
                        .sum()
                + JournalDictionary.retained(dictionary, exceptionClass)
                + JournalDictionary.retained(dictionary, family)
                + JournalDictionary.retained(dictionary, foundBy)
                + JournalDictionary.retained(dictionary, foundAt);
    }
}
