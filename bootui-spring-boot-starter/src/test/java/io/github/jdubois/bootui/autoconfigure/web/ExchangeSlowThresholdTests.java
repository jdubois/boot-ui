package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ExchangeSlowThresholdTests {

    private static long resolve(MockEnvironment environment) {
        BootUiProperties properties = new BootUiProperties();
        properties.getActivity().setRequestSlowThresholdMs(750);
        return ExchangeSlowThreshold.resolve(properties, environment);
    }

    @Test
    void keepsTheThresholdWhenActuatorRecordsTimeTakenByDefault() {
        assertThat(resolve(new MockEnvironment())).isEqualTo(750L);
    }

    @Test
    void keepsTheThresholdWhenTimeTakenIsIncludedInAnyAcceptedSpelling() {
        assertThat(resolve(new MockEnvironment()
                        .withProperty(ExchangeSlowThreshold.INCLUDE_PROPERTY, "request-headers,time-taken")))
                .isEqualTo(750L);
        assertThat(resolve(new MockEnvironment().withProperty(ExchangeSlowThreshold.INCLUDE_PROPERTY, "TIME_TAKEN")))
                .isEqualTo(750L);
    }

    @Test
    void turnsSlowClassificationOffWhenTimeTakenIsNotRecorded() {
        assertThat(resolve(new MockEnvironment()
                        .withProperty(ExchangeSlowThreshold.INCLUDE_PROPERTY, "request-headers,response-headers")))
                .isZero();
    }
}
