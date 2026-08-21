package io.github.jdubois.bootui.engine.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CredentialRedactionTests {

    @Test
    void redactsUserAndPasswordEmbeddedInAConnectionUrl() {
        String redacted = CredentialRedaction.redact("jdbc:postgresql://app:sup3rs3cret@db.internal:5432/orders");
        assertThat(redacted).isEqualTo("jdbc:postgresql://******@db.internal:5432/orders");
    }

    @Test
    void redactsCredentialQueryParametersInBothCommonSeparatorStyles() {
        assertThat(CredentialRedaction.redact("jdbc:mysql://db/app?user=root&password=hunter2&useSsl=true"))
                .isEqualTo("jdbc:mysql://db/app?user=******&password=******&useSsl=true");
        assertThat(CredentialRedaction.redact("jdbc:sqlserver://db;user=sa;password=hunter2;encrypt=true"))
                .isEqualTo("jdbc:sqlserver://db;user=******;password=******;encrypt=true");
    }

    @Test
    void redactsCredentialsInsideADriverErrorMessage() {
        String message = "FATAL: password authentication failed while opening "
                + "jdbc:postgresql://reporting:s3cr3t@10.0.0.4:5432/warehouse";
        assertThat(CredentialRedaction.redact(message)).doesNotContain("s3cr3t").contains("******@10.0.0.4");
    }

    @Test
    void leavesTextWithoutCredentialsUnchanged() {
        String message = "relation \"orders\" does not exist";
        assertThat(CredentialRedaction.redact(message)).isEqualTo(message);
        assertThat(CredentialRedaction.redact(null)).isNull();
        assertThat(CredentialRedaction.redact("")).isEmpty();
    }

    @Test
    void redactMessageAlsoMasksSensitiveQueryValuesByName() {
        String message = "I/O error on GET request for \"https://api.example.com/orders"
                + "?apiKey=verysecretvalue&page=2\": Connection refused";
        assertThat(CredentialRedaction.redactMessage(message))
                .doesNotContain("verysecretvalue")
                .contains("apiKey=******")
                .contains("page=2")
                .contains("Connection refused");
    }

    @Test
    void redactMessageMasksPercentEncodedSensitiveQueryNames() {
        assertThat(CredentialRedaction.redactMessage("failed: https://api/x?%70assword=hunter2&page=2"))
                .isEqualTo("failed: https://api/x?%70assword=******&page=2");
    }

    @Test
    void redactMessageMasksSeveralParametersInOneMessage() {
        assertThat(CredentialRedaction.redactMessage("GET https://api/x?token=a&page=2&client_secret=b failed"))
                .isEqualTo("GET https://api/x?token=******&page=2&client_secret=****** failed");
    }

    @Test
    void redactMessageStillRedactsUrlCredentials() {
        assertThat(CredentialRedaction.redactMessage("Connect to https://alice:hunter2@api.example.com failed"))
                .isEqualTo("Connect to https://******@api.example.com failed");
    }

    @Test
    void redactMessageLeavesOrdinaryTextUnchanged() {
        String message = "Connection refused: connect to api.example.com:443?page=2";
        assertThat(CredentialRedaction.redactMessage(message)).isEqualTo(message);
        assertThat(CredentialRedaction.redactMessage(null)).isNull();
        assertThat(CredentialRedaction.redactMessage("")).isEmpty();
    }

    @Test
    void redactMessageRemovesPercentEncodedUserInfo() {
        assertThat(CredentialRedaction.redactMessage("failed https://alice%3As3cr3t@host/x"))
                .isEqualTo("failed https://******@host/x");
    }

    @Test
    void redactMessageRemovesUserInfoFromASchemeRelativeAuthority() {
        assertThat(CredentialRedaction.redactMessage("failed //alice:s3cr3t@host/x"))
                .isEqualTo("failed //******@host/x");
    }

    @Test
    void redactMessageMasksSensitiveFragmentParameters() {
        assertThat(CredentialRedaction.redactMessage("failed https://host/x#access_token=fragsecret"))
                .isEqualTo("failed https://host/x#access_token=******");
    }

    @Test
    void redactMessageMasksAQueryParameterFollowedByAFragment() {
        assertThat(CredentialRedaction.redactMessage("failed https://host/x?token=a#state=b"))
                .isEqualTo("failed https://host/x?token=******#state=b");
    }

    @Test
    void redactMessageDoesNotMistakeAnEmailAddressInAPathForUserInfo() {
        String message = "404 for https://host/users/alice@example.com";
        assertThat(CredentialRedaction.redactMessage(message)).isEqualTo(message);
    }
}
