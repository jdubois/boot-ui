package io.github.jdubois.bootui.engine.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

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
    void leavesTextWithoutSecretAssignmentsUnchanged() {
        String text = "Started Application in 1.2 seconds (process running for 1.5)";

        assertThat(MessageExposure.maskSecretAssignments(text)).isSameAs(text);
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
