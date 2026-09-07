package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared plumbing for the Hibernate ↔ physical schema cross-reference rules: they all skip (with a reason)
 * when either half is missing, and they all evaluate only entities whose physical table could be resolved
 * unambiguously — an entity with no explicit {@code @Table(name)}, or one whose name matches tables in several
 * readable datasources, is skipped rather than attributed to the wrong database.
 *
 * <p>An entity can split its mapped items across more than one physical table via {@code @SecondaryTable}.
 * {@link #checkEntity} therefore does not receive one pre-resolved table; it receives the entity's own
 * (primary-table) resolution and resolves each item's actual table through {@link #resolveItemTable}, which
 * falls back to the primary table when the item declares no {@code table=} override.</p>
 */
abstract class AbstractHibernateCrossReferenceRule extends AbstractDatabaseAdvisorRule {

    AbstractHibernateCrossReferenceRule(DatabaseAdvisorRuleDefinition definition) {
        super(definition);
    }

    /** Adds any findings for one entity, given its resolved primary-table facts. */
    abstract int checkEntity(
            DatabaseAdvisorContext context,
            MappedTableResolution primary,
            MappedEntityFacts entity,
            List<String> details);

    abstract boolean hasApplicableDeclarations(MappedEntityFacts entity);

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        if (!context.hibernateAvailable()) {
            return skipped("No EntityManagerFactory/Hibernate metamodel is available to cross-reference.");
        }
        if (context.availableSchemas().isEmpty()) {
            return skipped("No physical schema could be read to cross-reference against.");
        }
        List<MappedEntityFacts> targeted = context.hibernateEntities().stream()
                .filter(entity -> entity.explicitTableName() != null
                        && !entity.explicitTableName().isBlank())
                .filter(this::hasApplicableDeclarations)
                .toList();
        if (targeted.isEmpty()) {
            return skipped("No applicable explicit mapping declarations with supported table placement were found.");
        }
        if (context.schemas().size() != 1) {
            unknown(context, "The mapping facts do not associate persistence units with the discovered datasources.");
            return skipped("Multiple datasource inventories cannot be attributed to these mapping declarations.");
        }
        List<String> details = new ArrayList<>();
        int eligible = 0;
        for (MappedEntityFacts entity : targeted) {
            MappedTableResolution resolution = MappedTableResolution.resolve(context, entity);
            if (!resolution.resolved()) {
                if (resolution.status() != MappedTableResolution.Status.NOT_MAPPED
                        && resolution.status() != MappedTableResolution.Status.NOT_FOUND) {
                    unknown(
                            context,
                            entity.entityName() + ": declaration-name/source resolution is uncertain"
                                    + (resolution.detail() == null ? "." : ": " + resolution.detail()));
                }
                continue;
            }
            if (!supportsRelation(resolution.table())) {
                continue;
            }
            if (!sufficientMetadata(resolution.table())) {
                unknown(context, entity.entityName() + ": relation metadata is incomplete.");
                continue;
            }
            eligible += checkEntity(context, resolution, entity, details);
        }
        return assessed(context, eligible, details);
    }

    /**
     * Resolves which physical table one mapped item (a column, join column set, or unique constraint) belongs
     * to: {@code primary} when the item declares no explicit {@code table=} override, or the named
     * {@code @SecondaryTable} otherwise. Returns a resolution with {@link MappedTableResolution#resolved()}
     * {@code false} when the override does not match a declared secondary table, that secondary table cannot
     * be found unambiguously in the physical schema, or (according to {@link #sufficientMetadata}) its metadata
     * was not read completely — skip that item rather than guess or risk a false "absent" finding.
     */
    final MappedTableResolution resolveItemTable(
            DatabaseAdvisorContext context,
            MappedEntityFacts entity,
            MappedTableResolution primary,
            String itemTableName) {
        MappedTableResolution resolution = itemTableName == null || itemTableName.isBlank()
                ? primary
                : MappedTableResolution.resolveSecondary(context, entity, itemTableName);
        if (!resolution.resolved()) {
            unknown(context, entity.entityName() + ": item table placement or source identity is unresolved.");
            return resolution;
        }
        if (!supportsRelation(resolution.table())) {
            return new MappedTableResolution(MappedTableResolution.Status.NOT_MAPPED, null, null, null);
        }
        if (!sufficientMetadata(resolution.table())) {
            unknown(context, entity.entityName() + ": item relation metadata is incomplete.");
            return new MappedTableResolution(MappedTableResolution.Status.NOT_MAPPED, null, null, null);
        }
        return resolution;
    }

    /**
     * Whether the metadata needed by this rule was read. Unrelated metadata failures do not invalidate
     * positively observed facts.
     */
    boolean sufficientMetadata(TableModel table) {
        return table.metadata().complete();
    }

    boolean supportsRelation(TableModel table) {
        return true;
    }

    /** Called only when no single observed column matched a declaration. */
    final boolean unknownColumn(
            DatabaseAdvisorContext context,
            SchemaSnapshot schema,
            TableModel table,
            String declaredName,
            String description) {
        if (!schema.declarationCaseKnown(declaredName)
                || !table.metadata().columnsRead()
                || table.metadata().truncated()
                || table.columns().stream()
                                .filter(column -> schema.declaredMatches(column.name(), declaredName))
                                .count()
                        > 1) {
            unknown(
                    context,
                    description + ": column identifier policy or column inventory is incomplete or ambiguous.");
            return true;
        }
        return false;
    }
}
