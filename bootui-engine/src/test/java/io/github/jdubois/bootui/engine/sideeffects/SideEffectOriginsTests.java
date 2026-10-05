package io.github.jdubois.bootui.engine.sideeffects;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SideEffectOriginsTests {

    @Test
    void theJdkContextDecidesFirst() {
        assertThat(SideEffectOrigins.origin(SideEffectRecord.CONTEXT_JDK_LOGGING, null, null))
                .isEqualTo(SideEffectOrigins.LOGGING);
        assertThat(SideEffectOrigins.origin(
                        SideEffectRecord.CONTEXT_CLASS_LOADING, "com.acme.App#run", "com.acme.App#run"))
                .isEqualTo(SideEffectOrigins.CLASS_PATH);
        assertThat(SideEffectOrigins.origin(SideEffectRecord.CONTEXT_JDK_ONLY, null, null))
                .isEqualTo(SideEffectOrigins.JDK);
    }

    @Test
    void theFirstFrameOutsideTheJdkNamesLoggingAndClassPathFrameworksBeforeTheApplication() {
        assertThat(SideEffectOrigins.origin(
                        0, "ch.qos.logback.core.recovery.ResilientFileOutputStream#<init>", "com.acme.Orders#log"))
                .isEqualTo(SideEffectOrigins.LOGGING);
        assertThat(SideEffectOrigins.origin(0, "org.jboss.logmanager.handlers.FileHandler#setFile", null))
                .isEqualTo(SideEffectOrigins.LOGGING);
        assertThat(SideEffectOrigins.origin(0, "org.springframework.boot.loader.jar.NestedJarFile#<init>", null))
                .isEqualTo(SideEffectOrigins.CLASS_PATH);
        assertThat(SideEffectOrigins.origin(0, "com.acme.Reports#write", "com.acme.Reports#write"))
                .isEqualTo(SideEffectOrigins.APPLICATION);
        assertThat(SideEffectOrigins.origin(0, "org.h2.store.fs.FilePathDisk#newOutputStream", null))
                .isEqualTo(SideEffectOrigins.LIBRARY);
        assertThat(SideEffectOrigins.origin(0, null, null)).isEqualTo(SideEffectOrigins.UNKNOWN);
        assertThat(SideEffectOrigins.groupedApart(SideEffectOrigins.LOGGING)).isTrue();
        assertThat(SideEffectOrigins.groupedApart(SideEffectOrigins.LIBRARY)).isFalse();
    }

    @Test
    void theLocationComesFromThePatternsPrefix() {
        assertThat(SideEffectOrigins.location("$TMPDIR/upload-{n}.tmp"))
                .isEqualTo(SideEffectOrigins.TEMPORARY_DIRECTORY);
        assertThat(SideEffectOrigins.location("./reports/report-{n}.csv"))
                .isEqualTo(SideEffectOrigins.WORKING_DIRECTORY);
        assertThat(SideEffectOrigins.location("~/.m2/settings.xml")).isEqualTo(SideEffectOrigins.HOME);
        assertThat(SideEffectOrigins.location("/proc/self/cgroup")).isEqualTo(SideEffectOrigins.SYSTEM);
        assertThat(SideEffectOrigins.location("/srv/data/x.bin")).isEqualTo(SideEffectOrigins.ELSEWHERE);
        assertThat(SideEffectOrigins.location("$TMPDIRX/a")).isEqualTo(SideEffectOrigins.ELSEWHERE);
    }

    @Test
    void segmentsAndNamesThatLookLikeSecretsAreMasked() {
        assertThat(SideEffectOrigins.maskPath("/keys/AKIAABCDEFGHIJKLMNOP/x.txt"))
                .isEqualTo("/keys/******/x.txt");
        assertThat(SideEffectOrigins.maskPath("./reports/report-{n}.csv")).isEqualTo("./reports/report-{n}.csv");
        assertThat(SideEffectOrigins.maskName("AKIAABCDEFGHIJKLMNOP")).isEqualTo("******");
        assertThat(SideEffectOrigins.maskName("DATABASE_PASSWORD")).isEqualTo("DATABASE_PASSWORD");
    }
}
