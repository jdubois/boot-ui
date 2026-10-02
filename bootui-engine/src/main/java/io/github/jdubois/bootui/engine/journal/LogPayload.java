package io.github.jdubois.bootui.engine.journal;

/**
 * A {@code WARN} or {@code ERROR} log event's payload: its logger, its level, its unformatted message template, such as
 * {@code "Connection {} timed out after {} ms"}, and the class of the exception it logged. Never the formatted message
 * or its arguments, so warnings group exactly without storing values ({@code docs/PLAN-v2.md} §5.2, §8).
 */
public record LogPayload(String logger, String level, String template, String exceptionClass)
        implements RuntimeEventPayload {

    /** The longest template kept; a longer one is truncated. */
    public static final int MAX_TEMPLATE_LENGTH = 500;

    public LogPayload {
        if (template != null && template.length() > MAX_TEMPLATE_LENGTH) {
            template = template.substring(0, MAX_TEMPLATE_LENGTH);
        }
    }

    /** Whether a level, as Logback or {@code java.util.logging} names it, is recorded: {@code WARN} and above. */
    public static boolean isRecorded(String level) {
        return level != null
                && switch (level) {
                    case "WARN", "WARNING", "ERROR", "SEVERE", "FATAL" -> true;
                    default -> false;
                };
    }

    /**
     * This log event with its logger, level, template, and exception class replaced by the run's shared copies. A
     * template with a digit keeps, and is counted for, its own copy, since an application that concatenates ids or
     * numbers into its messages would otherwise fill the dictionary with one-off strings.
     */
    @Override
    public RuntimeEventPayload interned(JournalDictionary dictionary) {
        return new LogPayload(
                dictionary.shared(logger),
                dictionary.shared(level),
                shareable(template) ? dictionary.shared(template) : template,
                dictionary.shared(exceptionClass));
    }

    /** Whether the dictionary should share {@code template}: it has no digit, which a concatenated value would add. */
    static boolean shareable(String template) {
        return template != null && template.chars().noneMatch(Character::isDigit);
    }

    /** Its fixed part and its strings, each counted as the payload's own. */
    @Override
    public int estimatedBytes() {
        return estimatedBytes(null);
    }

    /** Its fixed part, with each string {@code dictionary} shares counted as a reference. */
    @Override
    public int estimatedBytes(JournalDictionary dictionary) {
        return 16
                + JournalDictionary.retained(dictionary, logger)
                + JournalDictionary.retained(dictionary, level)
                + JournalDictionary.retained(dictionary, template)
                + JournalDictionary.retained(dictionary, exceptionClass);
    }
}
