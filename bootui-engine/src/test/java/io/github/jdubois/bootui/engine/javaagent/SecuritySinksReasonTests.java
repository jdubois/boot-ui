package io.github.jdubois.bootui.engine.javaagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The installed security-sinks sensor's reason ({@code docs/PLAN-v2.md} §5.16, M5-6b2): its JDK check groups, each on
 * or off alone with why, then its request-value matching.
 */
class SecuritySinksReasonTests {

    @Test
    void theReasonNamesTheCheckGroupsOnAndOffAndRequestValueMatching() {
        Map<String, Object> groups = new LinkedHashMap<>();
        groups.put("deserialization", "on");
        groups.put("weak-algorithms", "off: self-test failed for [MessageDigest.getInstance: failed]");
        groups.put("trust-managers", "on");

        String reason = JavaAgentService.securitySinksReason(Map.of("groups", groups), Map.of(), List.of());

        assertThat(reason)
                .startsWith("JDK checks: deserialization without a filter, trust managers and hostname verifiers; off:"
                        + " weak algorithms (self-test failed for [MessageDigest.getInstance: failed]). ")
                .contains("Request-value matching is off");
    }

    @Test
    void anAgentReportingNoGroupsRunsNone() {
        assertThat(JavaAgentService.securitySinksReason(Map.of(), Map.of(), List.of()))
                .startsWith("JDK checks: none run; off: deserialization without a filter; weak algorithms; trust"
                        + " managers and hostname verifiers. ");
    }
}
