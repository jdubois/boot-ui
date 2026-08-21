package io.github.jdubois.bootui.engine.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import org.junit.jupiter.api.Test;

class UriMaskingTests {

    @Test
    void removesAuthorityUserInfoButKeepsHostAndPort() {
        assertThat(UriMasking.maskUserInfo("https://alice:s3cr3t@api.example.com:8443/orders"))
                .isEqualTo("https://" + SecretMasker.MASKED_VALUE + "@api.example.com:8443/orders");
    }

    @Test
    void removesUserInfoThatContainsNoPassword() {
        assertThat(UriMasking.maskUserInfo("https://alice@api.example.com/orders"))
                .isEqualTo("https://" + SecretMasker.MASKED_VALUE + "@api.example.com/orders");
    }

    @Test
    void removesUserInfoFromIpv6AndProtocolRelativeAuthorities() {
        assertThat(UriMasking.maskUserInfo("http://alice:s3cr3t@[::1]:8080/x"))
                .isEqualTo("http://" + SecretMasker.MASKED_VALUE + "@[::1]:8080/x");
        assertThat(UriMasking.maskUserInfo("//alice:s3cr3t@api.example.com/x"))
                .isEqualTo("//" + SecretMasker.MASKED_VALUE + "@api.example.com/x");
    }

    @Test
    void removesUserInfoWhenTheAuthorityIsFollowedDirectlyByAQueryOrFragment() {
        assertThat(UriMasking.maskUserInfo("https://alice:s3cr3t@api.example.com?page=2"))
                .isEqualTo("https://" + SecretMasker.MASKED_VALUE + "@api.example.com?page=2");
        assertThat(UriMasking.maskUserInfo("https://alice:s3cr3t@api.example.com#top"))
                .isEqualTo("https://" + SecretMasker.MASKED_VALUE + "@api.example.com#top");
    }

    @Test
    void leavesOrdinaryUrisUntouched() {
        assertThat(UriMasking.maskUserInfo("https://api.example.com/orders?page=2#top"))
                .isEqualTo("https://api.example.com/orders?page=2#top");
        assertThat(UriMasking.maskUserInfo("/orders/42")).isEqualTo("/orders/42");
        assertThat(UriMasking.maskUserInfo("")).isEmpty();
        assertThat(UriMasking.maskUserInfo(null)).isNull();
    }

    @Test
    void doesNotMistakeAnAtSignInThePathForUserInfo() {
        assertThat(UriMasking.maskUserInfo("https://api.example.com/users/alice@example.com"))
                .isEqualTo("https://api.example.com/users/alice@example.com");
        assertThat(UriMasking.maskUserInfo("/a//alice@example.com/b")).isEqualTo("/a//alice@example.com/b");
    }

    @Test
    void masksSensitiveQueryValuesByNameAndKeepsTheRest() {
        assertThat(UriMasking.maskQueryString("apiKey=s3cr3t&page=2", true, ValueExposure.MASKED))
                .isEqualTo("apiKey=" + SecretMasker.MASKED_VALUE + "&page=2");
    }

    @Test
    void masksPercentEncodedSensitiveQueryNames() {
        assertThat(UriMasking.maskQueryString("%70assword=s3cr3t&page=2", true, ValueExposure.MASKED))
                .isEqualTo("%70assword=" + SecretMasker.MASKED_VALUE + "&page=2");
        assertThat(UriMasking.maskQueryString("api%2Dkey=s3cr3t", true, ValueExposure.MASKED))
                .isEqualTo("api%2Dkey=" + SecretMasker.MASKED_VALUE);
    }

    @Test
    void masksAValuelessSensitiveParameterWholesale() {
        assertThat(UriMasking.maskQueryString("access_token&page=2", true, ValueExposure.MASKED))
                .isEqualTo(SecretMasker.MASKED_VALUE + "&page=2");
        assertThat(UriMasking.maskQueryString("verbose&page=2", true, ValueExposure.MASKED))
                .isEqualTo("verbose&page=2");
    }

