package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.PanelDto;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Pins how the Quarkus panel manifest distinguishes the three availability outcomes: panels that are lit up,
 * panels that are deliberately and permanently not applicable on Quarkus (GraalVM, CRaC, Conditions, Startup
 * Timeline, HTTP Sessions, Spring Data, Spring Security), and panels that are
 * merely not ported yet. The manifest is a pure function of the shared {@link BootUiPanels} registry, so this
 * needs no Quarkus runtime.
 */
class QuarkusPanelAvailabilityTest {

    private Map<String, PanelDto> manifestById() {
        return manifestById(StubConfig.empty());
    }

    private Map<String, PanelDto> manifestById(StubConfig config) {
        return new QuarkusPanelAvailability(config)
                .manifest().panels().stream().collect(Collectors.toMap(PanelDto::id, Function.identity()));
    }

    @Test
    void graalVmCracConditionsAndStartupAreDeliberatelyNotApplicableOnQuarkus() {
        Map<String, PanelDto> panels = manifestById();
        for (String id : new String[] {
            BootUiPanels.GRAALVM,
            BootUiPanels.CRAC,
            BootUiPanels.CONDITIONS,
            BootUiPanels.STARTUP,
            BootUiPanels.HTTP_SESSIONS,
            BootUiPanels.DATA,
            BootUiPanels.SPRING_SECURITY
        }) {
            PanelDto panel = panels.get(id);
            assertThat(panel).as("panel %s is present in the manifest", id).isNotNull();
            assertThat(panel.available())
                    .as("panel %s is unavailable on Quarkus", id)
                    .isFalse();
            assertThat(panel.unavailableReason())
                    .as("panel %s carries a deliberate, panel-specific reason (not the generic one)", id)
                    .isNotNull()
                    .doesNotContain("Not yet available")
                    .containsIgnoringCase("Not applicable on Quarkus");
        }
    }

    @Test
    void springOnlyFinishersRedirectToTheirQuarkusEquivalents() {
        // Spring Data and Spring Security are NOT_APPLICABLE on Quarkus, but their reasons must point users at
        // the Quarkus-native panels that do cover the concern (Hibernate advisor / Security advisor).
        Map<String, PanelDto> panels = manifestById();
        assertThat(panels.get(BootUiPanels.DATA).unavailableReason())
                .as("Spring Data reason redirects to the Hibernate advisor")
                .containsIgnoringCase("Hibernate");
        assertThat(panels.get(BootUiPanels.SPRING_SECURITY).unavailableReason())
                .as("Spring Security reason redirects to the Security advisor")
                .containsIgnoringCase("Security advisor");
    }

    @Test
    void securityAdvisorIsLitUpOnQuarkus() {
        // The Security advisor now runs a Quarkus-native ruleset, so the panel is available (not the
        // not-applicable shim) — always-available like Architecture/Pentesting, no capability gate.
        PanelDto security = manifestById().get(BootUiPanels.SECURITY);
        assertThat(security.available())
                .as("Security advisor is lit up on Quarkus")
                .isTrue();
    }

    @Test
    void springAdvisorIsLitUpOnQuarkus() {
        // The Spring advisor panel now runs a Quarkus-native idiom ruleset, so the panel is available
        // (not the not-yet-ported shim) — always-available like Security/Architecture, no capability gate.
        PanelDto spring = manifestById().get(BootUiPanels.SPRING);
        assertThat(spring.available())
                .as("Spring advisor panel is lit up on Quarkus")
                .isTrue();
    }

    @Test
    void notYetPortedPanelsKeepTheGenericReason() {
        // Mappings has no Quarkus backing yet; it must keep the generic "not yet" reason so it stays clearly
        // distinct from the deliberately-not-applicable GraalVM/CRaC panels above.
        PanelDto mappings = manifestById().get(BootUiPanels.MAPPINGS);
        assertThat(mappings.available()).isFalse();
        assertThat(mappings.unavailableReason()).isEqualTo("Not yet available on Quarkus.");
    }

    @Test
    void beansIsLitUpOnQuarkus() {
        // Beans runs over the live Arc/CDI container; quarkus-arc is a core extension dependency, so the panel
        // is always-available like Architecture/Metrics, with no capability gate.
        PanelDto beans = manifestById().get(BootUiPanels.BEANS);
        assertThat(beans.available()).as("Beans is lit up on Quarkus").isTrue();
        assertThat(beans.unavailableReason()).isNull();
    }

    @Test
    void availablePanelsCarryNoUnavailableReason() {
        PanelDto architecture = manifestById().get(BootUiPanels.ARCHITECTURE);
        assertThat(architecture.available())
                .as("Architecture is lit up on Quarkus")
                .isTrue();
        assertThat(architecture.unavailableReason()).isNull();
    }

    @Test
    void pentestingIsLitUpOnQuarkus() {
        PanelDto pentesting = manifestById().get(BootUiPanels.PENTESTING);
        assertThat(pentesting.available()).as("Pentesting is lit up on Quarkus").isTrue();
        assertThat(pentesting.unavailableReason()).isNull();
    }

    @Test
    void vulnerabilitiesIsStaticallyAvailableOnQuarkus() {
        PanelDto vulnerabilities = manifestById().get(BootUiPanels.VULNERABILITIES);
        assertThat(vulnerabilities.available())
                .as("Vulnerabilities is lit up on Quarkus (local inventory + user-initiated OSV scan)")
                .isTrue();
        assertThat(vulnerabilities.unavailableReason()).isNull();
    }

