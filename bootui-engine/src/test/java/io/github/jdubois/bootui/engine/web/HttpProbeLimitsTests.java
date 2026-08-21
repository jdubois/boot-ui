package io.github.jdubois.bootui.engine.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/**
 * Pins the HTTP Probe budgets themselves: the shipped defaults (which every adapter uses, so they are
 * part of the cross-stack contract) and the fail-closed rejection of a nonsensical limit.
 */
class HttpProbeLimitsTests {

    @Test
    void defaultsAreTheDocumentedBudgets() {
        HttpProbeLimits limits = HttpProbeLimits.defaults();

        assertThat(limits.maxMethodBytes()).isEqualTo(32);
        assertThat(limits.maxPathBytes()).isEqualTo(2048);
        assertThat(limits.maxRequestBodyBytes()).isEqualTo(64 * 1024);
        assertThat(limits.maxHeaderCount()).isEqualTo(50);
        assertThat(limits.maxHeaderNameBytes()).isEqualTo(256);
        assertThat(limits.maxHeaderValueBytes()).isEqualTo(8 * 1024);
        assertThat(limits.maxTotalHeaderBytes()).isEqualTo(32 * 1024);
        assertThat(limits.maxResponseBodyBytes()).isEqualTo(BoundedBodyReader.HTTP_PROBE_MAX_BYTES);
    }

    @Test
    void requestBodyBudgetStaysBelowTheReactiveCodecDefault() {
        // The WebFlux codec buffers at 256 KiB by default; keeping the probe budget below it means the
        // canonical BootUI validation error, not an opaque framework error, is what a developer sees.
        assertThat(HttpProbeLimits.defaults().maxRequestBodyBytes()).isLessThan(256 * 1024);
    }

    @Test
    void nonPositiveLimitsAreRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new HttpProbeLimits(0, 2048, 1024, 50, 256, 8192, 32768, 1024))
                .withMessage("maxMethodBytes must be positive");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new HttpProbeLimits(32, 2048, -1, 50, 256, 8192, 32768, 1024))
                .withMessage("maxRequestBodyBytes must be positive");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new HttpProbeLimits(32, 2048, 1024, 50, 256, 8192, 32768, 0))
                .withMessage("maxResponseBodyBytes must be positive");
    }

    @Test
    void responseBudgetCanBeOverriddenWithoutTouchingInboundBudgets() {
        HttpProbeLimits limits = HttpProbeLimits.defaults().withMaxResponseBodyBytes(5);

        assertThat(limits.maxResponseBodyBytes()).isEqualTo(5);
        assertThat(limits.maxRequestBodyBytes())
                .isEqualTo(HttpProbeLimits.defaults().maxRequestBodyBytes());
    }
}
