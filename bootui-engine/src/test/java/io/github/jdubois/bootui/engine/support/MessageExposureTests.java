package io.github.jdubois.bootui.engine.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class MessageExposureTests {

    private static final String SECRETS = "login failed password=hunter2 token: tok-123 \"apiKey\": \"ak-9\"";

    @Test
    void masksSecretAssignmentsKeepingKeysUnderTheDefaultMaskedMode() {
        String masked =
                MessageExposure.current(policy(ValueExposure.MASKED, true)).apply(SECRETS);

        assertThat(masked)
                .isEqualTo("login failed password=****** token: ****** \"apiKey\": \"******\"")
                .doesNotContain("hunter2", "tok-123", "ak-9");
    }

    @Test
    void omitsTextUnderMetadataOnlyWithoutConsultingMaskSecrets() {
        AtomicInteger maskSecretsReads = new AtomicInteger();
        MessageExposure rule = MessageExposure.current(new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return ValueExposure.METADATA_ONLY;
            }

            @Override
            public boolean maskSecrets() {
                maskSecretsReads.incrementAndGet();
                return false;
            }
        });

        assertThat(rule.omitsText()).isTrue();
        assertThat(rule.apply(SECRETS)).isNull();
        assertThat(rule.apply("")).isNull();
        assertThat(maskSecretsReads).hasValue(0);
    }

    @Test
    void returnsTextVerbatimUnderFullEvenWhenMaskSecretsIsOn() {
        MessageExposure rule = MessageExposure.current(policy(ValueExposure.FULL, true));

        assertThat(rule.omitsText()).isFalse();
        assertThat(rule.apply(SECRETS)).isSameAs(SECRETS);
    }

    @Test
    void returnsTextVerbatimUnderMaskedWhenMaskSecretsIsOff() {
        MessageExposure rule = MessageExposure.current(policy(ValueExposure.MASKED, false));

        assertThat(rule.omitsText()).isFalse();
        assertThat(rule.apply(SECRETS)).isSameAs(SECRETS);
    }

    @Test
    void treatsAnUnresolvedModeAsMasked() {
        assertThat(MessageExposure.current(policy(null, true)).apply("password=hunter2"))
                .isEqualTo("password=******");
    }

    @Test
    void keepsNullAndEmptyTextAsTheyAre() {
        MessageExposure rule = MessageExposure.current(policy(ValueExposure.MASKED, true));

        assertThat(rule.apply(null)).isNull();
        assertThat(rule.apply("")).isEmpty();
        assertThat(MessageExposure.maskSecretAssignments(null)).isNull();
    }

    @Test
    void masksEveryLineOfMultiLineText() {
        String text = "first line\nclient_secret=cs-1\n\tat Foo.bar(Foo.java:1)\nAuthorization: abc123\nlast";

        assertThat(MessageExposure.maskSecretAssignments(text))
                .isEqualTo("first line\nclient_secret=******\n\tat Foo.bar(Foo.java:1)\nAuthorization: ******\nlast")
                .doesNotContain("cs-1", "abc123");
    }

    @Test
    void recognizesEveryDocumentedSecretKeyCaseInsensitively() {
        String text = "PASSWORD=a1 passwd=a2 pwd=a3 secret=a4 token=a5 api-key=a6 api_key=a7 apikey=a8 "
                + "authorization=a9 credential=b1 access-key=b2 client-secret=b3 private_key=b4";

        assertThat(MessageExposure.maskSecretAssignments(text))
                .doesNotContain("a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9", "b1", "b2", "b3", "b4");
    }

    @Test
    void masksTheCredentialAfterAnAuthorizationSchemeKeepingTheScheme() {
        assertThat(MessageExposure.maskSecretAssignments("Authorization: Bearer abc.def"))
                .isEqualTo("Authorization: Bearer ******");
    }

    @Test
    void leavesTextWithoutSecretAssignmentsUnchanged() {
        String text = "Started Application in 1.2 seconds (process running for 1.5)";

        assertThat(MessageExposure.maskSecretAssignments(text)).isSameAs(text);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Bearer",
                "bearer",
                "BEARER",
                "Basic",
                "basic",
                "BASIC",
                "Digest",
                "digest",
                "Negotiate",
                "negotiate",
                "NTLM",
                "ntlm",
                "Ntlm",
                "Token",
                "token",
                "TOKEN",
                "DPoP",
                "dpop",
                "OAuth",
                "oauth",
                "AWS4-HMAC-SHA256",
                "aws4-hmac-sha256",
                "SCRAM-SHA-1",
                "SCRAM-SHA-256",
                "HOBA",
                "Mutual",
                "vapid",
                "GNAP",
                "PrivateToken",
                "Concealed"
            })
    void masksTheCredentialAfterEveryRecognizedSchemeInAnyCase(String scheme) {
        String credential = "c9-" + Integer.toHexString(scheme.hashCode()) + "/x+Y==";

        assertThat(MessageExposure.maskSecretAssignments("Authorization: " + scheme + " " + credential))
                .isEqualTo("Authorization: " + scheme + " ******")
                .doesNotContain(credential);
    }

    @ParameterizedTest
    @MethodSource("authorizationKeyShapes")
    void masksTheCredentialInEveryCommonShapeOfAnAuthorizationKey(String text, String expected, String secret) {
        assertThat(MessageExposure.maskSecretAssignments(text))
                .isEqualTo(expected)
                .doesNotContain(secret);
    }

    static Stream<Arguments> authorizationKeyShapes() {
        return Stream.of(
                arguments("Authorization: Bearer tok-1", "Authorization: Bearer ******", "tok-1"),
                arguments("authorization=Basic dXNlcjpwYXNz", "authorization=Basic ******", "dXNlcjpwYXNz"),
                arguments(
                        "Proxy-Authorization: Negotiate YIIGhgYJKoZIhvcSAQICAQBu==",
                        "Proxy-Authorization: Negotiate ******",
                        "YIIGhg"),
                arguments("{\"authorization\": \"Bearer tok-1\"}", "{\"authorization\": \"Bearer ******\"}", "tok-1"),
                arguments("{'Authorization': 'Bearer tok-1'}", "{'Authorization': 'Bearer ******'}", "tok-1"),
                arguments(
                        "[Authorization:\"Bearer tok-1\", Accept:\"*/*\"]",
                        "[Authorization:\"Bearer ******\", Accept:\"*/*\"]",
                        "tok-1"),
                arguments(
                        "{Authorization=[Bearer tok-1], Accept=[*/*]}",
                        "{Authorization=[Bearer ******], Accept=[*/*]}",
                        "tok-1"),
                arguments("{\"authorization\":[\"Bearer tok-1\"]}", "{\"authorization\":[\"Bearer ******\"]}", "tok-1"),
                arguments(
                        "DefaultHttpHeaders[authorization: bearer tok-1, host: api]",
                        "DefaultHttpHeaders[authorization: bearer ******, host: api]",
                        "tok-1"),
                arguments("Authorization:Bearer tok-1", "Authorization:Bearer ******", "tok-1"),
                arguments(
                        "authorization = Bearer\ttok-1 (expired)", "authorization = Bearer\t****** (expired)", "tok-1"),
                arguments("-H 'Authorization: Bearer tok-1'", "-H 'Authorization: Bearer ******'", "tok-1"),
                arguments(
                        "{\"authorization\": [ \"Basic dXNlcjpwYXNz\" ]}",
                        "{\"authorization\": [ \"Basic ******\" ]}",
                        "dXNlcjpwYXNz"),
                arguments(
                        "{Authorization=[Basic dXNlcjpwYXNz, Bearer tok-1]}",
                        "{Authorization=[Basic ******, Bearer ******]}",
                        "tok-1"),
                arguments(
                        "{authorization=[\"Basic dXNlcjpwYXNz\", \"Basic dGVzdDp0ZXN0\"]}",
                        "{authorization=[\"Basic ******\", \"Basic ******\"]}",
                        "dGVzdDp0ZXN0"),
                arguments(
                        "{\\\"Authorization\\\":\\\"Basic dXNlcjpwYXNz\\\"}",
                        "{\\\"Authorization\\\":\\\"Basic ******\\\"}",
                        "dXNlcjpwYXNz"),
                arguments(
                        "{\\\"authorization\\\":\\\"Bearer tok-1\\\"}",
                        "{\\\"authorization\\\":\\\"Bearer ******\\\"}",
                        "tok-1"),
                arguments("Authorization header: Bearer tok-1", "Authorization header: Bearer ******", "tok-1"),
                arguments(
                        "authorization_header=Basic dXNlcjpwYXNz", "authorization_header=Basic ******", "dXNlcjpwYXNz"),
                arguments("Authorization: Bearer \"tok-1\"", "Authorization: Bearer \"******\"", "tok-1"),
                arguments("Authorization: Basic 'dXNlcjpwYXNz'", "Authorization: Basic '******'", "dXNlcjpwYXNz"),
                arguments(
                        "{\"message\":\"Authorization: Basic \\\"dXNlcjpwYXNz\\\"\"}",
                        "{\"message\":\"Authorization: Basic \\\"******\\\"\"}",
                        "dXNlcjpwYXNz"),
                arguments(
                        "{\"authorization\": [\n  \"Basic dXNlcjpwYXNz\"\n]}",
                        "{\"authorization\": [\n  \"Basic ******\"\n]}",
                        "dXNlcjpwYXNz"),
                arguments(
                        "[Authorization:\"Basic dXNlcjpwYXNz\", \"Basic dGVzdDp0ZXN0\"]",
                        "[Authorization:\"Basic ******\", \"Basic ******\"]",
                        "dGVzdDp0ZXN0"),
                arguments(
                        "{\"message\":\"[Authorization:\\\"Basic dXNlcjpwYXNz\\\"]\"}",
                        "{\"message\":\"[Authorization:\\\"Basic ******\\\"]\"}",
                        "dXNlcjpwYXNz"));
    }

    @ParameterizedTest
    @MethodSource("parameterListCredentials")
    void masksEveryParameterOfADigestStyleCredential(String text, String expected) {
        assertThat(MessageExposure.maskSecretAssignments(text))
                .isEqualTo(expected)
                .doesNotContain("Mufasa", "6629fae4", "tnnArxj06c", "fe5f80f77d");
    }

    static Stream<Arguments> parameterListCredentials() {
        return Stream.of(
                arguments(
                        "Authorization: Digest username=\"Mufasa\", realm=\"http-auth@example.org\", "
                                + "uri=\"/dir/index.html\", algorithm=SHA-256, "
                                + "nonce=\"7ypf/xlj9XXwfDPEoM4URrv/xwf94Bc\", "
                                + "nc=00000001, cnonce=\"f2/wE4q74E6zIJEtWaHKaf5wv/H5Qzzp\", qop=auth, "
                                + "response=\"6629fae49393a05397450978507c4ef1\", "
                                + "opaque=\"FQhe/qaU925kfnzjCev0ciny7QMk\" "
                                + "for GET /dir/index.html",
                        "Authorization: Digest ****** for GET /dir/index.html"),
                arguments(
                        "Authorization: OAuth oauth_consumer_key=\"Mufasa\", oauth_nonce=\"kYjzVBB8Y0ZFabxSWbWo\", "
                                + "oauth_signature=\"tnnArxj06cWHq44gCs1OSKk%2FjY%3D\", "
                                + "oauth_signature_method=\"HMAC-SHA1\", oauth_version=\"1.0\"",
                        "Authorization: OAuth ******"),
                arguments(
                        "Authorization: AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/iam/aws4_request, "
                                + "SignedHeaders=content-type;host;x-amz-date, "
                                + "Signature=fe5f80f77d5fa3beca038a248ff027d0445342fe2855ddc963176630326f1024",
                        "Authorization: AWS4-HMAC-SHA256 ******"),
                arguments(
                        "authorization: Token token=\"6629fae4\", nonce=\"def\"; retrying",
                        "authorization: Token ******; retrying"),
                arguments(
                        "[Authorization:\"Digest username=\"Mufasa\", response=\"6629fae4\"\", Accept:\"*/*\"]",
                        "[Authorization:\"Digest ******\", Accept:\"*/*\"]"),
                arguments(
                        "{\"authorization\":\"Digest username=\\\"Mufasa\\\", response=\\\"6629fae4\\\"\"}",
                        "{\"authorization\":\"Digest ******\"}"),
                arguments(
                        "Authorization: Digest username=\"Mufasa\", response=\"6629fae4",
                        "Authorization: Digest ******"),
                arguments(
                        "Authorization: Digest username=\"Mu\\\"fasa\", response=\"6629fae4\" sent",
                        "Authorization: Digest ****** sent"),
                arguments(
                        "{\"authorization\":\"Digest username=\\\"Mu\\\\\\\"fasa\\\", response=\\\"6629fae4\\\"\"}",
                        "{\"authorization\":\"Digest ******\"}"),
                arguments(
                        "Authorization: Digest username*=UTF-8''Mufasa%C3%A4, realm=\"x\", response=\"6629fae4\"",
                        "Authorization: Digest ******"),
                arguments(
                        "Authorization: Digest username=\"Mufasa\",\n    realm=\"x\",\n    response=\"6629fae4\"\nnext",
                        "Authorization: Digest ******\nnext"),
                arguments(
                        "Authorization: Digest " + "p=\"v\", ".repeat(100) + "response=\"6629fae4\"",
                        "Authorization: Digest ******"),
                arguments(
                        "Authorization: Digest \"username=\"Mufasa\", response=\"6629fae4\"\"",
                        "Authorization: Digest \"******\""),
                arguments(
                        "Authorization: Digest username=\\\"Mufasa\\\", response=\\\"6629fae4\\\" -> 401 for /api",
                        "Authorization: Digest ****** -> 401 for /api"),
                arguments(
                        "{\n  \"authorization\" : [\n    \"Digest username=\\\"Mufasa\\\", "
                                + "response=\\\"6629fae4\\\"\"\n  ]\n}",
                        "{\n  \"authorization\" : [\n    \"Digest ******\"\n  ]\n}"));
    }

    @ParameterizedTest
    @MethodSource("unrecognizedSchemes")
    void masksAnUnrecognizedSchemeWordTogetherWithItsCredentialAfterAnAuthorizationKey(
            String text, String expected, String secret) {
        // A custom scheme cannot be told apart from a bare credential followed by more text, so neither is shown.
        assertThat(MessageExposure.maskSecretAssignments(text))
                .isEqualTo(expected)
                .doesNotContain(secret);
    }

    static Stream<Arguments> unrecognizedSchemes() {
        return Stream.of(
                arguments("Authorization: SSWS 00QCjAl4MlV-WPXM", "Authorization: ******", "00QCjAl4MlV"),
                arguments(
                        "authorization=ApiKey dGVzdDp0ZXN0 accepted", "authorization=****** accepted", "dGVzdDp0ZXN0"),
                arguments(
                        "Proxy-Authorization: Bot MTk4NjIyNDgzNDcxOTI1MjQ4.Cl2FMQ",
                        "Proxy-Authorization: ******",
                        "MTk4NjIy"),
                arguments(
                        "Authorization: SharedAccessSignature sr=sb%3A%2F%2Fns&sig=c2lnbmF0dXJl%3D&se=1700000000",
                        "Authorization: ******", "c2lnbmF0dXJl"),
                arguments("Authorization: abc123 rejected", "Authorization: ******", "abc123"),
                arguments(
                        "Headers[Authorization=raw123 X-Token=Bearer tok999 Accept=application/json]",
                        "Headers[Authorization=****** X-Token=****** Accept=application/json]",
                        "tok999"),
                arguments("Authorization: raw123 X-Api-Key: k1-9", "Authorization: ****** X-Api-Key: ******", "k1-9"),
                arguments("Authorization: SSWS \"00QCjAl4MlV\"", "Authorization: ******\"", "00QCjAl4MlV"));
    }

    @ParameterizedTest
    @MethodSource("multiValuedHeaders")
    void masksEveryValueOfAMultiValuedAuthorizationHeader(String text, String expected, String secret) {
        assertThat(MessageExposure.maskSecretAssignments(text))
                .isEqualTo(expected)
                .doesNotContain(secret);
    }

    static Stream<Arguments> multiValuedHeaders() {
        return Stream.of(
                arguments(
                        "{Authorization=[Bearer tok-1, SSWS 00QCjAl4MlV]}",
                        "{Authorization=[Bearer ******, ******]}",
                        "00QCjAl4MlV"),
                arguments(
                        "{Authorization=[SSWS 00QCjAl4MlV, SSWS 11QCjAl4MlV]}",
                        "{Authorization=[******, ******]}",
                        "11QCjAl4MlV"),
                arguments("{authorization=[raw-1, raw-2]}", "{authorization=[******, ******]}", "raw-2"),
                arguments(
                        "[Authorization:\"SSWS 00QCjAl4MlV\", \"SSWS 11QCjAl4MlV\"]",
                        "[Authorization:\"******\", \"******\"]",
                        "11QCjAl4MlV"),
                arguments(
                        "{\"authorization\": [\"Basic dXNlcjpwYXNz\", \"ApiKey ak-2\"]}",
                        "{\"authorization\": [\"Basic ******\", \"******\"]}",
                        "ak-2"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"authorization\": \"Bearer ******\", \"accept\": \"application/json\"}",
                "{'Authorization': 'Basic ******', 'Host': 'api.example.com'}",
                "[Authorization:\"Bearer ******\", Accept:\"*/*\"]",
                "{Authorization=[Bearer ******], Accept=[*/*]}",
                "Authorization: Bearer ******, retrying the request"
            })
    void leavesTextAfterTheLastAuthorizationValueAlone(String masked) {
        String original = masked.replace("******", "tok-1");

        assertThat(MessageExposure.maskSecretAssignments(original)).isEqualTo(masked);
    }

    @ParameterizedTest
    @MethodSource("schemesAfterOtherSecretKeys")
    void masksASchemeTogetherWithItsCredentialAfterAnyOtherSecretKey(String text, String expected) {
        // The scheme is masked too, so a password that happens to be a scheme name is never shown.
        assertThat(MessageExposure.maskSecretAssignments(text)).isEqualTo(expected);
    }

    static Stream<Arguments> schemesAfterOtherSecretKeys() {
        return Stream.of(
                arguments("X-Auth-Token: Bearer tok-1", "X-Auth-Token: ******"),
                arguments("token=Bearer tok-1 retry", "token=****** retry"),
                arguments("password=Basic login failed", "password=****** failed"),
                arguments("secret: oauth rotation failed", "secret: ****** failed"),
                arguments("api_key: Digest this later please", "api_key: ****** later please"),
                arguments("{\"apiKey\": \"Token ak-1\"}", "{\"apiKey\": \"******\"}"));
    }

    @Test
    void masksAPaddedCredentialThatEndsLikeASecretKey() {
        assertThat(MessageExposure.maskSecretAssignments("Authorization: Bearer c2VjcmV0cwd="))
                .isEqualTo("Authorization: Bearer ******");
        assertThat(MessageExposure.maskSecretAssignments("Authorization: Basic dXNlcjpwYXNzd29yZA=="))
                .isEqualTo("Authorization: Basic ******");
    }

    @Test
    void keepsMaskingTheFirstValueTokenWhenNoCredentialFollowsTheScheme() {
        assertThat(MessageExposure.maskSecretAssignments("Authorization: Bearer"))
                .isEqualTo("Authorization: ******");
        assertThat(MessageExposure.maskSecretAssignments("Authorization: Bearer "))
                .isEqualTo("Authorization: ****** ");
        assertThat(MessageExposure.maskSecretAssignments("Authorization: Bearer\nnext line"))
                .isEqualTo("Authorization: ******\nnext line");
        assertThat(MessageExposure.maskSecretAssignments("Authorization: Bearer, retrying"))
                .isEqualTo("Authorization: ******, retrying");
        assertThat(MessageExposure.maskSecretAssignments("Authorization: basic-4"))
                .isEqualTo("Authorization: ******");
    }

    @ParameterizedTest
    @MethodSource("bareSchemeCredentials")
    void masksACredentialShapedValueAfterABareScheme(String text, String expected, String secret) {
        assertThat(MessageExposure.maskSecretAssignments(text))
                .isEqualTo(expected)
                .doesNotContain(secret);
    }

    static Stream<Arguments> bareSchemeCredentials() {
        return Stream.of(
                arguments("sent Basic dXNlcjpwYXNzd29yZA== upstream", "sent Basic ****** upstream", "dXNlcjpwYXNz"),
                arguments("upstream rejected Basic dXNlcjpwYXNz.", "upstream rejected Basic ******.", "dXNlcjpwYXNz"),
                arguments(
                        "Authorization: is Basic dXNlcjpwYXNzd29yZA==",
                        "Authorization: ****** Basic ******",
                        "dXNlcjpwYXNz"),
                arguments(
                        "\"Authorization\" => \"Basic dXNlcjpwYXNzd29yZA==\"",
                        "\"Authorization\" =****** \"Basic ******\"",
                        "dXNlcjpwYXNz"),
                arguments(
                        "Received Negotiate Header for http://localhost:8080/api: Negotiate YIIGhgYJKoZIhvcSAQICAQBu==",
                        "Received Negotiate Header for http://localhost:8080/api: Negotiate ******",
                        "YIIGhg"),
                arguments(
                        "NTLM TlRMTVNTUAABAAAAB4IIogAAAAAAAAAAAAAAAAAAAAAGAbEdAAAADw== received",
                        "NTLM ****** received",
                        "TlRMTVNT"),
                arguments(
                        "sending Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln to api",
                        "sending Bearer ****** to api",
                        "eyJhbGci"),
                arguments("retry with bearer 0123abcd", "retry with bearer ******", "0123abcd"),
                arguments("sending Bearer \"eyJhbGciOiJIUzI1NiJ9.e30.c2ln\"", "sending Bearer \"******\"", "eyJhbGci"),
                arguments("BEARER abcdefghijklmnopqrst", "BEARER ******", "abcdefghijklmnopqrst"),
                arguments(
                        "Bearer ghp_16C7e42F292c6912E7710c838347Ae178B4a, attempt 2",
                        "Bearer ******, attempt 2",
                        "ghp_16C7e42F"),
                arguments("token was Bearer dGVzdC10b2tlbi0xMjM0NQ==.", "token was Bearer ******.", "dGVzdC10b2tlbi0x"),
                arguments(
                        "{\\\"Authorization\\\":\\\"Bearer eyJhbGciOiJIUzI1NiJ9.e30.c2ln\\\"}",
                        "{\\\"Authorization\\\":\\\"Bearer ******\\\"}",
                        "eyJhbGci"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Missing Bearer token.",
                "Bearer authentication failed for /api",
                "WWW-Authenticate: Bearer realm=\"api\", error=\"invalid_token\"",
                "Expected Bearer <token> in the Authorization header",
                "Bearer abc123",
                "OAuth2Bearer 0123456789abcdef",
                "token_type=bearer",
                "Basic auth is enabled",
                "Basic settings",
                "Basic YWJjZA== is not a user:password pair",
                "NTLM authentication failed",
                "Negotiate failed for user alice",
                "unable to negotiate TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 with peer",
                "failed to negotiate 10.0.0.12:5432",
                "NTLM dXNlcjpwYXNzd29yZGRkZGRkZA== is not an NTLM message"
            })
    void leavesProseAboutAuthorizationSchemesAlone(String text) {
        assertThat(MessageExposure.maskSecretAssignments(text)).isSameAs(text);
    }

    @Test
    void masksAuthorizationCredentialsOnEveryLineOfMultiLineText() {
        String text = "GET /api/orders\nAuthorization: Bearer tok-1\r\nProxy-Authorization: Basic dXNlcjpwYXNz\n"
                + "password=pw-2\nretried with Bearer eyJhbGciOiJIUzI1NiJ9.e30.c2ln\n\tat Foo.bar(Foo.java:1)";

        assertThat(MessageExposure.maskSecretAssignments(text))
                .isEqualTo("GET /api/orders\nAuthorization: Bearer ******\r\nProxy-Authorization: Basic ******\n"
                        + "password=******\nretried with Bearer ******\n\tat Foo.bar(Foo.java:1)")
                .doesNotContain("tok-1", "dXNlcjpwYXNz", "pw-2", "eyJhbGci");
    }

    @Test
    void masksAuthorizationCredentialsOnlyUnderTheMaskedMode() {
        String text = "Authorization: Bearer tok-1, then Bearer eyJhbGciOiJIUzI1NiJ9.e30.c2ln";

        assertThat(MessageExposure.current(policy(ValueExposure.MASKED, true)).apply(text))
                .isEqualTo("Authorization: Bearer ******, then Bearer ******");
        assertThat(MessageExposure.current(policy(null, true)).apply(text))
                .isEqualTo("Authorization: Bearer ******, then Bearer ******");
        assertThat(MessageExposure.current(policy(ValueExposure.FULL, true)).apply(text))
                .isSameAs(text);
        assertThat(MessageExposure.current(policy(ValueExposure.MASKED, false)).apply(text))
                .isSameAs(text);
        assertThat(MessageExposure.current(policy(ValueExposure.METADATA_ONLY, true))
                        .apply(text))
                .isNull();
    }

    @Test
    void masksEveryOtherAssignmentExactlyAsThePreviousRuleDid() {
        // Everything but a value that starts with a scheme or an authorization value followed by more text on its line.
        Pattern previous = Pattern.compile(
                "(?i)([\"']?(?:password|passwd|pwd|secret|token|api[-_]?key|apikey|authorization|credential|"
                        + "access[-_]?key|client[-_]?secret|private[-_]?key)[\"']?\\s*[=:]\\s*[\"']?)([^\\s\"',;&)]+)");
        List<String> corpus = List.of(
                SECRETS,
                "first line\nclient_secret=cs-1\n\tat Foo.bar(Foo.java:1)\nAuthorization: abc123\nlast",
                "{\"apiKey\": \"ak-1\", 'client_secret':'cs-2', \"token\":\"t-3\"}",
                "Access-Key = ak5, private_key=pk6; pwd=(x) passwd:  p7&next=1",
                "password: correct horse battery staple",
                "token: tok-1 retry token=tok-2,token=tok-3",
                "authorization=abc123\nAUTHORIZATION:xyz\nauthorization: 'q-1'",
                "multi\nline token: tok-3\nAuthorization: basic-4",
                "Authorization: Bearer\nnext line",
                "Authorization: Bearer, retrying",
                "credential=c1 x-api-key: k2 apikey=k3 access_key=k4 client-secret=k5",
                "Started Application in 1.2 seconds (process running for 1.5)",
                "");

        for (String text : corpus) {
            assertThat(MessageExposure.maskSecretAssignments(text))
                    .as(text)
                    .isEqualTo(previous.matcher(text).replaceAll(result -> result.group(1) + "******"));
        }
    }

    @Test
    void masksAdversarialInputInLinearTime() {
        int size = 500_000;
        List<String> inputs = List.of(
                "Authorization: Digest " + "a=b, ".repeat(size / 5),
                "Authorization: Digest a=b" + ",a=".repeat(size / 3),
                "authorization: " + "bearer ".repeat(size / 7),
                "Bearer ".repeat(size / 7),
                ("bearer" + " ".repeat(1_000)).repeat(size / 1_006),
                "authorization: basic" + " ".repeat(size) + ",",
                "Authorization: Digest a=\"" + "x".repeat(size),
                "authorization: digest a=\"".repeat(size / 24),
                "Authorization: " + "x ".repeat(size / 2),
                "authorization" + " ".repeat(size) + "x",
                "password password=".repeat(size / 18),
                "Authorization: Digest a=\"" + "\\\"".repeat(size / 2),
                "{Authorization=[Basic abc" + ", Basic abc".repeat(size / 11) + "]}",
                "Basic abcd ".repeat(size / 11),
                "Bearer bearer ".repeat(size / 14),
                "authorization header: ".repeat(size / 22),
                "Authorization: Digest a*=" + "x'".repeat(size / 2),
                "Authorization: Bearer " + "tokenx".repeat(size / 6));

        for (String input : inputs) {
            assertTimeoutPreemptively(
                    Duration.ofSeconds(10),
                    () -> MessageExposure.maskSecretAssignments(input),
                    () -> "masking " + input.substring(0, 40) + "... took too long");
        }
        assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                () -> assertThat(MessageExposure.maskSecretAssignments(
                                "Authorization: Bearer " + "a".repeat(size) + "=="))
                        .isEqualTo("Authorization: Bearer ******"));
        assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                () -> assertThat(MessageExposure.maskSecretAssignments(
                                "Authorization: Digest " + "p=\"v\", ".repeat(size / 8) + "response=\"6629fae4\""))
                        .isEqualTo("Authorization: Digest ******"));
        assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                () -> assertThat(MessageExposure.maskSecretAssignments("sent Bearer " + "a".repeat(size)))
                        .isEqualTo("sent Bearer ******"));
    }

    private static ExposurePolicy policy(ValueExposure exposure, boolean maskSecrets) {
        return new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return exposure;
            }

            @Override
            public boolean maskSecrets() {
                return maskSecrets;
            }
        };
    }
}
