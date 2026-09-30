package io.github.jdubois.bootui.engine.jms;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.jms.JmsActivityRecorder.CapturedMessage;

/** Maps captured JMS metadata to top-level {@code MESSAGING} Live Activity entries. */
public final class JmsActivityEntries {

    private static final String TYPE_MESSAGING = "MESSAGING";
    private static final String SEVERITY_OK = "OK";
    private static final String SEVERITY_ERROR = "ERROR";

    private JmsActivityEntries() {}

    /** The Live Activity entry id of {@code message}. */
    public static String entryId(CapturedMessage message) {
        return "jms-" + message.id();
    }

    /** The entry of {@code message}, top-level. */
    public static ActivityEntryDto toEntry(CapturedMessage message) {
        return toEntry(message, null);
    }

    /**
     * The entry of {@code message}, nested under {@code parentId}: an outgoing message under the request or
     * execution that sent it ({@code docs/PLAN-v2.md} §5.1).
     */
    public static ActivityEntryDto toEntry(CapturedMessage message, String parentId) {
        String severity = message.success() ? SEVERITY_OK : SEVERITY_ERROR;
        String arrow = message.direction() == JmsActivityRecorder.Direction.PRODUCE ? "→" : "←";
        String destination = message.destination();
        String summary =
                arrow + " " + (destination == null || destination.isBlank() ? "(unknown destination)" : destination);

        StringBuilder detail = new StringBuilder();
        if (message.messageId() != null) {
            detail.append("messageId=").append(message.messageId());
        }
        if (!message.success() && message.failureType() != null) {
            if (detail.length() > 0) {
                detail.append(' ');
            }
            detail.append(message.failureType());
        }

        return new ActivityEntryDto(
                entryId(message),
                TYPE_MESSAGING,
                message.timestamp(),
                severity,
                summary,
                detail.length() > 0 ? detail.toString() : null,
                message.durationMillis(),
                null,
                null,
                null,
                null,
                null,
                false,
                parentId,
                null,
                false);
    }
}
