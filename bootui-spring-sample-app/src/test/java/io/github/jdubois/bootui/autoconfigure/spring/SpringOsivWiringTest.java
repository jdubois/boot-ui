package io.github.jdubois.bootui.autoconfigure.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.sample.BootUiSampleApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;

/** Pins the positive counterpart using Boot's real interceptor/configurer, not a property-only fact. */
@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.jpa.open-in-view=true",
            "spring.datasource.url=jdbc:h2:mem:bootui_osiv_wiring;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
            "bootui.enabled=ON",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/bootui-osiv-wiring-overrides.properties"
        })
class SpringOsivWiringTest {
    @Autowired
    ConfigurableApplicationContext application;

    @Test
    void bootAppliedOsivStillProducesTheExistingMediumFinding() {
        var snapshot = SpringInventory.discover(application.getBeanFactory(), application.getEnvironment(), false);
        var observation =
                snapshot.observations().get(SpringObservations.Fact.OSIV, SpringObservations.OsivObservation.class);
        assertThat(observation.complete()).isTrue();
        assertThat(observation.registration()).isEqualTo("Boot servlet interceptor applied to MVC handler mappings");
        var result = new OpenSessionInViewEnabledRule().evaluate(snapshot);
        assertThat(result.status()).isEqualTo("VIOLATION");
        assertThat(result.severity()).isEqualTo("MEDIUM");
        assertThat(result.violationCount()).isOne();
        assertThat(SpringScanner.evidence(snapshot).usable()).isTrue();
        assertThat(SpringScanner.evidence(snapshot).coverageComplete()).isTrue();
    }
}
