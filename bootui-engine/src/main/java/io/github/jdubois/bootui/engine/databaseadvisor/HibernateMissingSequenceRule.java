package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedSequenceGeneratorFacts;
import java.util.Objects;

/**
 * Cross-references an explicit {@code @SequenceGenerator(sequenceName = ...)} declaration targeting the
 * connected schema against a complete observed PostgreSQL or Oracle sequence inventory.
 */
final class HibernateMissingSequenceRule extends AbstractHibernateCrossReferenceRule {

    HibernateMissingSequenceRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-014",
                "Declared JPA sequence generator name not found in the observed schema",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.HIGH,
                "Cross-references explicit @SequenceGenerator(sequenceName = ...) declarations targeting the "
                        + "connected schema against complete observed PostgreSQL and Oracle sequence inventories.",
                "Verify the effective physical naming strategy and schema, then create the missing sequence in a "
                        + "migration or align @SequenceGenerator(sequenceName = ...) with the existing sequence.",
                "https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html"));
    }

    @Override
    boolean hasApplicableDeclarations(MappedEntityFacts entity) {
        return !entity.sequenceGenerators().isEmpty();
    }

    @Override
    boolean sufficientMetadata(TableModel table) {
        return true;
    }

    @Override
    int checkEntity(
            DatabaseAdvisorContext context,
            MappedTableResolution primary,
            MappedEntityFacts entity,
            DatabaseFindings details) {
        SchemaSnapshot schema = primary.schema();
        TableModel table = primary.table();
        if (schema.dialect() != Dialect.POSTGRESQL && schema.dialect() != Dialect.ORACLE) {
            return 0;
        }
        VendorFindingKind<?> kind = schema.dialect() == Dialect.POSTGRESQL
                ? VendorFindingKinds.POSTGRES_SEQUENCES
                : VendorFindingKinds.ORACLE_SEQUENCES;
        if (!VendorRuleSupport.complete(schema, kind)) {
            VendorRuleSupport.coverage(context, definition().id(), schema, kind);
            return 0;
        }
        int eligible = 0;
        for (MappedSequenceGeneratorFacts generator : entity.sequenceGenerators()) {
            if (generator.catalog() != null
                    && !generator.catalog().isBlank()
                    && (table.catalog() == null || !schema.declaredMatches(table.catalog(), generator.catalog()))) {
                continue;
            }
            QualifiedSequence parsed = parseSequence(generator);
            if (parsed == null) {
                continue;
            }
            if (parsed.schema() != null
                    && (table.schema() == null || !schema.declaredMatches(table.schema(), parsed.schema()))) {
                // Targets a schema outside the connected/resolved table's schema.
                continue;
            }
            if (!schema.declarationCaseKnown(parsed.schema(), parsed.name())) {
                unknown(context, generator.attributeDescription() + ": sequence identifier folding is unknown.");
                continue;
            }
            eligible++;
            boolean found = schema.dialect() == Dialect.POSTGRESQL
                    ? schema.vendorFindings().findings(VendorFindingKinds.POSTGRES_SEQUENCES).stream()
                            .anyMatch(seq -> matchesSchema(table.schema(), seq.schema())
                                    && schema.declaredMatches(seq.sequence(), parsed.name()))
                    : schema.vendorFindings().findings(VendorFindingKinds.ORACLE_SEQUENCES).stream()
                            .anyMatch(seq -> matchesSchema(table.schema(), seq.schema())
                                    && schema.declaredMatches(seq.sequence(), parsed.name()));
            if (!found) {
                String qualified = table.schema() == null || table.schema().isBlank()
                        ? parsed.name()
                        : table.schema() + "." + parsed.name();
                details.add(
                        schema.dataSourceName() + ": " + generator.attributeDescription()
                                + " declares @SequenceGenerator(sequenceName = \"" + generator.sequenceName()
                                + "\"), which was not found in the observed "
                                + schema.dialect().label()
                                + " sequence inventory (" + qualified + ").",
                        DatabaseIdentities.of(
                                schema.dataSourceName(),
                                "entity=" + entity.entityName(),
                                "attribute=" + generator.attributeDescription(),
                                "sequence=" + qualified));
            }
        }
        return eligible;
    }

    private static boolean matchesSchema(String tableSchema, String sequenceSchema) {
        return tableSchema == null || tableSchema.isBlank() || Objects.equals(tableSchema, sequenceSchema);
    }

    private record QualifiedSequence(String schema, String name) {}

    private static QualifiedSequence parseSequence(MappedSequenceGeneratorFacts generator) {
        String rawName = generator.sequenceName();
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        String explicitSchema = generator.schema() == null || generator.schema().isBlank() ? null : generator.schema();
        boolean quoted = rawName.length() >= 2
                && ((rawName.startsWith("\"") && rawName.endsWith("\""))
                        || (rawName.startsWith("`") && rawName.endsWith("`")));
        if (!quoted && rawName.indexOf('.') >= 0) {
            int firstDot = rawName.indexOf('.');
            int lastDot = rawName.lastIndexOf('.');
            if (firstDot != lastDot || explicitSchema != null) {
                return null;
            }
            String prefix = rawName.substring(0, firstDot).trim();
            String suffix = rawName.substring(firstDot + 1).trim();
            if (prefix.isEmpty() || suffix.isEmpty()) {
                return null;
            }
            return new QualifiedSequence(prefix, suffix);
        }
        return new QualifiedSequence(explicitSchema, rawName);
    }
}
