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

    @Override
    public int estimatedBytes() {
        return 16
                + RuntimeEvent.stringBytes(logger)
                + RuntimeEvent.stringBytes(level)
                + RuntimeEvent.stringBytes(template)
                + RuntimeEvent.stringBytes(exceptionClass);
    }
}