    @Test
    void keepsEmptyParametersAndPreservesParameterOrder() {
        assertThat(UriMasking.maskQueryString("a=1&&b=2", true, ValueExposure.MASKED))
                .isEqualTo("a=1&&b=2");
        assertThat(UriMasking.maskQueryString("", true, ValueExposure.MASKED)).isEmpty();
        assertThat(UriMasking.maskQueryString(null, true, ValueExposure.MASKED)).isNull();
    }

    @Test
    void honorsTheExposurePolicy() {
        assertThat(UriMasking.maskQueryString("apiKey=s3cr3t", false, ValueExposure.MASKED))
                .isEqualTo("apiKey=s3cr3t");
        assertThat(UriMasking.maskQueryString("apiKey=s3cr3t", true, ValueExposure.FULL))
                .isEqualTo("apiKey=s3cr3t");
        assertThat(UriMasking.maskQueryString("apiKey=s3cr3t", true, ValueExposure.METADATA_ONLY))
                .isNull();
    }

    @Test
    void masksUserInfoQueryAndFragmentTogether() {
        String masked = UriMasking.maskUri(
                "https://alice:s3cr3t@api.example.com/orders?apiKey=abc&page=2#access_token=xyz",
                true,
                ValueExposure.MASKED);
        assertThat(masked)
                .isEqualTo("https://" + SecretMasker.MASKED_VALUE + "@api.example.com/orders?apiKey="
                        + SecretMasker.MASKED_VALUE + "&page=2#access_token=" + SecretMasker.MASKED_VALUE);
    }

    @Test
    void keepsUserInfoMaskedEvenUnderFullExposure() {
        assertThat(UriMasking.maskUri(
                        "https://alice:s3cr3t@api.example.com/orders?apiKey=abc", true, ValueExposure.FULL))
                .isEqualTo("https://" + SecretMasker.MASKED_VALUE + "@api.example.com/orders?apiKey=abc");
        assertThat(UriMasking.maskUri("https://alice:s3cr3t@api.example.com/orders", false, ValueExposure.FULL))
                .isEqualTo("https://" + SecretMasker.MASKED_VALUE + "@api.example.com/orders");
    }

    @Test
    void dropsQueryAndFragmentUnderMetadataOnlyExposure() {
        assertThat(UriMasking.maskUri(
                        "https://api.example.com/orders?page=2#section", true, ValueExposure.METADATA_ONLY))
                .isEqualTo("https://api.example.com/orders");
    }

    @Test
    void keepsANonSensitiveFragment() {
        assertThat(UriMasking.maskUri("https://api.example.com/docs#installation", true, ValueExposure.MASKED))
                .isEqualTo("https://api.example.com/docs#installation");
    }

    @Test
    void treatsEverythingAfterTheFirstHashAsFragmentAndStillMasksSecretsInIt() {
        assertThat(UriMasking.maskUri("https://api.example.com/docs#a?token=abc", true, ValueExposure.MASKED))
                .isEqualTo("https://api.example.com/docs#a?token=" + SecretMasker.MASKED_VALUE);
        assertThat(UriMasking.maskUri("https://api.example.com/docs#a?page=2", true, ValueExposure.MASKED))
                .isEqualTo("https://api.example.com/docs#a?page=2");
    }

    @Test
    void leavesOrdinaryAndRelativeUrisUnchanged() {
        assertThat(UriMasking.maskUri("https://api.example.com/orders", true, ValueExposure.MASKED))
                .isEqualTo("https://api.example.com/orders");
        assertThat(UriMasking.maskUri("/orders?page=2", true, ValueExposure.MASKED))
                .isEqualTo("/orders?page=2");
        assertThat(UriMasking.maskUri("https://api.example.com/orders?", true, ValueExposure.MASKED))
                .isEqualTo("https://api.example.com/orders?");
        assertThat(UriMasking.maskUri(null, true, ValueExposure.MASKED)).isNull();
    }

