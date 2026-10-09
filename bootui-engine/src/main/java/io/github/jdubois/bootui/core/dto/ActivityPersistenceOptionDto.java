package io.github.jdubois.bootui.core.dto;

/**
 * Describes the Live Activity persistence option's current state, so the panel can render its
 * "Currently saving X events in memory" tip and "Use a database" affordance without a separate request.
 * Always populated on every {@code GET /bootui/api/activity} response, regardless of whether persistence
 * is currently active.
 *
 * @param active whether entries are currently being durably persisted (equivalent to the response
 *     carrying a non-null {@code pageInfo}, but explicit so the UI does not need to infer it)
 * @param dataSourceAvailable whether a {@code DataSource} bean is available for the "Use the existing
 *     datasource" action to reuse, independent of whether persistence is currently active
 * @param tableName the table name that is (or would be) used, always resolved regardless of {@code active}
 */
public record ActivityPersistenceOptionDto(boolean active, boolean dataSourceAvailable, String tableName) {}
