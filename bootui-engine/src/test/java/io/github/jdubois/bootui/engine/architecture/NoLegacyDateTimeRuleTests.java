package io.github.jdubois.bootui.engine.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.ArchitectureReport;
import io.github.jdubois.bootui.core.dto.ArchitectureRuleResultDto;
import io.github.jdubois.bootui.core.dto.ArchitectureSeverityCountDto;
import java.nio.file.Path;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NoLegacyDateTimeRuleTests {
    @TempDir
    Path tempDir;

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void standardBridgeCallsDoNotCountAsLegacyUse(ArchitecturePlatform platform) {
        assertClean(platform, BridgeCalls.class);
    }

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void standardBridgeReferencesDoNotCountAsLegacyUse(ArchitecturePlatform platform) {
        assertClean(platform, BridgeReferences.class);
    }

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void inheritedJdkBridgesAreRecognized(ArchitecturePlatform platform) {
        assertClean(platform, InheritedBridges.class);
    }

    @Test
    void exactBridgeSignaturesDoNotRequireClasspathResolution() {
        ArchConfiguration.withThreadLocalScope(configuration -> {
            configuration.setResolveMissingDependenciesFromClassPath(false);
            assertClean(ArchitecturePlatform.SPRING, BridgeCalls.class);
            assertClean(ArchitecturePlatform.SPRING, BridgeReferences.class);
        });
    }

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void allPreviouslyCheckedFieldTypesRemainFindings(ArchitecturePlatform platform) {
        ArchitectureRuleResultDto result = evaluate(platform, LegacyFields.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(5);
        assertThat(result.sampleViolations()).allMatch(sample -> sample.contains("has type"));
    }

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void legacyConstructionAndCalendarFactoryRemainFindings(ArchitecturePlatform platform) {
        ArchitectureRuleResultDto result = evaluate(platform, LegacyConstruction.class);

        assertThat(result.violationCount()).isEqualTo(5);
        assertThat(result.sampleViolations()).anyMatch(sample -> sample.contains("java.util.Calendar.getInstance()"));
        assertThat(result.sampleViolations().stream().filter(sample -> sample.contains("calls constructor")))
                .hasSize(4);
    }

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void legacyDeclarationsRemainFindings(ArchitecturePlatform platform) {
        ArchitectureRuleResultDto result = evaluate(platform, LegacyDeclarations.class);

        assertThat(result.violationCount()).isEqualTo(3);
        assertThat(result.sampleViolations()).anyMatch(sample -> sample.contains("has return type"));
        assertThat(result.sampleViolations().stream().filter(sample -> sample.contains("has parameter")))
                .hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void genericAndArrayDependenciesRemainFindings(ArchitecturePlatform platform) {
        ArchitectureRuleResultDto result = evaluate(platform, LegacyContainers.class);

        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations()).anyMatch(sample -> sample.contains("java.util.Date"));
        assertThat(result.sampleViolations()).anyMatch(sample -> sample.contains("java.util.Calendar"));
    }

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void legacyOperationsAndUnsupportedConversionsRemainFindings(ArchitecturePlatform platform) {
        ArchitectureRuleResultDto result = evaluate(platform, LegacyOperations.class);

        assertThat(result.violationCount()).isEqualTo(9);
        assertThat(result.sampleViolations())
                .anyMatch(sample -> sample.contains("java.sql.Date.toInstant()"))
                .anyMatch(sample -> sample.contains("java.sql.Time.toInstant()"))
                .anyMatch(sample -> sample.contains("java.sql.Timestamp.valueOf("))
                .anyMatch(sample -> sample.contains("java.sql.Date.valueOf("))
                .anyMatch(sample -> sample.contains("java.sql.Time.valueOf("));
    }

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void nonBridgeMethodReferencesRemainFindings(ArchitecturePlatform platform) {
        ArchitectureRuleResultDto result = evaluate(platform, LegacyReferences.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.violationCount()).isEqualTo(3);
        assertThat(result.sampleViolations())
                .allMatch(sample -> sample.contains("references method"))
                .anyMatch(sample -> sample.contains("java.util.Date.getTime()"))
                .anyMatch(sample -> sample.contains("java.sql.Date.toInstant()"))
                .anyMatch(sample -> sample.contains("java.sql.Time.toInstant()"));
    }

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void aBridgeDoesNotExemptOtherDependenciesInItsClass(ArchitecturePlatform platform) {
        ArchitectureRuleResultDto result = evaluate(platform, MixedUse.class);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.VIOLATION);
        assertThat(result.id()).isEqualTo("ARCH-CODE-008");
        assertThat(result.severity()).isEqualTo("INFO");
        assertThat(result.violationCount()).isEqualTo(3);
        assertThat(result.sampleViolations())
                .anyMatch(sample -> sample.contains(".stored> has type"))
                .anyMatch(sample -> sample.contains("has parameter of type"))
                .anyMatch(sample -> sample.contains("java.util.Date.setTime("))
                .noneMatch(sample -> sample.contains("toInstant"));
        ArchitectureContext context = context(platform, MixedUse.class);
        assertThat(new NoLegacyDateTimeRule()
                        .rule(context)
                        .evaluate(context.classes())
                        .getFailureReport()
                        .getDetails())
                .allMatch(sample -> sample.contains("NoLegacyDateTimeRuleTests.java:"))
                .anyMatch(sample -> sample.contains("java.util.Date.setTime(long)"));
    }

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void scannerRetainsOnlyRealFindingsAndTheirEvidence(ArchitecturePlatform platform) {
        ArchitectureScanner scanner = new ArchitectureScanner(
                () -> List.of(getClass().getPackageName()),
                packages ->
                        CompiledOutputFixtures.importFrom(tempDir, "target/classes", BridgeCalls.class, MixedUse.class),
                platform,
                Clock.systemUTC(),
                List.of(new NoLegacyDateTimeRule()));

        ArchitectureReport report = scanner.scan();
        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.classesAnalyzed()).isEqualTo(2);
        assertThat(report.rulesEvaluated()).isEqualTo(1);
        assertThat(report.violationsFound()).isEqualTo(1);
        assertThat(report.results()).singleElement().satisfies(result -> {
            assertThat(result.id()).isEqualTo("ARCH-CODE-008");
            assertThat(result.violationCount()).isEqualTo(3);
            assertThat(result.sampleViolations()).allMatch(sample -> sample.contains("MixedUse"));
        });
        assertThat(report.severityCounts()).contains(new ArchitectureSeverityCountDto("INFO", 3));
        assertThat(report.analysisErrors()).isEmpty();
        assertThat(report.evidence().usable()).isTrue();
        assertThat(report.evidence().coverageComplete()).isTrue();
        assertThat(report.evidence().limitations()).isEmpty();
        ArchitectureReport dismissed = scanner.applyDismissals(report, Set.of("ARCH-CODE-008"));
        assertThat(dismissed.violationsFound()).isZero();
        assertThat(dismissed.evidence()).isEqualTo(report.evidence());
    }

    @ParameterizedTest
    @EnumSource(ArchitecturePlatform.class)
    void uncheckedTypesDoNotGainNewFindings(ArchitecturePlatform platform) {
        assertClean(platform, PreviouslyUncheckedTypes.class);
    }

    @Test
    void emptyScopeDoesNotEstablishUsableEvidence() {
        ArchitectureContext context = context(ArchitecturePlatform.SPRING);

        assertThat(new NoLegacyDateTimeRule().evaluate(context).status()).isEqualTo(ArchitectureRuleSupport.PASS);
        assertThat(context.evidence().usable()).isFalse();
        assertThat(context.evidence().evaluated()).isTrue();
    }

    private static void assertClean(ArchitecturePlatform platform, Class<?> fixture) {
        ArchitectureContext context = context(platform, fixture);
        ArchitectureRuleResultDto result = new NoLegacyDateTimeRule().evaluate(context);

        assertThat(result.status()).isEqualTo(ArchitectureRuleSupport.PASS);
        assertThat(result.violationCount()).isZero();
        assertThat(result.sampleViolations()).isEmpty();
        assertThat(context.evidence().usable()).isTrue();
        assertThat(context.evidence().evaluated()).isTrue();
        assertThat(context.evidence().requiredUnknown()).isFalse();
    }

    private static ArchitectureRuleResultDto evaluate(ArchitecturePlatform platform, Class<?> fixture) {
        return new NoLegacyDateTimeRule().evaluate(context(platform, fixture));
    }

    private static ArchitectureContext context(ArchitecturePlatform platform, Class<?>... fixtures) {
        return new ArchitectureContext(
                new ClassFileImporter().importClasses(fixtures),
                List.of(NoLegacyDateTimeRuleTests.class.getPackageName()),
                platform);
    }

    interface ExternalApi {
        Date date();

        Calendar calendar();

        java.sql.Date sqlDate();

        Time time();

        Timestamp timestamp();

        void accept(Object value);
    }

    static class BridgeCalls {
        void convert(ExternalApi api, Instant instant, LocalDate date, LocalTime time, LocalDateTime dateTime) {
            api.accept(api.date().toInstant());
            api.accept(api.calendar().toInstant());
            api.accept(api.sqlDate().toLocalDate());
            api.accept(api.time().toLocalTime());
            api.accept(api.timestamp().toInstant());
            api.accept(api.timestamp().toLocalDateTime());
            api.accept(Date.from(instant));
            api.accept(java.sql.Date.valueOf(date));
            api.accept(Time.valueOf(time));
            api.accept(Timestamp.from(instant));
            api.accept(Timestamp.valueOf(dateTime));
        }
    }

    static class BridgeReferences {
        void convert(ExternalApi api) {
            api.accept((Supplier<Instant>) api.date()::toInstant);
            api.accept((Supplier<Instant>) api.calendar()::toInstant);
            api.accept((Supplier<LocalDate>) api.sqlDate()::toLocalDate);
            api.accept((Supplier<LocalTime>) api.time()::toLocalTime);
            api.accept((Supplier<Instant>) api.timestamp()::toInstant);
            api.accept((Supplier<LocalDateTime>) api.timestamp()::toLocalDateTime);
            api.accept((Function<Instant, ?>) Date::from);
            api.accept((Function<LocalDate, ?>) java.sql.Date::valueOf);
            api.accept((Function<LocalTime, ?>) Time::valueOf);
            api.accept((Function<Instant, ?>) Timestamp::from);
            api.accept((Function<LocalDateTime, ?>) Timestamp::valueOf);
        }
    }

    static class InheritedBridges {
        void convert(ExternalApi api, Instant instant) {
            api.accept(java.sql.Date.from(instant));
            api.accept(Time.from(instant));
            api.accept((Function<Instant, ?>) java.sql.Date::from);
            api.accept((Function<Instant, ?>) Time::from);
        }
    }

    static class LegacyFields {
        Date date;
        Calendar calendar;
        java.sql.Date sqlDate;
        Time time;
        Timestamp timestamp;
    }

    static class LegacyConstruction {
        void create(ExternalApi api) {
            api.accept(new Date());
            api.accept(Calendar.getInstance());
            api.accept(new java.sql.Date(0));
            api.accept(new Time(0));
            api.accept(new Timestamp(0));
        }
    }

    static class LegacyDeclarations {
        LegacyDeclarations(Date date) {}

        Timestamp convert(Calendar calendar) {
            return null;
        }
    }

    static class LegacyContainers {
        List<Date> dates;
        Calendar[] calendars;
    }

    static class LegacyOperations {
        void use(ExternalApi api) {
            api.accept(api.date().getTime());
            api.date().setTime(0);
            api.accept(api.calendar().get(Calendar.YEAR));
            api.calendar().set(Calendar.YEAR, 2026);
            api.accept(Timestamp.valueOf("2026-01-01 00:00:00"));
            api.accept(java.sql.Date.valueOf("2026-01-01"));
            api.accept(Time.valueOf("00:00:00"));
            api.accept(api.sqlDate().toInstant());
            api.accept(api.time().toInstant());
        }
    }

    static class LegacyReferences {
        void use(ExternalApi api) {
            api.accept((Supplier<Long>) api.date()::getTime);
            api.accept((Supplier<Instant>) api.sqlDate()::toInstant);
            api.accept((Supplier<Instant>) api.time()::toInstant);
        }
    }

    static class MixedUse {
        Date stored;

        Instant convert(ExternalApi api, Date date) {
            date.setTime(0);
            return api.date().toInstant();
        }
    }

    static class PreviouslyUncheckedTypes {
        TimeZone zone;
        GregorianCalendar calendar;

        void convert(ExternalApi api, ZonedDateTime dateTime) {
            api.accept(zone.toZoneId());
            api.accept(calendar.toInstant());
            api.accept(calendar.toZonedDateTime());
            api.accept(GregorianCalendar.from(dateTime));
        }
    }
}
