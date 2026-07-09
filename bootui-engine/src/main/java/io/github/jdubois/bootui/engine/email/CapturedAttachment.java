package io.github.jdubois.bootui.engine.email;

import io.github.jdubois.bootui.core.dto.EmailAttachmentDto;

/**
 * A single attachment as reported by whichever mail API the host application used
 * ({@code jakarta.mail} on Spring, {@code io.quarkus.mailer.Mail.Attachment} on Quarkus).
 *
 * @param filename the attachment's file name, or {@code null} when not known
 * @param contentType the attachment's MIME content type, or {@code null} when not known
 * @param sizeBytes the attachment's size in bytes, or {@code null} when not known
 */
public record CapturedAttachment(String filename, String contentType, Long sizeBytes) {

    public EmailAttachmentDto toDto() {
        return new EmailAttachmentDto(filename, contentType, sizeBytes);
    }
}
