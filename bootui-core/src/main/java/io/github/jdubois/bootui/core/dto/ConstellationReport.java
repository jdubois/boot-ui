package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Response for {@code GET /bootui/api/constellation}: the local multi-service peer topology the
 * Constellation panel renders. {@code enabled} mirrors {@code bootui.constellation.enabled} plus at
 * least one configured peer; when {@code false} the panel renders its setup guidance instead of a graph.
 */
public record ConstellationReport(boolean enabled, List<PeerNodeDto> peers) {}
