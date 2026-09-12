package io.github.jdubois.bootui.core.dto;

/**
 * One client backend as {@code pg_stat_activity} sees it right now.
 *
 * <p>This is the only genuinely live section of the panel: every other section reports counters accumulated
 * since the last statistics reset, while this one is a snapshot of what the server is doing at the instant
 * of the read.</p>
 *
 * <p>Every component except {@link #pid()} is nullable, because {@code pg_stat_activity} degrades by nulling
 * columns rather than by hiding rows: a role that is not a member of {@code pg_monitor} still sees one row
 * per backend, but the state, wait event and statement of backends it does not own come back {@code NULL}.
 * A null is therefore "not visible to this role", never "idle and harmless".</p>
 *
 * @param pid the backend's process id
 * @param user the role the backend authenticated as
 * @param applicationName the client-supplied {@code application_name}
 * @param clientAddress the client address as the server sees it, host-local infrastructure metadata
 * @param state {@code active}, {@code idle}, {@code idle in transaction}, ...
 * @param waitEventType the class of wait ({@code Lock}, {@code IO}, {@code Client}, ...), or {@code null}
 *     when the backend is not waiting
 * @param waitEvent the specific wait event within that class
 * @param blockedBy the pids blocking this backend, comma-separated, or {@code null} when it is not blocked
 * @param stateSeconds how long the backend has been in its current state
 * @param transactionSeconds age of the backend's open transaction, or {@code null} when it has none
 * @param querySeconds how long the current — or, when idle, the last — statement has been running
 * @param query the statement text, credential-redacted, masked under the exposure policy and truncated;
 *     unlike {@code pg_stat_statements}, this text is <em>not</em> normalized by the server
 */
public record PostgresSessionDto(
        int pid,
        String user,
        String applicationName,
        String clientAddress,
        String state,
        String waitEventType,
        String waitEvent,
        String blockedBy,
        Double stateSeconds,
        Double transactionSeconds,
        Double querySeconds,
        String query) {}
