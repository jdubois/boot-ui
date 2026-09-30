package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

class QuarkusExposurePolicyTest {

    @Test
    void failsClosedWhenExposureSettingsAreMissing() {
        QuarkusExposurePolicy policy = new QuarkusExposurePolicy(StubConfig.empty());

        assertThat(policy.valueExposure()).isEqualTo(ValueExposure.MASKED);
        assertThat(policy.maskSecrets()).isTrue();
    }

    @Test
    void readsLiveExposureSettings() {
        StubConfig config = new StubConfig(Map.of(
                QuarkusExposurePolicy.EXPOSE_VALUES_KEY, " full ",
                QuarkusExposurePolicy.MASK_SECRETS_KEY, "false"));
        QuarkusExposurePolicy policy = new QuarkusExposurePolicy(config);

        assertThat(policy.valueExposure()).isEqualTo(ValueExposure.FULL);
        assertThat(policy.maskSecrets()).isFalse();
    }

    @Test
    void failsClosedForBlankOrInvalidExposureModes() {
        assertThat(new QuarkusExposurePolicy(new StubConfig(Map.of(QuarkusExposurePolicy.EXPOSE_VALUES_KEY, "  ")))
                        .valueExposure())
                .isEqualTo(ValueExposure.MASKED);
        assertThat(new QuarkusExposurePolicy(new StubConfig(Map.of(QuarkusExposurePolicy.EXPOSE_VALUES_KEY, "invalid")))
                        .valueExposure())
                .isEqualTo(ValueExposure.MASKED);
    }

    @Test
    void reportsAnInvalidValueOnceUntilItParsesAgain() {
        Map<String, String> values = new HashMap<>(Map.of(QuarkusExposurePolicy.EXPOSE_VALUES_KEY, "metadata-onyl"));
        Config liveConfig = mock(Config.class);
        when(liveConfig.getOptionalValue(QuarkusExposurePolicy.EXPOSE_VALUES_KEY, String.class))
                .thenAnswer(ignored -> Optional.ofNullable(values.get(QuarkusExposurePolicy.EXPOSE_VALUES_KEY)));
        List<String> warnings = new ArrayList<>();
        QuarkusExposurePolicy policy = new QuarkusExposurePolicy(liveConfig, warnings::add);

        for (int read = 0; read < 5; read++) {
            assertThat(policy.valueExposure()).isEqualTo(ValueExposure.MASKED);
        }
        assertThat(warnings).singleElement().asString().contains("metadata-onyl");

        values.put(QuarkusExposurePolicy.EXPOSE_VALUES_KEY, "full");
        assertThat(policy.valueExposure()).isEqualTo(ValueExposure.FULL);
        values.put(QuarkusExposurePolicy.EXPOSE_VALUES_KEY, "still-invalid");
        assertThat(policy.valueExposure()).isEqualTo(ValueExposure.MASKED);
        assertThat(warnings).hasSize(2);
    }

    @Test
    void acceptsTheDashedMetadataOnlyFormLikeSpringRelaxedBinding() {
        assertThat(new QuarkusExposurePolicy(
                                new StubConfig(Map.of(QuarkusExposurePolicy.EXPOSE_VALUES_KEY, "metadata-only")))
                        .valueExposure())
                .isEqualTo(ValueExposure.METADATA_ONLY);
    }

    @Test
    void failsClosedAndWarnsOnceForAnUnrecognizedMaskSecretsValue() {
        List<String> warnings = new ArrayList<>();
        QuarkusExposurePolicy policy = new QuarkusExposurePolicy(
                new StubConfig(Map.of(QuarkusExposurePolicy.MASK_SECRETS_KEY, "maybe")), warnings::add);

        for (int read = 0; read < 5; read++) {
            assertThat(policy.maskSecrets()).isTrue();
        }
        assertThat(warnings).singleElement().asString().contains("maybe");
    }

    @Test
    void acceptsCommonBooleanSpellingsForMaskSecrets() {
        for (String on : List.of("true", " TRUE ", "yes", "on", "1")) {
            assertThat(new QuarkusExposurePolicy(new StubConfig(Map.of(QuarkusExposurePolicy.MASK_SECRETS_KEY, on)))
                            .maskSecrets())
                    .as(on)
                    .isTrue();
        }
        for (String off : List.of("false", "False", "no", "off", "0")) {
            assertThat(new QuarkusExposurePolicy(new StubConfig(Map.of(QuarkusExposurePolicy.MASK_SECRETS_KEY, off)))
                            .maskSecrets())
                    .as(off)
                    .isFalse();
        }
    }

    @Test
    void leavesTheInvalidValueWarningToAReadOutsideALogTailDeliveryThread() throws Exception {
        List<String> warnings = new ArrayList<>();
        QuarkusExposurePolicy policy = new QuarkusExposurePolicy(
                new StubConfig(Map.of(QuarkusExposurePolicy.MASK_SECRETS_KEY, "maybe")), warnings::add);

        Thread delivery =
                LogTailBuffer.deliveryThreadFactory("bootui-log-tail-test-").newThread(policy::maskSecrets);
        delivery.start();
        delivery.join(5_000);
        assertThat(warnings).isEmpty();

        assertThat(policy.maskSecrets()).isTrue();
        assertThat(warnings).hasSize(1);
    }
}
