package io.github.jdubois.bootui.core.dto;

/**
 * One correlation identifier BootUI captured from an inbound request header (for example
 * {@code X-Correlation-ID}), as rendered to the browser.
 *
 * <p>The captured identifier is user/gateway data, not BootUI metadata, so it is <strong>masked by
 * default</strong>: {@code value} only carries the raw identifier when the live value-exposure policy is
 * {@code FULL}, is {@code null} under {@code METADATA_ONLY}, and is otherwise the shared masked
 * placeholder. {@code masked} says which of those happened, so the UI can decide whether reveal and copy
 * actions are permitted instead of guessing from the rendered text.</p>
 *
 * <p>{@code lookupId} is a one-way, domain-separated digest of the normalized identifier, not the
 * identifier itself. It is what BootUI matches on when filtering activity by correlation identifier and
 * what is propagated to correlated child entries, so exact filtering and cross-panel linking keep working
 * while the value itself stays masked and never appears in a BootUI-generated URL.</p>
 *
 * @param name normalized (lower-case) header name the identifier was read from
 * @param value the identifier as it may be displayed: the raw value only under {@code FULL} exposure,
 *     {@code null} under {@code METADATA_ONLY}, and the masked placeholder otherwise
 * @param masked whether {@code value} is masked or withheld rather than the raw identifier
 * @param truncated whether the captured identifier was longer than the per-value bound and was cut down
 *     to it (both the displayed value and {@code lookupId} then describe the truncated identifier)
 * @param lookupId opaque, one-way lookup identity used for exact matching and child propagation
 */
public record CorrelationIdDto(String name, String value, boolean masked, boolean truncated, String lookupId) {}
