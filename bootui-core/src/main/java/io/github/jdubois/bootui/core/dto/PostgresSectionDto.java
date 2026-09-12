package io.github.jdubois.bootui.core.dto;

/**
 * One subsystem the PostgreSQL panel tried to read, and how far it got.
 *
 * <p>A section that could not be read is reported explicitly rather than disappearing: "the role cannot see
 * this view" must never look like "this subsystem is healthy". {@code AVAILABLE} with a zero
 * {@link #findingCount()} is the only way the panel says "checked and clean".</p>
 *
 * @param id the stable section id ({@code vital-signs}, {@code statements}, {@code indexes}, {@code tables},
 *     {@code vacuum}, {@code replication}, {@code settings})
 * @param title the human-readable section title
 * @param status {@code AVAILABLE}, {@code SKIPPED} or {@code FAILED}
 * @param reason why the section was skipped or failed, already redacted and truncated; {@code null} when read
 * @param hint an actionable next step (for example the {@code CREATE EXTENSION} statement that would make the
 *     section readable), or {@code null}
 * @param rowCount how many rows the section actually retained
 * @param findingCount how many findings this section produced
 * @param truncated whether a row bound stopped the read short
 */
public record PostgresSectionDto(
        String id,
        String title,
        String status,
        String reason,
        String hint,
        int rowCount,
        int findingCount,
        boolean truncated) {}
