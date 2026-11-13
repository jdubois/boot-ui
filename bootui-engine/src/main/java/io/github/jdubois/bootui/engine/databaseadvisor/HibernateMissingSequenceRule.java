package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedSequenceGeneratorFacts;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
            if (generator.catalog() != null && !generator.catalog().isBlank()) {
                if (table.catalog() == null) {
                    unknown(
                            context,
                            generator.attributeDescription()
                                    + ": @SequenceGenerator specifies a catalog, but the datasource reports no catalog.");
                    continue;
                }
                if (!schema.declaredMatches(table.catalog(), generator.catalog())) {
                    continue;
                }
            }
            QualifiedSequence parsed = parseSequence(generator);
            if (parsed == null) {
                unknown(
                        context,
                        generator.attributeDescription()
                                + ": @SequenceGenerator sequenceName/schema qualification is ambiguous or malformed.");
                continue;
            }
            if (!schema.declarationCaseKnown(parsed.schema(), parsed.name())) {
                unknown(context, generator.attributeDescription() + ": sequence identifier folding is unknown.");
                continue;
            }
            if (schema.dialect() == Dialect.POSTGRESQL) {
                eligible += checkPostgresSequence(context, schema, table, entity, generator, parsed, details);
            } else {
                eligible += checkOracleSequence(context, schema, table, entity, generator, parsed, details);
            }
        }
        return eligible;
    }

    private int checkPostgresSequence(
            DatabaseAdvisorContext context,
            SchemaSnapshot schema,
            TableModel table,
            MappedEntityFacts entity,
            MappedSequenceGeneratorFacts generator,
            QualifiedSequence parsed,
            DatabaseFindings details) {
        var sequences = schema.vendorFindings().findings(VendorFindingKinds.POSTGRES_SEQUENCES);
        if (parsed.schema() != null) {
            boolean found = sequences.stream()
                    .anyMatch(seq -> schema.declaredMatches(seq.schema(), parsed.schema())
                            && schema.declaredMatches(seq.sequence(), parsed.name()));
            if (!found) {
                String resolvedSchema = foldIdentifier(schema, parsed.schema());
                String resolvedName = foldIdentifier(schema, parsed.name());
                String qualified = resolvedSchema + "." + resolvedName;
                details.add(
                        schema.dataSourceName() + ": " + generator.attributeDescription()
                                + " declares @SequenceGenerator(sequenceName = \"" + generator.sequenceName()
                                + "\"), which was not found in the observed PostgreSQL sequence inventory ("
                                + qualified + ").",
                        DatabaseIdentities.of(
                                schema.dataSourceName(),
                                "entity=" + entity.entityName(),
                                "attribute=" + generator.attributeDescription(),
                                "sequence=" + qualified));
            }
            return 1;
        }
        String candidateSchema =
                (entity.explicitSchema() == null || entity.explicitSchema().isBlank()) ? table.schema() : null;
        boolean foundInCandidate = candidateSchema != null
                && sequences.stream()
                        .anyMatch(seq -> Objects.equals(candidateSchema, seq.schema())
                                && schema.declaredMatches(seq.sequence(), parsed.name()));
        if (foundInCandidate) {
            return 1;
        }
        boolean foundInAnySchema =
                sequences.stream().anyMatch(seq -> schema.declaredMatches(seq.sequence(), parsed.name()));
        if (foundInAnySchema) {
            unknown(
                    context,
                    generator.attributeDescription()
                            + ": unqualified @SequenceGenerator matches a sequence in another schema; "
                            + "PostgreSQL search_path resolution is not established.");
            return 0;
        }
        String resolvedName = foldIdentifier(schema, parsed.name());
        String qualified = candidateSchema == null || candidateSchema.isBlank()
                ? resolvedName
                : candidateSchema + "." + resolvedName;
        details.add(
                schema.dataSourceName() + ": " + generator.attributeDescription()
                        + " declares @SequenceGenerator(sequenceName = \"" + generator.sequenceName()
                        + "\"), which was not found in the observed PostgreSQL sequence inventory ("
                        + qualified + ").",
                DatabaseIdentities.of(
                        schema.dataSourceName(),
                        "entity=" + entity.entityName(),
                        "attribute=" + generator.attributeDescription(),
                        "sequence=" + qualified));
        return 1;
    }

    private int checkOracleSequence(
            DatabaseAdvisorContext context,
            SchemaSnapshot schema,
            TableModel table,
            MappedEntityFacts entity,
            MappedSequenceGeneratorFacts generator,
            QualifiedSequence parsed,
            DatabaseFindings details) {
        if (parsed.schema() != null
                && (table.schema() == null || !schema.declaredMatches(table.schema(), parsed.schema()))) {
            unknown(
                    context,
                    generator.attributeDescription()
                            + ": @SequenceGenerator targets schema \"" + parsed.schema()
                            + "\", outside the CURRENT_SCHEMA-scoped Oracle sequence inventory.");
            return 0;
        }
        boolean found = schema.vendorFindings().findings(VendorFindingKinds.ORACLE_SEQUENCES).stream()
                .anyMatch(seq -> matchesSchema(table.schema(), seq.schema())
                        && schema.declaredMatches(seq.sequence(), parsed.name()));
        if (!found) {
            String resolvedName = foldIdentifier(schema, parsed.name());
            String qualified = table.schema() == null || table.schema().isBlank()
                    ? resolvedName
                    : table.schema() + "." + resolvedName;
            details.add(
                    schema.dataSourceName() + ": " + generator.attributeDescription()
                            + " declares @SequenceGenerator(sequenceName = \"" + generator.sequenceName()
                            + "\"), which was not found in the observed Oracle sequence inventory ("
                            + qualified + ").",
                    DatabaseIdentities.of(
                            schema.dataSourceName(),
                            "entity=" + entity.entityName(),
                            "attribute=" + generator.attributeDescription(),
                            "sequence=" + qualified));
        }
        return 1;
    }

    private static String foldIdentifier(SchemaSnapshot schema, String declared) {
        if (declared == null) {
            return "";
        }
        if (declared.length() >= 2
                && ((declared.startsWith("\"") && declared.endsWith("\""))
                        || (declared.startsWith("`") && declared.endsWith("`")))) {
            String quote = declared.substring(0, 1);
            return declared.substring(1, declared.length() - 1).replace(quote + quote, quote);
        }
        if ("UPPER".equals(schema.identifierCase())) {
            return declared.toUpperCase(Locale.ROOT);
        }
        if ("LOWER".equals(schema.identifierCase())) {
            return declared.toLowerCase(Locale.ROOT);
        }
        return declared;
    }

    private static boolean matchesSchema(String tableSchema, String sequenceSchema) {
        return tableSchema == null || tableSchema.isBlank() || Objects.equals(tableSchema, sequenceSchema);
    }

    record QualifiedSequence(String schema, String name) {}

    static QualifiedSequence parseSequence(MappedSequenceGeneratorFacts generator) {
        String rawName = generator.sequenceName();
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        String explicitSchema = generator.schema() == null || generator.schema().isBlank()
                ? null
                : generator.schema().trim();
        List<String> parts = splitQualifiedIdentifier(rawName.trim());
        if (parts.isEmpty() || parts.size() > 2) {
            return null;
        }
        if (parts.size() == 2) {
            if (explicitSchema != null) {
                return null;
            }
            return new QualifiedSequence(parts.get(0), parts.get(1));
        }
        return new QualifiedSequence(explicitSchema, parts.get(0));
    }

    private static List<String> splitQualifiedIdentifier(String text) {
        List<String> parts = new ArrayList<>(2);
        StringBuilder current = new StringBuilder();
        char activeQuote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (activeQuote != 0) {
                current.append(c);
                if (c == activeQuote) {
                    if (i + 1 < text.length() && text.charAt(i + 1) == activeQuote) {
                        current.append(activeQuote);
                        i++;
                    } else {
                        activeQuote = 0;
                    }
                }
            } else if (c == '"' || c == '`') {
                if (!current.toString().trim().isEmpty()) {
                    return List.of();
                }
                activeQuote = c;
                current.append(c);
            } else if (c == '.') {
                String token = current.toString().trim();
                if (token.isEmpty()) {
                    return List.of();
                }
                parts.add(token);
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (activeQuote != 0) {
            return List.of();
        }
        String tail = current.toString().trim();
        if (tail.isEmpty()) {
            return List.of();
        }
        parts.add(tail);
        return parts;
    }
}
