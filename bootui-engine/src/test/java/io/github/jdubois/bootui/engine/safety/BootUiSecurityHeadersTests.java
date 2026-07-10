package io.github.jdubois.bootui.engine.safety;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BootUiSecurityHeadersTests {

    @Test
    void apiResponsesAreNeverCached() {
        assertThat(BootUiSecurityHeaders.headersFor("/bootui/api/overview", "/bootui/api", 200))
                .containsEntry(BootUiSecurityHeaders.CACHE_CONTROL, BootUiSecurityHeaders.NO_STORE)
                .containsEntry(BootUiSecurityHeaders.PRAGMA, BootUiSecurityHeaders.PRAGMA_NO_CACHE);
    }

    @Test
    void onlyContentHashedAssetsAreImmutable() {
        assertThat(BootUiSecurityHeaders.cacheControl("/bootui/assets/index-C2x2BcDS.js", "/bootui/api", 200))
                .isEqualTo(BootUiSecurityHeaders.IMMUTABLE);
        assertThat(BootUiSecurityHeaders.cacheControl("/bootui/assets/index.js", "/bootui/api", 200))
                .isEqualTo(BootUiSecurityHeaders.NO_CACHE);
        assertThat(BootUiSecurityHeaders.cacheControl("/bootui/assets/missing.js", "/bootui/api", 404))
                .isEqualTo(BootUiSecurityHeaders.NO_CACHE);
        assertThat(BootUiSecurityHeaders.cacheControl("/bootui/assets/missing-C2x2BcDS.js", "/bootui/api", 404))
                .isEqualTo(BootUiSecurityHeaders.NO_CACHE);
    }

    @Test
    void immutableAssetsDoNotCarryConflictingPragma() {
        assertThat(BootUiSecurityHeaders.headersFor("/bootui/assets/index-C2x2BcDS.js", "/bootui/api", 200))
                .containsEntry(BootUiSecurityHeaders.CACHE_CONTROL, BootUiSecurityHeaders.IMMUTABLE)
                .doesNotContainKey(BootUiSecurityHeaders.PRAGMA);
        assertThat(BootUiSecurityHeaders.removesPragma("/bootui/assets/index-C2x2BcDS.js", "/bootui/api", 200))
                .isTrue();
    }

    @Test
    void shellAndErrorsAreRevalidated() {
        assertThat(BootUiSecurityHeaders.cacheControl("/bootui/", "/bootui/api", 200))
                .isEqualTo(BootUiSecurityHeaders.NO_CACHE);
        assertThat(BootUiSecurityHeaders.cacheControl(null, "/bootui/api", 500))
                .isEqualTo(BootUiSecurityHeaders.NO_CACHE);
    }

    @Test
    void canonicalHeadersContainTheCompleteBrowserPolicy() {
        Map<String, String> headers = BootUiSecurityHeaders.headersFor("/bootui/", "/bootui/api", 200);

        assertThat(headers)
                .containsEntry(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY, BootUiSecurityHeaders.CSP_VALUE)
                .containsEntry(BootUiSecurityHeaders.X_CONTENT_TYPE_OPTIONS, BootUiSecurityHeaders.NOSNIFF)
                .containsEntry(BootUiSecurityHeaders.X_FRAME_OPTIONS, BootUiSecurityHeaders.DENY)
                .containsEntry(
                        BootUiSecurityHeaders.REFERRER_POLICY, BootUiSecurityHeaders.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                .containsEntry(
                        BootUiSecurityHeaders.PERMISSIONS_POLICY, BootUiSecurityHeaders.PERMISSIONS_POLICY_VALUE);
        assertThat(BootUiSecurityHeaders.CSP_VALUE)
                .contains("base-uri 'self'", "form-action 'self'", "frame-ancestors 'none'")
                .doesNotContain("unsafe-eval");
    }

    @Test
    void onlyCacheHeadersOverrideHostPolicy() {
        assertThat(BootUiSecurityHeaders.overridesExisting(BootUiSecurityHeaders.CACHE_CONTROL))
                .isTrue();
        assertThat(BootUiSecurityHeaders.overridesExisting(BootUiSecurityHeaders.PRAGMA))
                .isTrue();
        assertThat(BootUiSecurityHeaders.overridesExisting(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY))
                .isFalse();
    }
}