    @Test
    void hibernateIsUnavailableWithACapabilityHintWhenHibernateOrmIsAbsent() {
        // Default: bootui.internal.hibernate-present is unset, so the deployment processor never saw the
        // HIBERNATE_ORM capability. The panel must surface an honest capability hint, NOT the generic reason.
        PanelDto hibernate = manifestById().get(BootUiPanels.HIBERNATE);
        assertThat(hibernate.available()).isFalse();
        assertThat(hibernate.unavailableReason())
                .isNotNull()
                .doesNotContain("Not yet available")
                .containsIgnoringCase("quarkus-hibernate-orm");
    }

    @Test
    void hibernateIsAvailableWhenTheBuildTimeFlagIsSet() {
        StubConfig withHibernate = new StubConfig(Map.of(QuarkusPanelAvailability.HIBERNATE_PRESENT_KEY, "true"));
        PanelDto hibernate = manifestById(withHibernate).get(BootUiPanels.HIBERNATE);
        assertThat(hibernate.available())
                .as("Hibernate is lit up when quarkus-hibernate-orm is present")
                .isTrue();
        assertThat(hibernate.unavailableReason()).isNull();
    }

    @Test
    void flywayIsUnavailableWithACapabilityHintWhenFlywayIsAbsent() {
        // Default: bootui.internal.flyway-present is unset, so the deployment processor never saw the FLYWAY
        // capability. The panel must surface an honest capability hint, NOT the generic reason.
        PanelDto flyway = manifestById().get(BootUiPanels.FLYWAY);
        assertThat(flyway.available()).isFalse();
        assertThat(flyway.unavailableReason())
                .isNotNull()
                .doesNotContain("Not yet available")
                .containsIgnoringCase("quarkus-flyway");
    }

    @Test
    void restApiIsUnavailableWithAJaxRsHintWhenNoResourcesArePresent() {
        // Default: bootui.internal.rest-api-present is unset, so the deployment processor saw no application
        // @Path resources. The panel must surface an honest JAX-RS hint, NOT the generic reason.
        PanelDto restApi = manifestById().get(BootUiPanels.REST_API);
        assertThat(restApi.available()).isFalse();
        assertThat(restApi.unavailableReason())
                .isNotNull()
                .doesNotContain("Not yet available")
                .containsIgnoringCase("@Path");
    }

    @Test
    void restApiIsAvailableWhenTheBuildTimeFlagIsSet() {
        StubConfig withRestApi = new StubConfig(Map.of(QuarkusPanelAvailability.REST_API_PRESENT_KEY, "true"));
        PanelDto restApi = manifestById(withRestApi).get(BootUiPanels.REST_API);
        assertThat(restApi.available())
                .as("REST API advisor is lit up when the application declares JAX-RS resources")
                .isTrue();
        assertThat(restApi.unavailableReason()).isNull();
    }

    @Test
    void flywayIsAvailableWhenTheBuildTimeFlagIsSet() {
        StubConfig withFlyway = new StubConfig(Map.of(QuarkusPanelAvailability.FLYWAY_PRESENT_KEY, "true"));
        PanelDto flyway = manifestById(withFlyway).get(BootUiPanels.FLYWAY);
        assertThat(flyway.available())
                .as("Flyway is lit up when quarkus-flyway is present")
                .isTrue();
        assertThat(flyway.unavailableReason()).isNull();
    }

    @Test
    void githubAvailabilityRoutesThroughTheRepositoryDetector() {
        // GitHub availability is dynamic (mirrors Spring's PanelsController.githubAvailable()): it is computed
        // from the shared GitHubRepositoryDetector, never the static "not yet" fallback. This module is itself
        // a checkout of github.com/jdubois/boot-ui, so the detector finds the repo and the panel lights up with
        // no unavailable reason — exactly as it does in CI and in a developer's own GitHub-hosted project.
        PanelDto github = manifestById().get(BootUiPanels.GITHUB);
        assertThat(github).as("GitHub panel is present in the manifest").isNotNull();
        assertThat(github.available())
                .as("GitHub lights up when the working directory is a GitHub checkout")
                .isTrue();
        assertThat(github.unavailableReason()).isNull();
    }

    @Test
    void connectionPoolsIsUnavailableWithAHintWhenNoDatasourceIsPresent() {
        // Default: bootui.internal.connection-pools-present is unset, so the deployment processor never saw the
        // AGROAL capability (no JDBC datasource). The panel must surface an honest hint, NOT the generic reason.
        PanelDto pools = manifestById().get(BootUiPanels.DATABASE_CONNECTION_POOLS);
        assertThat(pools.available()).isFalse();
        assertThat(pools.unavailableReason())
                .doesNotContain("Not yet available")
                .containsIgnoringCase("JDBC datasource");
    }

    @Test
    void connectionPoolsIsAvailableWhenTheBuildTimeFlagIsSet() {
        StubConfig withDatasource =
                new StubConfig(Map.of(QuarkusPanelAvailability.CONNECTION_POOLS_PRESENT_KEY, "true"));
        PanelDto pools = manifestById(withDatasource).get(BootUiPanels.DATABASE_CONNECTION_POOLS);
        assertThat(pools.available())
                .as("Database Connection Pools is lit up when a JDBC datasource (Agroal) is present")
                .isTrue();
        assertThat(pools.unavailableReason()).isNull();
    }
}
