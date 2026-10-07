package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.agent.resolverfixture.FirstHolder;
import io.github.jdubois.bootui.agent.resolverfixture.SecondHolder;
import io.github.jdubois.bootui.agent.resolverfixture.ThirdHolder;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The resolver's loaded-class index ({@code docs/PLAN-v2.md} §5.16, M5-5f): a miss on a stale index forces one
 * rebuild, at most every {@link ThreadLocalResolver#FORCED_INDEX_MILLIS} ms; between two, a miss on a stale index is
 * asked again ({@code null}), never declared not resolved, until the periodic rebuild names its holder.
 */
class ThreadLocalResolverTests {

    private static final String[] CLAIMED = {"io.github.jdubois.bootui.agent.resolverfixture"};
    private static final long BUDGET = 5_000_000_000L;

    private final Instrumentation instrumentation = mock(Instrumentation.class);
    private final List<Class<?>> loaded = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong();
    private ThreadLocalResolver resolver;

    @BeforeEach
    void resolver() {
        when(instrumentation.getAllLoadedClasses()).thenAnswer(invocation -> loaded.toArray(new Class<?>[0]));
        resolver = new ThreadLocalResolver(instrumentation, new ThreadLocalResolver.InitializationCheck() {
            @Override
            String name() {
                return "test";
            }

            @Override
            boolean initialized(Class<?> type, String[] claimed) {
                return true;
            }
        });
        resolver.clock = clock::get;
    }

    @Test
    void aMissOnAStaleIndexForcesOneRebuildPerWindowAndIsAskedAgainBetweenThem() {
        // A fresh index that does not know the holder yet: a definitive "not resolved".
        assertThat(resolver.resolve(FirstHolder.LOCAL, CLAIMED, null, BUDGET)[0])
                .isNull();
        verify(instrumentation, times(1)).getAllLoadedClasses();

        // Stale, never forced yet: one forced rebuild names it.
        loaded.add(FirstHolder.class);
        clock.set(ThreadLocalResolver.STALE_INDEX_MILLIS + 500);
        assertThat(resolver.resolve(FirstHolder.LOCAL, CLAIMED, null, BUDGET)[0])
                .isEqualTo(FirstHolder.class.getName() + ".LOCAL");
        verify(instrumentation, times(2)).getAllLoadedClasses();
        long forced = clock.get();

        // Stale again within the window: asked again, no walk.
        loaded.add(SecondHolder.class);
        clock.set(forced + ThreadLocalResolver.STALE_INDEX_MILLIS + 500);
        assertThat(resolver.resolve(SecondHolder.LOCAL, CLAIMED, null, BUDGET)).isNull();
        clock.set(forced + ThreadLocalResolver.FORCED_INDEX_MILLIS - 1);
        assertThat(resolver.resolve(SecondHolder.LOCAL, CLAIMED, null, BUDGET)).isNull();
        verify(instrumentation, times(2)).getAllLoadedClasses();

        // The periodic rebuild, once the index is that old, names it.
        clock.set(forced + ThreadLocalResolver.INDEX_MILLIS);
        assertThat(resolver.resolve(SecondHolder.LOCAL, CLAIMED, null, BUDGET)[0])
                .isEqualTo(SecondHolder.class.getName() + ".LOCAL");
        verify(instrumentation, times(3)).getAllLoadedClasses();
        long periodic = clock.get();

        // Past the window since the last forced rebuild, a stale miss forces one again.
        loaded.add(ThirdHolder.class);
        clock.set(periodic + ThreadLocalResolver.STALE_INDEX_MILLIS + 500);
        assertThat(clock.get() - forced).isGreaterThanOrEqualTo(ThreadLocalResolver.FORCED_INDEX_MILLIS);
        assertThat(resolver.resolve(ThirdHolder.LOCAL, CLAIMED, null, BUDGET)[0])
                .isEqualTo(ThirdHolder.class.getName() + ".LOCAL");
        verify(instrumentation, times(4)).getAllLoadedClasses();
    }

    @Test
    void aMissOnAStaleIndexWithNoTimeLeftIsAskedAgainWithoutAWalk() {
        assertThat(resolver.resolve(FirstHolder.LOCAL, CLAIMED, null, BUDGET)[0])
                .isNull();
        loaded.add(FirstHolder.class);
        clock.set(ThreadLocalResolver.STALE_INDEX_MILLIS + 500);

        assertThat(resolver.resolve(FirstHolder.LOCAL, CLAIMED, null, 1L)).isNull();
        verify(instrumentation, times(1)).getAllLoadedClasses();
    }
}
