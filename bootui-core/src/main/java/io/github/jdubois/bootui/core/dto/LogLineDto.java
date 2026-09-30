package io.github.jdubois.bootui.core.dto;

/**
 * A single log line for the live log tail.
 *
 * <p>The message follows the value-exposure policy when the line is read: secret-like assignments are masked under
 * the default {@code MASKED} mode, the message is omitted under {@code METADATA_ONLY}, and it is verbatim under
 * {@code FULL}. The other fields are always present.</p>
 *
 * @param timestamp epoch milliseconds when the line was logged
 * @param level the log level, such as {@code INFO} or {@code WARN}
 * @param logger the logger name
 * @param message the message as the exposure policy allows it, or {@code null} when it is omitted or the application
 *     logged none
 * @param thread the name of the thread that logged the line
 * @param messageOmitted {@code true} when the exposure policy withheld the message, so a {@code null} message is not
 *     read as an empty line
 */
public record LogLineDto(
        long timestamp, String level, String logger, String message, String thread, boolean messageOmitted) {

    /** A captured line whose message has not been withheld. */
    public LogLineDto(long timestamp, String level, String logger, String message, String thread) {
        this(timestamp, level, logger, message, thread, false);
    }
}
