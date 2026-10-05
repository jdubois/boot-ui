package io.github.jdubois.bootui.autoconfigure.jms;

import io.github.jdubois.bootui.core.SecretMasker;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Queue;
import jakarta.jms.Topic;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JmsCaptureMetadata {

    private static final int MAX_METADATA_LENGTH = 200;
    private static final Pattern URI_USER_INFO = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^\\s/@]+@");
    private static final Pattern SECRET_ASSIGNMENT =
            Pattern.compile("(?i)(password|passwd|pwd|secret|token|credential|api[-_.]?key)(\\s*=\\s*)([^&;\\s]+)");

    private JmsCaptureMetadata() {}

    static String destination(Destination destination) {
        if (destination == null) {
            return null;
        }
        try {
            if (destination instanceof Queue queue) {
                return sanitize(queue.getQueueName());
            }
            if (destination instanceof Topic topic) {
                return sanitize(topic.getTopicName());
            }
        } catch (JMSException | RuntimeException ignored) {
            return null;
        }
        // Provider-specific Destination#toString() output may contain broker URLs, credentials,
        // or arbitrary properties. Unknown destination implementations are therefore not rendered.
        return null;
    }

    static String destination(Message message) {
        if (message == null) {
            return null;
        }
        try {
            return destination(message.getJMSDestination());
        } catch (JMSException | RuntimeException ignored) {
            return null;
        }
    }

    static String messageId(Message message) {
        if (message == null) {
            return null;
        }
        try {
            return message.getJMSMessageID();
        } catch (JMSException | RuntimeException ignored) {
            return null;
        }
    }

    static String listenerId(String listenerId) {
        return sanitize(listenerId);
    }

    static String subscriptionName(String subscriptionName) {
        return sanitize(subscriptionName);
    }

    static String failureType(Throwable failure) {
        return failure == null ? null : failure.getClass().getSimpleName();
    }

    static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = value.replaceAll("[\\r\\n\\t\\p{Cntrl}]+", " ").trim();
        sanitized = URI_USER_INFO.matcher(sanitized).replaceAll("$1" + SecretMasker.MASKED_VALUE + "@");
        Matcher matcher = SECRET_ASSIGNMENT.matcher(sanitized);
        sanitized = matcher.replaceAll("$1$2" + SecretMasker.MASKED_VALUE);
        if (sanitized.isBlank()) {
            return null;
        }
        return sanitized.length() <= MAX_METADATA_LENGTH
                ? sanitized
                : sanitized.substring(0, MAX_METADATA_LENGTH - 3) + "...";
    }
}
