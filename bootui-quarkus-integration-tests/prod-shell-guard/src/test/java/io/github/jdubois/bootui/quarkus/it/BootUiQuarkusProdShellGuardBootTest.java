package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.github.jdubois.bootui.engine.safety.BootUiSecurityHeaders;
import io.quarkus.test.QuarkusProdModeTest;
import java.util.Map;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Runs in a genuine {@link io.quarkus.runtime.LaunchMode#NORMAL} (production) process rather than
 * {@code LaunchMode.TEST} — every {@code @QuarkusTest} elsewhere in the {@code bootui-quarkus-integration-tests}
 * reactor can only exercise {@code LaunchMode.TEST}. {@link QuarkusProdModeTest} is the standard Quarkus
 * extension-author mechanism for exactly this: it builds a real production artifact (using this module's own
 * resolved dependencies, so {@code bootui-quarkus}/{@code bootui-ui} are included automatically, exactly as
 * they would be for a real host application) and, with {@code setRun(true)}, launches it as a real
 * {@code java -jar} subprocess, waiting for the "Installed features" startup line before handing control back
 * to the test.
 *
 * <p>This proves the fix end-to-end: without {@code BootUiProdShellGuardFilter}, {@code GET /bootui/} in this
 * exact setup would return {@code 200} with the shared Vue shell's {@code index.html} (that <em>is</em> what
 * {@code BootUiQuarkusTracerBulletSmokeTest} asserts happens under {@code LaunchMode.TEST}, and the static
 * handler serving it is wired identically in production — that is the whole bug). Here, in a real
 * {@code LaunchMode.NORMAL} process, it must be a plain {@code 404} instead.
 *
 * <p><b>Why this class lives in its own module.</b> Quarkus's test framework tracks which test mechanism is in
 * use per JUnit Platform launcher session (i.e. per Surefire fork) and refuses to mix them:
 * {@code io.quarkus.test.ExclusivityChecker} throws {@code IllegalStateException} if a
 * {@code QuarkusTestExtension}-based test ({@code @QuarkusTest}, used throughout {@code base} and every other
 * sibling module) and a {@link QuarkusProdModeTest}-based test run in the same JVM. So this class cannot live
 * alongside {@code base}'s {@code @QuarkusTest} classes — {@code bootui-quarkus-prod-shell-guard-integration-tests}
 * exists solely to give it an isolated Surefire fork.
 *
 * <p>Runs on a dedicated port ({@value #PORT}) rather than the default {@code 8081} to avoid any ambiguity with
 * the port other modules' {@code @QuarkusTest}s bind to. A minimal {@code application.properties} is supplied
 * explicitly via {@link StringAsset} (naming the app) so the build is self-contained and not dependent on any
 * incidental classpath resource.
 */
class BootUiQuarkusProdShellGuardBootTest {

    private static final int PORT = 8083;

    @RegisterExtension
    static final QuarkusProdModeTest config = new QuarkusProdModeTest()
            .withApplicationRoot(jar -> jar.add(
                    new StringAsset("quarkus.application.name=bootui-quarkus-prod-shell-guard-it\n"),
                    "application.properties"))
            .setApplicationName("bootui-quarkus-prod-shell-guard-it")
            .setRuntimeProperties(Map.of("quarkus.http.port", String.valueOf(PORT)))
            .setRun(true);

    private BootUiHttpProbe probe() {
        return new BootUiHttpProbe("http://localhost:" + PORT);
    }

    @Test
    void productionShellDirectoryIndexIs404() {
        Response response = probe().get("/bootui/");
        assertThat(response.status())
                .as("GET /bootui/ must be a plain 404 in production, not the static shell's index.html")
                .isEqualTo(404);
        assertThat(response.header(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY))
                .isEqualTo(BootUiSecurityHeaders.CSP_VALUE);
        assertThat(response.header(BootUiSecurityHeaders.CACHE_CONTROL)).isEqualTo(BootUiSecurityHeaders.NO_CACHE);
    }

    @Test
    void productionShellNoTrailingSlashIs404() {
        // Also proves QuarkusIndexResource (the dev/test /bootui-without-trailing-slash JAX-RS resource) is
        // correctly absent here: it is only ever discovered by the launch-mode-gated registerConsole build
        // step, so there is nothing for the guard filter to be "backing up" on this path in production.
        Response response = probe().get("/bootui");
        assertThat(response.status())
                .as("GET /bootui (no trailing slash) must be a plain 404 in production")
                .isEqualTo(404);
    }

    @Test
    void productionStaticAssetIs404() {
        Response response = probe().get("/bootui/index.html");
        assertThat(response.status())
                .as("GET /bootui/index.html must be a plain 404 in production, not the shell asset")
                .isEqualTo(404);
    }

    @Test
    void productionHashedLookingAsset404IsNotImmutable() {
        Response response = probe().get("/bootui/assets/missing-C2x2BcDS.js");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.header(BootUiSecurityHeaders.CACHE_CONTROL)).isEqualTo(BootUiSecurityHeaders.NO_CACHE);
        assertThat(response.header(BootUiSecurityHeaders.PRAGMA)).isEqualTo(BootUiSecurityHeaders.PRAGMA_NO_CACHE);
    }

    @Test
    void productionApiSurfaceIs404() {
        // Already unreachable in production because nothing registers it (registerConsole is skipped
        // entirely), but asserted here too so this test pins the guard's full-surface coverage, not just
        // the static-shell gap it was written to close.
        Response response = probe().get("/bootui/api/overview");
        assertThat(response.status())
                .as("GET /bootui/api/overview must be a plain 404 in production")
                .isEqualTo(404);
    }
}
