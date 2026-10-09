package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.SecuritySinks;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The side-effect sensors' self-test reports never carry the temporary directory (PLAN-v2 M5-5d). */
class SideEffectsSensorTests {

    @Test
    void aSelfTestResultNamesTheTemporaryDirectoryAsTmpdir() {
        String windows = "C:\\Users\\bob\\AppData\\Local\\Temp\\";
        String text =
                "error: [FileInputStream: java.io.IOException: C:\\Users\\bob\\AppData\\Local\\Temp\\bootui-x\\file,"
                        + " C:/Users/bob/AppData/Local/Temp/bootui-x was created]";

        assertThat(SideEffectsSensor.withoutTemporaryDirectory(text, windows))
                .doesNotContain("bob")
                .contains("$TMPDIR\\bootui-x\\file")
                .contains("$TMPDIR/bootui-x was created");
        assertThat(SideEffectsSensor.withoutTemporaryDirectory("/var/folders/ab/T/x failed", "/var/folders/ab/T/"))
                .isEqualTo("$TMPDIR/x failed");
        assertThat(SideEffectsSensor.withoutTemporaryDirectory("ok", "/tmp")).isEqualTo("ok");
    }

    @Test
    void theFilesSelfTestReportsOkAndNeverItsPath() {
        String result = SideEffectsSensor.filesStep();

        assertThat(result).isEqualTo("ok");
        assertThat(result).doesNotContain(System.getProperty("java.io.tmpdir"));
    }

    /** A failed core hook switches its own security-sinks check group off, with its reason, never another (M5-6b2). */
    @Test
    void aFailedCoreHookSwitchesItsCheckGroupOffAlone() {
        Map<String, String> results = new LinkedHashMap<>();
        for (String[] hook : SideEffectsSensor.HOOKS) {
            results.put(hook[0], "passed");
        }
        results.put("ObjectInputStream.readObject", "failed");
        Map<String, String> errors =
                Map.of("ObjectInputStream.readObject", "failed (not-exercised: no jdk.unsupported)");
        String[] reasons = new String[SecuritySinks.GROUP_IDS.length];

        int on = SideEffectsSensor.checkGroups(results, Set.of(), errors, reasons);

        assertThat(on).isEqualTo(SecuritySinks.GROUP_ALGORITHMS | SecuritySinks.GROUP_TRUST);
        assertThat(reasons[0])
                .isEqualTo("self-test failed for [ObjectInputStream.readObject: failed (not-exercised:"
                        + " no jdk.unsupported)]");
        assertThat(reasons[1]).isNull();
        assertThat(reasons[2]).isNull();

        // Left out after an earlier round, the hook still keeps its group off; an optional hook never does.
        results.put("ObjectInputStream.readObject", "passed");
        results.put("ObjectInputStream.resolveClass", "failed");
        results.put("HttpsURLConnection.setDefaultHostnameVerifier", "failed");
        String[] again = new String[SecuritySinks.GROUP_IDS.length];
        assertThat(SideEffectsSensor.checkGroups(
                        results, Set.of("SSLContext.init", "ObjectInputStream.resolveClass"), Map.of(), again))
                .isEqualTo(SecuritySinks.GROUP_DESERIALIZATION | SecuritySinks.GROUP_ALGORITHMS);
        assertThat(again[2]).isEqualTo("self-test failed for [SSLContext.init: passed]");
    }
}
