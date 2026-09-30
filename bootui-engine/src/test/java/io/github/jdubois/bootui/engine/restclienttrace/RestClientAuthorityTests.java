package io.github.jdubois.bootui.engine.restclienttrace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@code docs/PLAN-v2.md} §5.1: two local services on different ports stay distinct in Live Activity. */
class RestClientAuthorityTests {

    @Test
    void keepsAnExplicitPortAndOtherwiseTheHostAlone() {
        assertThat(RestClientAuthority.of("localhost", "http://localhost:8082/api/products?token=******"))
                .isEqualTo("localhost:8082");
        assertThat(RestClientAuthority.of("api.example.com", "https://api.example.com/rates"))
                .isEqualTo("api.example.com");
        assertThat(RestClientAuthority.of("localhost", "not a uri")).isEqualTo("localhost");
        assertThat(RestClientAuthority.of("localhost", null)).isEqualTo("localhost");
        assertThat(RestClientAuthority.of(null, "http://localhost:8082/")).isEmpty();
    }
}
