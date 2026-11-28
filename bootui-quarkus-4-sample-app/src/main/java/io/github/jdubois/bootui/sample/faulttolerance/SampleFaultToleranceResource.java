package io.github.jdubois.bootui.sample.faulttolerance;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Quarkus analogue of the Spring sample's {@code SampleFaultToleranceController}. Exercises the sample's fault
 * tolerance annotations on demand so the BootUI Fault Tolerance panel shows a circuit breaker that has actually
 * moved state. Nothing runs on startup or on page load.
 */
@Path("/api/sample/fault-tolerance")
public class SampleFaultToleranceResource {

    @Inject
    SampleFaultToleranceService faultTolerance;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> exerciseFaultTolerance() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reservation", faultTolerance.reserve("BOOTUI-1"));
        int failures = 0;
        int rejections = 0;
        for (int attempt = 0; attempt < 6; attempt++) {
            try {
                faultTolerance.charge("ORDER-" + attempt);
            } catch (IllegalStateException failure) {
                failures++;
            } catch (RuntimeException rejected) {
                // CircuitBreakerOpenException once the breaker trips.
                rejections++;
            }
        }
        result.put("circuitBreakerFailures", failures);
        result.put("circuitBreakerRejections", rejections);
        return result;
    }
}
