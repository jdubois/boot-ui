package io.github.jdubois.bootui.engine.advisor;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The Hibernate and Database advisors key every finding, so a rule cannot report one that is neither diffed between
 * scans nor suppressible by accident. This guard bans the identity-less ways of recording a finding in those two
 * packages; the rest of the engine keeps them.
 */
class KeyedFindingsGuardTests {

    private static final JavaClasses KEYED_ADVISORS = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(
                    "io.github.jdubois.bootui.engine.hibernate", "io.github.jdubois.bootui.engine.databaseadvisor");

    @Test
    void onlyTheKeyedAdvisorsTrackChangesBetweenScans() {
        JavaClasses engine = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.github.jdubois.bootui");
        noClasses()
                .that()
                .doNotHaveFullyQualifiedName("io.github.jdubois.bootui.engine.hibernate.HibernateScanner")
                .and()
                .doNotHaveFullyQualifiedName("io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorScanner")
                .should()
                .callMethod(AdvisorScanState.class, "trackingChanges")
                .because("the other advisors do not key their findings, so a comparison would be noise")
                .check(engine);
    }

    @Test
    void noHibernateOrDatabaseRuleRecordsAFindingWithoutAnIdentity() {
        noClasses()
                .should()
                .callMethod(AdvisorFindings.class, "add", String.class)
                .orShould()
                .callMethod(AdvisorFindings.class, "add", String.class, AdvisorViolationLocationDto.class)
                .orShould()
                .callMethod(AdvisorViolation.class, "of", String.class)
                .orShould()
                .callMethod(AdvisorViolation.class, "withoutLocations", List.class)
                .orShould()
                .callConstructor(AdvisorViolation.class, String.class, AdvisorViolationLocationDto.class)
                .orShould()
                .callMethod(
                        AdvisorViolationCollector.class,
                        "record",
                        String.class,
                        int.class,
                        List.class,
                        UnaryOperator.class)
                .because("every Hibernate and Database Advisor finding needs a canonical identity")
                .check(KEYED_ADVISORS);
    }
}
