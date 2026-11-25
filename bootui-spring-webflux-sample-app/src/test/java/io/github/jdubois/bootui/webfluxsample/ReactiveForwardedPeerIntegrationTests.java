package io.github.jdubois.bootui.webfluxsample;

import io.github.jdubois.bootui.conformance.ForwardedPeerContract;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        classes = BootUiWebfluxSampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "server.address=0.0.0.0",
            "server.forward-headers-strategy=NATIVE",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/forwarded-peer/application-bootui.properties"
        })
class ReactiveForwardedPeerIntegrationTests {

    @LocalServerPort
    int port;

    @Test
    void nativeForwardingNeverSubstitutesForTheSocketPeer() {
        ForwardedPeerContract.verify("http://127.0.0.1:" + port);
    }
}

@SpringBootTest(
        classes = BootUiWebfluxSampleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "server.address=0.0.0.0",
            "server.forward-headers-strategy=FRAMEWORK",
            "bootui.show-banner=false",
            "bootui.overrides-file=target/forwarded-peer/application-bootui.properties"
        })
class ReactiveFrameworkForwardedPeerIntegrationTests {
    @LocalServerPort
    int port;

    @Test
    void frameworkForwardingDoesNotReplacePeerOrHost() {
        ForwardedPeerContract.verify("http://127.0.0.1:" + port);
    }
}
