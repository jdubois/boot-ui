package io.github.jdubois.bootui.engine.email;

import io.github.jdubois.bootui.core.dto.EmailMessageDto;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders a captured {@link EmailMessageDto} as a downloadable {@code message/rfc822} (.eml) document.
 *
 * <p>Framework-neutral on purpose: both the Spring ({@code EmailController}) and Quarkus
 * ({@code EmailResource}) bindings delegate here so the {@code .eml} bytes are byte-identical on either
 * runtime, exactly like the rest of the Email Viewer panel shares one engine. It builds only from the
 * masked DTO the read path already produced, so the download honors the same value-exposure policy as the
 * list/detail views. Attachment content is never captured, so only attachment metadata is appended.</p>
 */
public final class EmailEmlRenderer {

    private static final DateTimeFormatter EML_DATE_FORMAT = DateTimeFormatter.RFC_1123_DATE_TIME;

    private EmailEmlRenderer() {}

    /** Renders {@code message} as an RFC 822 {@code .eml} string (CRLF line endings). */
    public static String render(EmailMessageDto message) {
        StringBuilder builder = new StringBuilder();
        builder.append("From: ").append(nullToEmpty(message.from())).append("\r\n");
        appendAddressHeader(builder, "To", message.to());
        appendAddressHeader(builder, "Cc", message.cc());
        appendAddressHeader(builder, "Bcc", message.bcc());
        builder.append("Subject: ").append(nullToEmpty(message.subject())).append("\r\n");
        builder.append("Date: ")
                .append(EML_DATE_FORMAT.format(
                        Instant.ofEpochMilli(message.timestamp()).atZone(ZoneOffset.UTC)))
                .append("\r\n");
        boolean hasHtml = message.htmlBody() != null;
        builder.append("Content-Type: ")
                .append(hasHtml ? "text/html; charset=UTF-8" : "text/plain; charset=UTF-8")
                .append("\r\n\r\n");
        builder.append(hasHtml ? message.htmlBody() : nullToEmpty(message.textBody()));
        if (!message.attachments().isEmpty()) {
            builder.append("\r\n\r\n--- Attachments (metadata only, content not captured) ---\r\n");
            message.attachments()
                    .forEach(attachment -> builder.append(attachment.filename())
                            .append(" (")
                            .append(attachment.contentType())
                            .append(", ")
                            .append(attachment.sizeBytes())
                            .append(" bytes)\r\n"));
        }
        return builder.toString();
    }

    private static void appendAddressHeader(StringBuilder builder, String name, List<String> addresses) {
        if (addresses != null && !addresses.isEmpty()) {
            builder.append(name)
                    .append(": ")
                    .append(String.join(", ", addresses))
                    .append("\r\n");
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
