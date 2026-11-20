package io.github.jdubois.bootui.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

class BootUiEngineProducerAdvisorConfigTest {

    @Test
    void memoryHistogramConfigurationMatchesSpringWithoutCollecting() {
        var threads = mock(io.github.jdubois.bootui.engine.threads.ThreadDumpService.class);
        var producer = new BootUiEngineProducer();
        assertThat(producer.memoryScanner(threads, StubConfig.empty())
                        .initialReport()
                        .scan()
                        .includeHistogram())
                .isTrue();
        var configured = new StubConfig(Map.of("bootui.memory.include-histogram", "false"));
        assertThat(producer.memoryScanner(threads, configured)
                        .initialReport()
                        .scan()
                        .includeHistogram())
                .isFalse();
        org.mockito.Mockito.verifyNoInteractions(threads);
    }

    @Test
    void retentionDefaultsAndInvalidValuesAreRejectedBeforeScanning() {
        assertThat(BootUiEngineProducer.advisorRetentionLimit(StubConfig.empty()))
                .isEqualTo(10_000);
        for (String invalid : new String[] {"0", "-1", "1.5", "2147483648", ""}) {
            Config config = new StubConfig(Map.of("bootui.advisors.max-retained-violations", invalid));
            assertThatThrownBy(() -> new BootUiEngineProducer().quarkusAppScanner(config))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> new BootUiEngineProducer().validateAdvisorRetention(null, config))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void nativeScannerReadsLiveRetentionAndFreezesItAtScanStart() {
        AtomicInteger retention = new AtomicInteger(12);
        Config config = mock(Config.class);
        when(config.getOptionalValue(eq("bootui.advisors.max-retained-violations"), eq(Integer.class)))
                .thenAnswer(ignored -> Optional.of(retention.get()));
        var scanner = new BootUiEngineProducer().quarkusAppScanner(config);
        var first = scanner.scan();
        assertThat(first.violationDetails().retentionLimit()).isEqualTo(12);
        retention.set(23);
        assertThat(scanner.lastReport().violationDetails().retentionLimit()).isEqualTo(12);
        assertThat(scanner.scan().violationDetails().retentionLimit()).isEqualTo(23);
    }
}
