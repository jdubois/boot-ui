package io.github.jdubois.bootui.autoconfigure.safety;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.BootUiPathNormalizer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * White-box coverage for the servlet shell guard: what it blocks, what it must never touch, how it
 * behaves under a host {@code server.servlet.context-path}, and that it matches the decoded path rather
 * than the raw request URI.
 */
class BootUiShellGuardFilterTests {

    private static final List<String> DEFAULT_MOUNTS = List.of(BootUiPathNormalizer.DEFAULT_PATH);

    @Test
    void blocksThePackagedShellAndItsAssets() throws Exception {
        assertThat(status("/bootui/index.html", "")).isEqualTo(404);
        assertThat(status("/bootui/assets/index-abc123.js", "")).isEqualTo(404);
        assertThat(status("/bootui/assets/index-abc123.css", "")).isEqualTo(404);
        assertThat(status("/bootui/", "")).isEqualTo(404);
        assertThat(status("/bootui", "")).isEqualTo(404);
    }

    @Test
    void blocksTheApiMountToo() throws Exception {
        // Nothing serves it while BootUI is inactive, but the guard must not carve out an exception
        // that a future change could turn into a hole.
        assertThat(status("/bootui/api/overview", "")).isEqualTo(404);
    }

    @Test
    void blocksPercentEncodedAndMatrixParameterSpellingsOfTheMount() throws Exception {
        // Spring resolves handlers against the decoded path, so these all reach the packaged bundle.
        assertThat(status("/%62ootui/index.html", "")).isEqualTo(404);
        assertThat(status("/boot%75i/index.html", "")).isEqualTo(404);
        assertThat(status("/bootui;version=1/index.html", "")).isEqualTo(404);
    }

    @Test
    void blocksTheMountUnderAHostContextPath() throws Exception {
        assertThat(status("/host/bootui/index.html", "/host")).isEqualTo(404);
    }

    @Test
    void blocksEveryConfiguredMount() throws Exception {
        // What BootUiShellGuardMounts derives for spring.mvc.servlet.path=/app plus
        // spring.mvc.static-path-pattern=/static/**.
        List<String> mounts = List.of("/bootui", "/app/static/bootui");
        assertThat(status(mounts, "/app/static/bootui/index.html", "")).isEqualTo(404);
        assertThat(status(mounts, "/bootui/index.html", "")).isEqualTo(404);
        assertThat(status(mounts, "/app/static/other.js", "")).isEqualTo(200);
    }

    @Test
    void leavesHostApplicationRoutesUntouched() throws Exception {
        assertThat(status("/", "")).isEqualTo(200);
        assertThat(status("/api/orders", "")).isEqualTo(200);
        // Adjacent paths that merely share the "/bootui" prefix are not under the mount.
        assertThat(status("/bootui-console/index.html", "")).isEqualTo(200);
        assertThat(status("/bootuix", "")).isEqualTo(200);
        // A host route that happens to sit at the same-named path under a different context.
        assertThat(status("/host/console/bootui", "/host")).isEqualTo(200);
    }

    private static int status(String uri, String contextPath) throws Exception {
        return status(DEFAULT_MOUNTS, uri, contextPath);
    }

    private static int status(List<String> mounts, String uri, String contextPath) throws Exception {
        BootUiShellGuardFilter filter = new BootUiShellGuardFilter(mounts);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        request.setContextPath(contextPath);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
