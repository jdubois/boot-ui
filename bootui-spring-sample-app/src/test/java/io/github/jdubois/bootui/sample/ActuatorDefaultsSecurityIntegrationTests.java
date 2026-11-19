package io.github.jdubois.bootui.sample;

import io.github.jdubois.bootui.conformance.ActuatorSecurityContract;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "server.address=127.0.0.1",
            "bootui.allow-non-localhost=true",
            "bootui.authentication.token=" + ActuatorSecurityContract.TOKEN,
            "bootui.show-banner=false",
            "management.endpoint.health.probes.enabled=true",
            "bootui.heap-dump.capture-enabled=false",
            "management.endpoint.health.probes.add-additional-paths=true",
            "bootui.overrides-file=target/actuator-security/application-bootui.properties"
        })
@DirtiesContext
class ActuatorDefaultsSecurityIntegrationTests {
    @LocalServerPort
    int port;

    @Test
    void protectsAddedRawExposureWithoutRemovingInProcessPanels() {
        String url = "http://127.0.0.1:" + port;
        ActuatorSecurityContract.verify(url, url, "/actuator", "env", true, true);
        ActuatorSecurityContract.verifyHealthAlias(url, "/readyz");
        ActuatorSecurityContract.verifyCaptureConfirmation(url);
    }
}

@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "server.address=127.0.0.1",
            "bootui.allow-non-localhost=true",
            "bootui.authentication.token=" + ActuatorSecurityContract.TOKEN,
            "bootui.show-banner=false",
            "management.endpoint.health.probes.enabled=true",
            "management.server.port=0",
            "management.server.address=127.0.0.1",
            "management.server.base-path=/manage",
            "management.endpoints.web.base-path=/actuator/private",
            "management.endpoints.web.path-mapping.env=environment",
            "management.endpoint.health.show-details=never",
            "bootui.overrides-file=target/actuator-security/application-bootui.properties"
        })
@DirtiesContext
class ActuatorManagementSecurityIntegrationTests {
    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    @Test
    void guardsCustomManagementPortAndMappedPathButPreservesHostHealthPolicy() {
        ActuatorSecurityContract.verify(
                "http://127.0.0.1:" + port,
                "http://127.0.0.1:" + managementPort,
                "/manage/actuator/private",
                "environment",
                true,
                false);
    }
}

@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "server.address=127.0.0.1",
            "bootui.allow-non-localhost=true",
            "bootui.authentication.token=" + ActuatorSecurityContract.TOKEN,
            "bootui.show-banner=false",
            "management.endpoint.health.probes.enabled=true",
            "management.endpoints.web.exposure.include=health,env,loggers,beans,configprops",
            "bootui.overrides-file=target/actuator-security/application-bootui.properties"
        })
@DirtiesContext
class ActuatorHostExposureSecurityIntegrationTests {
    @LocalServerPort
    int port;

    @Test
    void hostExposureDoesNotMakeBootUiHealthDetailsHostOwned() {
        String url = "http://127.0.0.1:" + port;
        ActuatorSecurityContract.verify(url, url, "/actuator", "env", false, true);
    }
}

@SpringBootTest(
        classes = BootUiSampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "server.address=127.0.0.1",
            "bootui.show-banner=false",
            "management.endpoint.health.probes.enabled=true",
            "management.endpoint.health.probes.add-additional-paths=true",
            "management.server.port=0",
            "management.server.address=127.0.0.1",
            "bootui.overrides-file=target/actuator-security/application-bootui.properties"
        })
@DirtiesContext
class ActuatorAdditionalHealthSecurityIntegrationTests {
    @LocalServerPort
    int port;

    @Test
    void healthAliasesRemainGuardedOnMainWhenManagementUsesAnotherListener() {
        ActuatorSecurityContract.verifyHealthAlias("http://127.0.0.1:" + port, "/readyz");
    }
}
