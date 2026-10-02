package io.github.jdubois.bootui.quarkus.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.Map;
import java.util.ServiceLoader;
import org.eclipse.microprofile.context.spi.ThreadContextController;
import org.eclipse.microprofile.context.spi.ThreadContextProvider;
import org.eclipse.microprofile.context.spi.ThreadContextSnapshot;
import org.junit.jupiter.api.Test;

class BootUiThreadContextProviderTest {

    private final BootUiThreadContextProvider provider = new BootUiThreadContextProvider();

    @Test
    void aManagedTaskRunsAsAnExecutionOfTheRequestThatSubmittedIt() {
        ThreadContextSnapshot snapshot;
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest("r1"))) {
            snapshot = provider.currentContext(Map.of());
        }

        ThreadContextController controller = snapshot.begin();
        CorrelationContext task = BootUiCorrelation.current();
        controller.endContext();

        assertThat(task.requestId()).isEqualTo("r1");
        assertThat(task.executionId()).startsWith("task-");
        assertThat(BootUiCorrelation.current()).isEqualTo(CorrelationContext.NONE);
    }

    @Test
    void aClearedContextRunsWithoutTheRequestAndTheProviderIsRegisteredAsAService() {
        try (BootUiCorrelation.Scope ignored = BootUiCorrelation.open(CorrelationContext.forRequest("r1"))) {
            ThreadContextController controller =
                    provider.clearedContext(Map.of()).begin();
            assertThat(BootUiCorrelation.current()).isEqualTo(CorrelationContext.NONE);
            controller.endContext();
            assertThat(BootUiCorrelation.current().requestId()).isEqualTo("r1");
        }
        assertThat(ServiceLoader.load(ThreadContextProvider.class).stream().map(ServiceLoader.Provider::type))
                .contains(BootUiThreadContextProvider.class);
        assertThat(provider.getThreadContextType()).isEqualTo("BootUI");
    }
}
