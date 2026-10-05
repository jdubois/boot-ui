package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

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
}
