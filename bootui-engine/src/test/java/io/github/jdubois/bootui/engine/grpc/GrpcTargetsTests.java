package io.github.jdubois.bootui.engine.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.SecretMasker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link GrpcTargets}. A configured gRPC target is user-supplied text that may carry
 * credentials or simply be malformed, and BootUI must render it without ever resolving it or echoing a
 * secret, so these tests pin the redaction, the bound, and the authority heuristics — including the shapes
 * that legitimately have no authority at all.
 */
class GrpcTargetsTests {

    @Test
    void returnsNullForBlankTargets() {
        assertThat(GrpcTargets.normalize(null)).isNull();
        assertThat(GrpcTargets.normalize("   ")).isNull();
    }

    @Test
    void redactsEmbeddedUserInfo() {
        String normalized = GrpcTargets.normalize("dns://alice:hunter2@billing.internal:443");

        assertThat(normalized).isEqualTo("dns://" + SecretMasker.MASKED_VALUE + "@billing.internal:443");
        assertThat(GrpcTargets.authority(normalized)).isEqualTo("billing.internal:443");
    }

    @Test
    void dropsQueryStringsAndFragmentsWholesale() {
        assertThat(GrpcTargets.normalize("dns:///billing:443?token=abcdef#frag"))
                .isEqualTo("dns:///billing:443");
    }

    @Test
    void redactsCredentialParametersLeftInThePath() {
        String normalized = GrpcTargets.normalize("static://localhost:9090/service;password=hunter2");

        assertThat(normalized).doesNotContain("hunter2");
    }

    @Test
    void boundsPathologicallyLongTargets() {
        String normalized = GrpcTargets.normalize("dns:///" + "a".repeat(500));

        assertThat(normalized).hasSize(GrpcTargets.MAX_TARGET_LENGTH + 1).endsWith("…");
    }

    @ParameterizedTest
    @CsvSource({
        "static://localhost:9090, localhost:9090",
        "dns:///billing.internal:443, billing.internal:443",
        "localhost:9090, localhost:9090",
        "billing.internal, billing.internal",
        "stork:///inventory, inventory"
    })
    void extractsTheAuthorityOfAResolvableTarget(String target, String expected) {
        assertThat(GrpcTargets.authority(GrpcTargets.normalize(target))).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "unix:/var/run/grpc.sock",
        "unix:///var/run/grpc.sock",
        "unix:@bootui-abstract",
        "in-process:sample",
        "':::'"
    })
    void reportsNoAuthorityForTargetsThatCarryNone(String target) {
        assertThat(GrpcTargets.authority(GrpcTargets.normalize(target))).isNull();
    }

    @Test
    void keepsAnIpv6LiteralAuthorityIntact() {
        assertThat(GrpcTargets.authority(GrpcTargets.normalize("[::1]:9090"))).isEqualTo("[::1]:9090");
        assertThat(GrpcTargets.authority(GrpcTargets.normalize("dns:///[2001:db8::1]:443")))
                .isEqualTo("[2001:db8::1]:443");
    }

    @Test
    void rejectsAPortOutsideTheValidRange() {
        assertThat(GrpcTargets.authority(GrpcTargets.normalize("billing.internal:65536")))
                .isNull();
    }

    @Test
    void redactsCredentialsEmbeddedAfterTheResolverPath() {
        String normalized = GrpcTargets.normalize("dns://8.8.8.8/user:s3cret@billing.internal:443");
        assertThat(normalized).doesNotContain("s3cret").contains("billing.internal:443");
    }

    @Test
    void leavesAnAbstractUnixSocketNameIntact() {
        assertThat(GrpcTargets.normalize("unix:@bootui-abstract")).isEqualTo("unix:@bootui-abstract");
    }

    @Test
    void neverThrowsOnAMalformedTarget() {
        assertThat(GrpcTargets.normalize("://")).isEqualTo("://");
        assertThat(GrpcTargets.authority("://")).isNull();
        assertThat(GrpcTargets.authority(null)).isNull();
    }
}
