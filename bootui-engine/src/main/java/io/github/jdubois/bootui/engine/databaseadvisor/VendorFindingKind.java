package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * A typed key for one vendor catalog augmentation (a read-only {@code pg_catalog}/{@code information_schema}
 * query that the generic JDBC metadata API has no equivalent for).
 *
 * <p>Keys replace the previous "one hardcoded {@code List<...>} field per vendor finding" design on
 * {@link SchemaSnapshot}: adding an augmentation now means declaring a key and a record, not widening a
 * record every rule and test constructs.</p>
 *
 * @param id a stable identifier, also used in diagnostics ({@code postgresql.invalid-indexes})
 * @param label a human-readable label for diagnostics
 * @param type the finding record type, which makes {@link VendorFindings} lookups type-safe
 */
record VendorFindingKind<T>(String id, String label, Class<T> type, boolean enrichment) {

    VendorFindingKind(String id, String label, Class<T> type) {
        this(id, label, type, false);
    }
}
