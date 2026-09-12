package io.github.jdubois.bootui.engine.postgres;

import io.github.jdubois.bootui.core.dto.PostgresSectionDto;

/**
 * One subsystem's bounded read.
 *
 * <p>A collector never throws for an absent extension, an invisible view, a privilege it does not hold, or a
 * server too old for a column: it returns a {@code SKIPPED} or {@code FAILED} section carrying the reason,
 * and — where one exists — the exact statement that would make the section readable. A blocked collector is
 * never a silent pass.</p>
 */
interface PostgresCollector {

    String id();

    String title();

    PostgresSectionDto collect(PostgresReadContext context, PostgresDatabaseData data);

    /** A section that was read, with the rows it retained. */
    default PostgresSectionDto available(int rowCount, boolean truncated) {
        return new PostgresSectionDto(id(), title(), "AVAILABLE", null, null, rowCount, 0, truncated);
    }

    /** A section that was read, but not completely; the reason says which part is missing. */
    default PostgresSectionDto partial(int rowCount, String reason) {
        return new PostgresSectionDto(id(), title(), "AVAILABLE", reason, null, rowCount, 0, false);
    }

    /** A section BootUI deliberately did not read: no extension, no privilege, too old a server. */
    default PostgresSectionDto skipped(String reason, String hint) {
        return new PostgresSectionDto(id(), title(), "SKIPPED", reason, hint, 0, 0, false);
    }

    /** A section whose query failed; the redacted driver reason is preserved. */
    default PostgresSectionDto failed(String reason) {
        return new PostgresSectionDto(id(), title(), "FAILED", reason, null, 0, 0, false);
    }
}
