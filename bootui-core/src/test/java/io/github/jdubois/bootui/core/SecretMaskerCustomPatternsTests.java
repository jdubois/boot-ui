package io.github.jdubois.bootui.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class SecretMaskerCustomPatternsTests {

    @Test
    void detectionIsCaseInsensitive() {
        SecretMasker masker = new SecretMasker();
        assertThat(masker.isSecret("Spring.Datasource.Password")).isTrue();
        assertThat(masker.isSecret("APP.API-KEY")).isTrue();
        assertThat(masker.isSecret("Authorization")).isTrue();
        assertThat(masker.isSecret("X-PRIVATE-FIELD")).isTrue();
    }

    @Test
    void blankAndWhitespaceNamesAreNotSecret() {
        SecretMasker masker = new SecretMasker();
        assertThat(masker.isSecret("   ")).isFalse();
        assertThat(masker.isSecret("\t")).isFalse();
    }

    @Test
    void customPatternsReplaceDefaults() {
        SecretMasker masker = new SecretMasker(Set.of("custom-pattern"));
        assertThat(masker.isSecret("app.custom-pattern")).isTrue();
        // default patterns are no longer in effect
        assertThat(masker.isSecret("spring.datasource.password")).isFalse();
    }

    @Test
    void nullPatternsThrow() {
        assertThatThrownBy(() -> new SecretMasker(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void maskReturnsMaskedConstantForSecretKeys() {
        SecretMasker masker = new SecretMasker();
        assertThat(masker.mask("api.token", "abc123")).isEqualTo(SecretMasker.MASKED_VALUE);
        assertThat(masker.mask("oauth.client_secret", "shh")).isEqualTo(SecretMasker.MASKED_VALUE);
    }

    @Test
    void maskedConstantIsAFixedString() {
        // Guard against accidental change of the externally-visible masked token.
        assertThat(SecretMasker.MASKED_VALUE).isEqualTo("******");
    }
}
