package io.github.jdubois.bootui.core.dto;

/**
 * A Live Activity entry captured by a <strong>different</strong> BootUI instance that shares the same
 * durable activity store (the opt-in "Use the existing datasource" / shared-database persistence
 * option), correlated to the current request by the same W3C distributed-trace id every entry already
 * carries as {@link ActivityEntryDto#correlationId()}.
 *
 * <p>Surfaced only in {@link RequestProfileDto#remoteActivity()}, populated by a single, explicit,
 * trace-id-scoped lookup triggered by opening one specific request's own profile — never a general
 * cross-instance browse. Kept as its own record (rather than folding {@code instanceId} onto {@link
 * ActivityEntryDto} itself) so every other, far more common, single-instance use of that shape stays
 * free of a field that means nothing there.</p>
 *
 * @param instanceId the BootUI instance ({@code bootui.activity.persistence.instance-id}, or a resolved
 *     default such as the host name or application name) that captured {@code entry}
 * @param entry the captured entry, exactly as that instance recorded it, except its {@code parentId} is
 *     always cleared — it would otherwise reference a request id from that instance's own local id
 *     space, which is meaningless to this instance
 */
public record RemoteActivityEntryDto(String instanceId, ActivityEntryDto entry) {}
