package io.github.jdubois.bootui.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretMaskerTests {

    private final SecretMasker masker = new SecretMasker();

    @Test
    void detectsCommonSecretKeys() {
        assertThat(masker.isSecret("spring.datasource.password")).isTrue();
        assertThat(masker.isSecret("MY_SECRET_TOKEN")).isTrue();
        assertThat(masker.isSecret("app.api-key")).isTrue();
        assertThat(masker.isSecret("server.port")).isFalse();
        assertThat(masker.isSecret(null)).isFalse();
        assertThat(masker.isSecret("")).isFalse();
    }

    @Test
    void masksValueWhenKeyMatches() {
        assertThat(masker.mask("spring.datasource.password", "hunter2"))
            .isEqualTo(SecretMasker.MASKED_VALUE);
        assertThat(masker.mask("server.port", 8080)).isEqualTo(8080);
        assertThat(masker.mask("spring.datasource.password", null)).isNull();
    }
}
