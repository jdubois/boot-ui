package io.github.jdubois.bootui.core.dto;

/**
 * Tail of logs for one local development service.
 *
 * <p>The log text follows the value-exposure policy: secret-like assignments are masked under the default
 * {@code MASKED} mode, the text is omitted under {@code METADATA_ONLY}, and it is verbatim under {@code FULL}.</p>
 *
 * @param id the service identifier
 * @param logs the bounded log tail as the exposure policy allows it, or {@code null} when it is omitted
 * @param truncated whether older output was dropped to stay within {@code maxBytes}
 * @param maxBytes the maximum number of UTF-8 bytes returned
 * @param logsOmitted {@code true} when the exposure policy withheld the log text
 */
public record DevServiceLogReport(String id, String logs, boolean truncated, int maxBytes, boolean logsOmitted) {}
