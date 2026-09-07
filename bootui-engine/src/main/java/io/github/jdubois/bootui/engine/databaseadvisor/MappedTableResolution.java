package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedSecondaryTableFacts;
import java.util.List;

/**
 * Resolves one mapped entity's physical table across every readable datasource, honestly.
 *
 * <p>An entity is only matched when its physical name is <em>known</em> (an explicit {@code @Table(name)}),
 * and only when exactly one table matches: with several datasources or several schemas in play, a bare table
 * name can legitimately exist more than once, and picking the first match would attribute findings to the
 * wrong database. An ambiguous match therefore reports {@link Status#AMBIGUOUS} and the rule skips that
 * entity instead of guessing.</p>
 *
 * <p>The same resolution applies to an explicitly named {@code @SecondaryTable}: {@link #resolveSecondary}
 * looks the name up among the entity's declared secondary tables and resolves it exactly like the primary
 * table, so a mapped item pinned to a secondary table (via {@code @Column(table=...)}/
 * {@code @JoinColumn(table=...)}) is checked against the table it actually lives in.</p>
 */
record MappedTableResolution(Status status, SchemaSnapshot schema, TableModel table, String detail) {

    enum Status {
        RESOLVED,
        NOT_MAPPED,
        NOT_FOUND,
        AMBIGUOUS,
        UNKNOWN
    }

    boolean resolved() {
        return status == Status.RESOLVED;
    }

    static MappedTableResolution resolve(DatabaseAdvisorContext context, MappedEntityFacts entity) {
        return resolveNamed(
                context,
                entity.explicitTableName(),
                entity.explicitCatalog(),
                entity.explicitSchema(),
                entity.explicitTableName());
    }

    static MappedTableResolution resolveDeclared(
            DatabaseAdvisorContext context, String catalog, String schema, String tableName) {
        return resolveNamed(context, tableName, catalog, schema, tableName);
    }

    /**
     * Resolves an explicitly named {@code @SecondaryTable}, or {@code null}/blank {@code tableName} for the
     * entity's own primary table. A {@code tableName} that does not match any {@code @SecondaryTable} the
     * entity declares is reported as {@link Status#NOT_MAPPED} rather than guessed.
     */
    static MappedTableResolution resolveSecondary(
            DatabaseAdvisorContext context, MappedEntityFacts entity, String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return resolve(context, entity);
        }
        List<MappedSecondaryTableFacts> secondaryTables = entity.secondaryTables().stream()
                .filter(candidate -> tableName.equals(candidate.name()))
                .toList();
        if (secondaryTables.size() > 1) {
            return new MappedTableResolution(
                    Status.AMBIGUOUS,
                    null,
                    null,
                    "More than one secondary-table declaration uses \"" + tableName + "\".");
        }
        if (secondaryTables.isEmpty()) {
            return new MappedTableResolution(
                    Status.NOT_MAPPED,
                    null,
                    null,
                    "References secondary table \"" + tableName + "\", which " + entity.entityName()
                            + " does not declare via @SecondaryTable.");
        }
        MappedSecondaryTableFacts secondaryTable = secondaryTables.get(0);
        return resolveNamed(
                context,
                secondaryTable.name(),
                secondaryTable.catalog(),
                secondaryTable.schema(),
                secondaryTable.qualifiedName());
    }

    private static MappedTableResolution resolveNamed(
            DatabaseAdvisorContext context, String tableName, String catalog, String schema, String qualifiedName) {
        if (tableName == null || tableName.isBlank()) {
            return new MappedTableResolution(Status.NOT_MAPPED, null, null, null);
        }
        SchemaSnapshot matchedSchema = null;
        TableModel matchedTable = null;
        int matches = 0;
        for (SchemaSnapshot candidateSchema : context.availableSchemas()) {
            List<TableModel> candidates = candidateSchema.declaredTablesNamed(catalog, schema, tableName);
            matches += candidates.size();
            if (!candidates.isEmpty() && matchedTable == null) {
                matchedSchema = candidateSchema;
                matchedTable = candidates.get(0);
            }
        }
        if (context.schemas().stream()
                .anyMatch(candidate -> !candidate.available()
                        || !candidate.relationInventoryComplete()
                        || !candidate.declarationCaseKnown(catalog, schema, tableName))) {
            return new MappedTableResolution(
                    Status.UNKNOWN,
                    null,
                    null,
                    "An unread/incomplete relation inventory or unknown identifier folding may hide another matching declared table.");
        }
        if (matches > 1) {
            return new MappedTableResolution(
                    Status.AMBIGUOUS,
                    null,
                    null,
                    matches + " physical tables named " + qualify(schema, qualifiedName) + " were found across "
                            + "the readable datasources, so this cannot be attributed to one of them.");
        }
        if (context.schemas().size() > 1) {
            return new MappedTableResolution(
                    Status.UNKNOWN,
                    null,
                    null,
                    "Several datasources are configured without persistence-unit attribution; a unique observed name "
                            + "does not establish the mapping's intended datasource.");
        }
        if (matches == 0) {
            return new MappedTableResolution(Status.NOT_FOUND, null, null, null);
        }
        return new MappedTableResolution(Status.RESOLVED, matchedSchema, matchedTable, null);
    }

    private static String qualify(String schema, String qualifiedName) {
        if (schema == null || schema.isBlank() || qualifiedName.contains(".")) {
            return qualifiedName;
        }
        return schema + "." + qualifiedName;
    }
}
