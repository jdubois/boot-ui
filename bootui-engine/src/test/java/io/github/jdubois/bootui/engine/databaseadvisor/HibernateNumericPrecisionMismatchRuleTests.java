package io.github.jdubois.bootui.engine.databaseadvisor;

import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.schema;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.table;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedColumnFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import java.sql.Types;
import java.util.List;
import org.junit.jupiter.api.Test;

class HibernateNumericPrecisionMismatchRuleTests {

    @Test
    void narrowerPhysicalScaleOrIntegerDigitsAreReported() {
        DatabaseAdvisorRuleResultDto rounding = evaluate(mapped("BigDecimal", 19, 4, false), decimal(19, 2));
        assertThat(rounding.status()).isEqualTo(DatabaseAdvisorRuleSupport.VIOLATION);
        assertThat(rounding.severity()).isEqualTo(DatabaseAdvisorRuleSupport.MEDIUM);
        assertThat(rounding.sampleViolations().get(0))
                .contains("precision=19, scale=4")
                .contains("numeric(19,2)")
                .contains("rounds written values to 2 fractional digits");

        DatabaseAdvisorRuleResultDto overflow = evaluate(mapped("BigDecimal", 12, 2, false), decimal(10, 2));
        assertThat(overflow.status()).isEqualTo(DatabaseAdvisorRuleSupport.VIOLATION);
        assertThat(overflow.sampleViolations().get(0)).contains("holds only 8 integer digits instead of 10");

        DatabaseAdvisorRuleResultDto both = evaluate(mapped("BigDecimal", 12, 4, false), decimal(8, 2));
        assertThat(both.sampleViolations().get(0)).contains("rounds fractional digits and rejects large values");
    }

    @Test
    void equalOrWiderPhysicalColumnsPass() {
        assertThat(evaluate(mapped("BigDecimal", 10, 2, false), decimal(10, 2)).status())
                .isEqualTo(DatabaseAdvisorRuleSupport.PASS);
        assertThat(evaluate(mapped("BigDecimal", 10, 2, false), decimal(14, 4)).status())
                .isEqualTo(DatabaseAdvisorRuleSupport.PASS);
        assertThat(evaluate(mapped("BigInteger", 18, 0, false), decimal(18, 0)).status())
                .isEqualTo(DatabaseAdvisorRuleSupport.PASS);
    }

    @Test
    void zeroScaleAppliedWithAPositivePrecisionIsCompared() {
        DatabaseAdvisorRuleResultDto result = evaluate(mapped("BigDecimal", 10, 0, false), decimal(10, 2));
        assertThat(result.status()).isEqualTo(DatabaseAdvisorRuleSupport.VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("holds only 8 integer digits instead of 10");
    }

    @Test
    void undeclaredPrecisionAndNonDecimalAttributesAreNotApplicable() {
        MappedColumnFacts undeclared = new MappedColumnFacts("com.example.Invoice#total", "total", null, "BigDecimal");
        assertThat(evaluate(undeclared, decimal(5, 2)).status()).isEqualTo(DatabaseAdvisorRuleSupport.SKIPPED);
        assertThat(evaluate(mapped("Double", 12, 4, false), decimal(5, 2)).status())
                .isEqualTo(DatabaseAdvisorRuleSupport.SKIPPED);
    }

    @Test
    void unboundedOrUnusualPhysicalNumericsAreUnknownNotFindings() {
        for (ColumnModel physical :
                List.of(numeric(null, null), numeric(0, 0), numeric(131089, 0), numeric(38, -127), numeric(5, 7))) {
            DatabaseAdvisorContext context = context(mapped("BigDecimal", 19, 4, false), physical);
            assertThat(new HibernateNumericPrecisionMismatchRule()
                            .evaluate(context)
                            .status())
                    .as(physical.describeType())
                    .isEqualTo(DatabaseAdvisorRuleSupport.SKIPPED);
            assertThat(context.evaluationDiagnostics())
                    .as(physical.describeType())
                    .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("bounded physical precision"));
        }
    }

    @Test
    void convertedMappingsAndNonDecimalColumnsAreNotCompared() {
        DatabaseAdvisorContext converted = context(mapped("BigDecimal", 19, 4, true), decimal(10, 2));
        assertThat(new HibernateNumericPrecisionMismatchRule()
                        .evaluate(converted)
                        .status())
                .isEqualTo(DatabaseAdvisorRuleSupport.SKIPPED);
        assertThat(converted.evaluationDiagnostics()).isNotEmpty();

        ColumnModel bigint =
                new ColumnModel("total", "int8", Types.BIGINT, ColumnModel.Nullability.NULLABLE, 19, 0, false);
        DatabaseAdvisorContext integer = context(mapped("BigInteger", 30, 0, false), bigint);
        assertThat(new HibernateNumericPrecisionMismatchRule().evaluate(integer).status())
                .isEqualTo(DatabaseAdvisorRuleSupport.SKIPPED);
        assertThat(integer.evaluationDiagnostics()).isEmpty();
    }

    @Test
    void withoutHibernateMetadataTheRuleIsSkipped() {
        DatabaseAdvisorContext context = new DatabaseAdvisorContext(
                List.of(schema("ds", Dialect.POSTGRESQL, List.of(invoices(decimal(10, 2))))), false, List.of());
        assertThat(new HibernateNumericPrecisionMismatchRule().evaluate(context).status())
                .isEqualTo(DatabaseAdvisorRuleSupport.SKIPPED);
    }

    private static DatabaseAdvisorRuleResultDto evaluate(MappedColumnFacts column, ColumnModel physical) {
        return new HibernateNumericPrecisionMismatchRule().evaluate(context(column, physical));
    }

    private static DatabaseAdvisorContext context(MappedColumnFacts column, ColumnModel physical) {
        MappedEntityFacts entity = new MappedEntityFacts(
                "com.example.Invoice", "invoices", null, null, List.of(), List.of(column), List.of());
        return new DatabaseAdvisorContext(
                List.of(schema("ds", Dialect.POSTGRESQL, List.of(invoices(physical)))), true, List.of(entity));
    }

    private static TableModel invoices(ColumnModel physical) {
        return table("invoices", List.of(physical), List.of(), List.of(), List.of());
    }

    private static MappedColumnFacts mapped(String javaType, int precision, int scale, boolean ambiguous) {
        return new MappedColumnFacts(
                "com.example.Invoice#total",
                "total",
                null,
                javaType,
                null,
                false,
                ambiguous,
                false,
                null,
                precision,
                scale,
                false);
    }

    private static ColumnModel decimal(int precision, int scale) {
        return numeric(precision, scale);
    }

    private static ColumnModel numeric(Integer size, Integer digits) {
        return new ColumnModel(
                "total", "numeric", Types.NUMERIC, ColumnModel.Nullability.NULLABLE, size, digits, false);
    }
}
