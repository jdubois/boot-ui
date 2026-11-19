package io.github.jdubois.bootui.quarkus.it;

import io.github.jdubois.bootui.conformance.ActuatorSecurityContract;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(BootUiQuarkusHeapConfirmationBootTest.NoCapture.class)
class BootUiQuarkusHeapConfirmationBootTest {
    @TestHTTPResource
    URL baseUrl;

    @Test
    void missingOrDeclinedConfirmationNeverRunsCapture() {
        ActuatorSecurityContract.verifyCaptureConfirmation(
                baseUrl.toExternalForm().replaceAll("/$", ""));
    }

    public static class NoCapture implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("bootui.heap-dump.capture-enabled", "false");
        }
    }
}