    @Test
    void masksATruncatedUriWithoutFailing() {
        assertThat(UriMasking.maskUri(
                        "https://alice:s3cr3t@api.example.com/orders?apiKey=abc…", true, ValueExposure.MASKED))
                .isEqualTo("https://" + SecretMasker.MASKED_VALUE + "@api.example.com/orders?apiKey="
                        + SecretMasker.MASKED_VALUE);
    }

    @Test
    void masksParametersSeparatedByTheLegacySemicolon() {
        assertThat(UriMasking.maskUri(
                        "https://api.example.com/orders?page=2;token=qsecret", true, ValueExposure.MASKED))
                .isEqualTo("https://api.example.com/orders?page=2;token=" + SecretMasker.MASKED_VALUE);
    }

    @Test
    void preservesTheOriginalParameterSeparators() {
        assertThat(UriMasking.maskQueryString("a=1;b=2&c=3", true, ValueExposure.MASKED))
                .isEqualTo("a=1;b=2&c=3");
    }

    @Test
    void masksSensitiveMatrixParametersInThePath() {
        assertThat(UriMasking.maskUri(
                        "https://api.example.com/orders;token=pathsecret/42?page=2", true, ValueExposure.MASKED))
                .isEqualTo("https://api.example.com/orders;token=" + SecretMasker.MASKED_VALUE + "/42?page=2");
    }

    @Test
    void masksSensitiveMatrixParametersEvenUnderMetadataOnlyExposure() {
        assertThat(UriMasking.maskUri(
                        "https://api.example.com/orders;token=pathsecret?page=2", true, ValueExposure.METADATA_ONLY))
                .isEqualTo("https://api.example.com/orders;token=" + SecretMasker.MASKED_VALUE);
    }

    @Test
    void doesNotMistakeAHostPortForAMatrixParameter() {
        assertThat(UriMasking.maskUri("https://api.example.com:8443/orders", true, ValueExposure.MASKED))
                .isEqualTo("https://api.example.com:8443/orders");
    }

    @Test
    void masksPathMatrixParametersThroughMaskPath() {
        assertThat(UriMasking.maskPath("/orders;token=abc/42", true, ValueExposure.MASKED))
                .isEqualTo("/orders;token=" + SecretMasker.MASKED_VALUE + "/42");
        assertThat(UriMasking.maskPath("/orders/42", true, ValueExposure.MASKED))
                .isEqualTo("/orders/42");
        assertThat(UriMasking.maskPath("/orders;token=abc", true, ValueExposure.FULL))
                .isEqualTo("/orders;token=abc");
        assertThat(UriMasking.maskPath(null, true, ValueExposure.MASKED)).isNull();
    }

    @Test
    void masksAParameterValueCarryingANestedEncodedCredentialUrl() {
        assertThat(UriMasking.maskUri(
                        "https://api.example.com/go?redirect=https%3A%2F%2Fevil%2Fcb%3Faccess_token%3Dnestedsecret",
                        true, ValueExposure.MASKED))
                .isEqualTo("https://api.example.com/go?redirect=" + SecretMasker.MASKED_VALUE);
    }

    @Test
    void masksAParameterValueCarryingNestedUserInfo() {
        assertThat(UriMasking.maskUri(
                        "https://api.example.com/go?next=https://alice:s3cr3t@evil/cb", true, ValueExposure.MASKED))
                .isEqualTo("https://api.example.com/go?next=" + SecretMasker.MASKED_VALUE);
    }

    @Test
    void masksNestedCredentialsEvenUnderFullExposure() {
        assertThat(UriMasking.maskUri(
                        "https://api.example.com/go?next=https://alice:s3cr3t@evil/cb", true, ValueExposure.FULL))
                .isEqualTo("https://api.example.com/go?next=" + SecretMasker.MASKED_VALUE);
    }

    @Test
    void leavesAnOrdinaryNestedRedirectUrlVisible() {
        assertThat(UriMasking.maskUri(
                        "https://api.example.com/go?redirect=https%3A%2F%2Fexample.com%2Fhome",
                        true, ValueExposure.MASKED))
                .isEqualTo("https://api.example.com/go?redirect=https%3A%2F%2Fexample.com%2Fhome");
    }
}
