package io.github.jdubois.bootui.engine.vulnerabilities;

/**
 * One CVE's FIRST.org EPSS (Exploit Prediction Scoring System) figures, fetched by an adapter's
 * {@code GET https://api.first.org/data/v1/epss?cve=...} call and applied back onto the matching
 * {@link io.github.jdubois.bootui.core.dto.DependencyVulnerabilityDto} via
 * {@link DependencyReports#applyEpssScores}. Not a DTO itself (never serialized directly) &mdash; just the
 * engine-side carrier that keeps the adapter's JSON parsing decoupled from the DTO's field shape.
 *
 * @param probability the modeled probability (0.0-1.0) of exploitation in the next 30 days
 * @param percentile the percentile (0.0-1.0) that probability ranks against every other scored CVE
 */
public record EpssScore(double probability, double percentile) {}
