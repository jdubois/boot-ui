package io.github.jdubois.bootui.engine.insights;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import io.github.jdubois.bootui.engine.journal.RequestJournalProfiles;
import io.github.jdubois.bootui.engine.journal.RequestProfileSelection;
import io.github.jdubois.bootui.engine.resources.JfrProfiler;
import io.github.jdubois.bootui.engine.web.ExecutionProfileAssembler;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * §5.5's first acceptance criterion, pinned by structure: opening Runtime Insights or a request profile starts no
 * capture, scan, database read, or network call. Runtime Insights and the runtime model ({@code insights},
 * {@code model}) and the request profile's assemblers read only what the journal and the panels already recorded: they
 * depend on no network, JDBC, file, or process API, and on none of the panels that scan or call out, and only the
 * <b>Profile resources</b> action starts a JFR session.
 */
@AnalyzeClasses(packages = "io.github.jdubois.bootui.engine", importOptions = DoNotIncludeTests.class)
class RuntimeInsightsReadPurityArchitectureTests {

    @ArchTest
    static final ArchRule readsReachNoNetworkDatabaseFileProcessOrScanner = ArchRuleDefinition.noClasses()
            .that()
            .resideInAnyPackage("io.github.jdubois.bootui.engine.insights..", "io.github.jdubois.bootui.engine.model..")
            .or()
            .belongToAnyOf(RequestJournalProfiles.class, RequestProfileSelection.class, ExecutionProfileAssembler.class)
            .should()
            .dependOnClassesThat(resideInAnyPackage(
                            "java.net.http..",
                            "java.sql..",
                            "javax.sql..",
                            "java.nio.file..",
                            "java.nio.channels..",
                            "jdk.jfr..",
                            "io.github.jdubois.bootui.engine.advisor..",
                            "io.github.jdubois.bootui.engine.architecture..",
                            "io.github.jdubois.bootui.engine.databaseadvisor..",
                            "io.github.jdubois.bootui.engine.pentesting..",
                            "io.github.jdubois.bootui.engine.vulnerabilities..",
                            "io.github.jdubois.bootui.engine.graalvm..",
                            "io.github.jdubois.bootui.engine.crac..",
                            "io.github.jdubois.bootui.engine.github..",
                            "io.github.jdubois.bootui.engine.heapdump..",
                            "io.github.jdubois.bootui.engine.mysql..",
                            "io.github.jdubois.bootui.engine.postgres..")
                    .or(assignableTo(java.net.Socket.class))
                    .or(assignableTo(java.net.URLConnection.class))
                    .or(assignableTo(ProcessBuilder.class)))
            .because("opening Runtime Insights or a profile starts no capture, scan, database read, or network call"
                    + " (docs/PLAN-v2.md §5.5)");

    /** Only {@link ResourceProfileService#start()}, the <b>Profile resources</b> action, starts a JFR session. */
    @ArchTest
    static void onlyTheProfileResourcesActionStartsAJfrSession(JavaClasses classes) {
        Set<String> callers =
                classes.get(JfrProfiler.class).getMethod("start", Duration.class).getCallsOfSelf().stream()
                        .map(call -> call.getOrigin().getFullName())
                        .collect(Collectors.toSet());

        assertThat(callers)
                .as("a JFR session starts only when the user asks for one (docs/PLAN-v2.md §5.5, §5.11)")
                .containsExactly(ResourceProfileService.class.getName() + ".start()");
    }
}
