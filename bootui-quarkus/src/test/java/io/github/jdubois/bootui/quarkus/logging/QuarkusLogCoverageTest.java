package io.github.jdubois.bootui.quarkus.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Whether BootUI's JBoss LogManager handler sees every WARN+ log, for the caught-exception outcomes (docs/PLAN-v2.md
 * M5-6): attached, not replaced during the window, and no logger keeping its records from the root.
 */
class QuarkusLogCoverageTest {

    private final Logger root = Logger.getLogger("");
    private Handler installed;
    private Logger bypassing;
    private Handler own;

    @AfterEach
    void restore() {
        if (installed != null) {
            root.removeHandler(installed);
        }
        if (bypassing != null) {
            bypassing.removeHandler(own);
            bypassing.setUseParentHandlers(true);
        }
    }

    @Test
    void aGapWhileDetachedReplacedDuringTheWindowOrBypassed() throws Exception {
        QuarkusLogCoverage coverage = new QuarkusLogCoverage();
        assertThat(coverage.gap(0, Long.MAX_VALUE)).contains("not attached");

        installed = new QuarkusLogTailHandler(new LogTailBuffer(), new InternalPackageMatcher(List.of()));
        root.addHandler(installed);
        QuarkusLogCoverage.handlerChanged();
        long changed = System.currentTimeMillis();
        assertThat(coverage.gap(changed - 1_000, Long.MAX_VALUE)).contains("replaced");
        Thread.sleep(5);
        assertThat(coverage.gap(System.currentTimeMillis(), Long.MAX_VALUE)).isNull();

        bypassing = Logger.getLogger("com.example.audit.quarkus");
        own = new ConsoleHandler();
        bypassing.addHandler(own);
        bypassing.setUseParentHandlers(false);
        assertThat(new QuarkusLogCoverage().bypassingLoggers()).contains("com.example.audit.quarkus");
    }
}
