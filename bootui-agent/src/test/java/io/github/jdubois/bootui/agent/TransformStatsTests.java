package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import net.bytebuddy.agent.builder.AgentBuilder;
import org.junit.jupiter.api.Test;

class TransformStatsTests {

    @Test
    void configuringATransformerFirstDefinesWhatTheFirstTransformationLoads() {
        new TransformStats().configure(new AgentBuilder.Default());

        assertThat(TransformStats.transformationWarmedUp())
                .as("the throwaway transformer applied its advice to a generated class")
                .isTrue();
    }
}
