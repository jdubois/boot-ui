package io.github.jdubois.bootui.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

import io.github.jdubois.bootui.engine.javaagent.AgentThreadLocals;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import reactor.core.scheduler.Schedulers;

/**
 * The WebFlux {@code thread-locals} scopes ({@code docs/PLAN-v2.md} §5.16, M5-5f): a Reactor schedule hook opens an
 * unowned scope around each scheduler task and closes it after the task, even when it throws, only while the agent's
 * bridge carries the sensor, and is removed when the context closes.
 */
class ReactorThreadLocalsScopesTests {

    @AfterEach
    void resetHook() {
        Schedulers.resetOnScheduleHook(ReactorThreadLocalsScopes.HOOK);
    }

    @Test
    void eachScheduledTaskRunsInsideAnUnownedScopeClosedAfterIt() {
        List<String> calls = new ArrayList<>();
        try (MockedStatic<AgentThreadLocals> agent = mockStatic(AgentThreadLocals.class)) {
            agent.when(AgentThreadLocals::bound).thenReturn(true);
            agent.when(AgentThreadLocals::openUnowned).thenAnswer(invocation -> {
                calls.add("open");
                return 42L;
            });
            agent.when(() -> AgentThreadLocals.close(42L)).thenAnswer(invocation -> calls.add("close"));

            ReactorThreadLocalsScopes.register();
            Runnable scheduled = Schedulers.onSchedule(() -> calls.add("task"));
            scheduled.run();

            assertThat(scheduled).isInstanceOf(ReactorThreadLocalsScopes.Scoped.class);
            assertThat(calls).containsExactly("open", "task", "close");
        }
    }

    @Test
    void aTaskThatThrowsStillClosesItsScope() {
        try (MockedStatic<AgentThreadLocals> agent = mockStatic(AgentThreadLocals.class)) {
            agent.when(AgentThreadLocals::bound).thenReturn(true);
            agent.when(AgentThreadLocals::openUnowned).thenReturn(7L);

            ReactorThreadLocalsScopes.register();
            Runnable scheduled = Schedulers.onSchedule(() -> {
                throw new IllegalStateException("boom");
            });

            assertThatThrownBy(scheduled::run).isInstanceOf(IllegalStateException.class);
            agent.verify(() -> AgentThreadLocals.close(7L));
        }
    }

    @Test
    void withoutTheSensorNoHookIsRegistered() {
        try (MockedStatic<AgentThreadLocals> agent = mockStatic(AgentThreadLocals.class)) {
            agent.when(AgentThreadLocals::bound).thenReturn(false);
            Runnable task = () -> {};

            ReactorThreadLocalsScopes.register();

            assertThat(Schedulers.onSchedule(task)).isSameAs(task);
            agent.verify(AgentThreadLocals::openUnowned, never());
        }
    }

    @Test
    void closingRemovesTheHook() {
        try (MockedStatic<AgentThreadLocals> agent = mockStatic(AgentThreadLocals.class)) {
            agent.when(AgentThreadLocals::bound).thenReturn(true);
            Runnable task = () -> {};

            ReactorThreadLocalsScopes scopes = ReactorThreadLocalsScopes.register();
            scopes.close();

            assertThat(Schedulers.onSchedule(task)).isSameAs(task);
        }
    }
}
