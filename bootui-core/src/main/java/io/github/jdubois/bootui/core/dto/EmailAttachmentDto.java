package io.github.jdubois.bootui.core.dto;

/**
 * Metadata for one attachment captured on an outgoing email. Never carries attachment contents,
 * only the name/content-type/size a browser needs to render a summary line.
 *
 * @param filename the attachment's file name, or {@code null} when not known
 * @param contentType the attachment's MIME content type, or {@code null} when not known
 * @param sizeBytes the attachment's size in bytes, or {@code null} when not known
 */
public record EmailAttachmentDto(String filename, String contentType, Long sizeBytes) {}
