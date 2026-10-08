package io.github.jdubois.bootui.core.dto;

/**
 * One effective fault tolerance policy setting, with the provenance that produced it.
 *
 * <p>Values are already-rendered display strings (for example {@code "50%"}, {@code "3"} or
 * {@code "PT2S"}) so the shared UI never has to re-implement a library's unit conventions. A setting the
 * host library does not expose is omitted entirely rather than guessed.</p>
 *
 * @param name stable, human-readable setting name (for example {@code "failureRateThreshold"})
 * @param value rendered value; never a credential, payload or raw exception message
 * @param provenance where the value came from: {@code DEFAULT}, {@code CONFIGURED} or {@code UNKNOWN}
 */
public record FaultTolerancePolicySettingDto(String name, String value, String provenance) {}
