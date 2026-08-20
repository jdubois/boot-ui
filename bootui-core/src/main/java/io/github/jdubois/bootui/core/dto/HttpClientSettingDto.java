package io.github.jdubois.bootui.core.dto;

/**
 * One effective transport setting of a declared HTTP client, together with where its value came from.
 *
 * <p>A {@code null} {@link #value()} means the setting is <em>explicitly unavailable</em>: neither the
 * framework nor the underlying client library exposes it safely without building the client. BootUI never
 * substitutes a guessed default for an unavailable value, so {@code provenance} is then
 * {@code UNAVAILABLE} and {@code source} is {@code null}.</p>
 *
 * @param category coarse grouping, one of {@code BASE_URL}, {@code TIMEOUT}, {@code CONNECTION_POOL},
 *     {@code RETRY}, {@code REDIRECT}, {@code PROXY}, {@code TLS} or {@code TRANSPORT}
 * @param name human-readable setting name, stable across adapters where the concept is shared
 * @param value the effective value as displayed, already masked, or {@code null} when unavailable
 * @param provenance one of {@code CLIENT}, {@code ANNOTATION}, {@code APPLICATION}, {@code FRAMEWORK} or
 *     {@code UNAVAILABLE}
 * @param source the configuration key or annotation attribute the value came from, or {@code null}
 */
public record HttpClientSettingDto(String category, String name, String value, String provenance, String source) {}
