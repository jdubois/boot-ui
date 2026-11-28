package io.github.jdubois.bootui.quarkus4.it;

import io.github.jdubois.bootui.conformance.ForwardedPeerContract;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(BootUiQuarkusForwardedPeerBootTest.Forwarding.class)
class BootUiQuarkusForwardedPeerBootTest {

    @TestHTTPResource
    URL baseUrl;

    @Test
    void forwardingNeverSubstitutesForTheSocketPeer() {
        ForwardedPeerContract.verify(baseUrl.toExternalForm());
    }

    public static class Forwarding implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.http.host",
                    "0.0.0.0",
                    "quarkus.http.test-host",
                    "127.0.0.1",
                    "quarkus.http.proxy.proxy-address-forwarding",
                    "true",
                    "quarkus.http.proxy.allow-forwarded",
                    "true",
                    "quarkus.http.proxy.allow-x-forwarded",
                    "true");
        }
    }
}
