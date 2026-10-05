package io.github.jdubois.bootui.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import io.github.jdubois.bootui.agent.bridge.MethodProbes;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.matcher.ElementMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link MethodProbeShapesAdvice} woven into a class (PLAN-v2 M5-8, D44): an invocation its probe does not record, as
 * advice left behind by an ended probe sees, builds no argument array and boxes nothing, so it allocates nothing.
 */
class MethodProbeShapesAdviceTests {

    @AfterEach
    void reset() throws Exception {
        Method reset = AgentBridge.class.getDeclaredMethod("reset");
        reset.setAccessible(true);
        reset.invoke(null);
    }

    /** An argument array and three boxes per call would be over 10 MB; a little is the measurement's own. */
    static final long LITTLE = 64 * 1024L;

    @Test
    void anUnrecordedInvocationAllocatesNothing() throws Exception {
        Calls calls = weave(MethodProbeShapesAdvice.class);
        long before = (Long) MethodProbes.status().get("adviceCalls");

        long allocated = allocated(calls);

        assertThat((Long) MethodProbes.status().get("adviceCalls") - before).isEqualTo(220_000L);
        assertThat(allocated).isLessThan(LITTLE);
    }

    @Test
    void theMeasurementSeesAnAdviceThatReadsItsArgumentsEveryTime() throws Exception {
        assertThat(allocated(weave(Eager.class))).isGreaterThan(LITTLE * 10);
    }

    private Calls weave(Class<?> advice) throws Exception {
        Class<?> woven = new ByteBuddy()
                .redefine(Target.class)
                .visit(Advice.withCustomMapping()
                        .bind(MethodProbeAdvice.Slot.class, Integer.valueOf(0))
                        .bind(MethodProbeAdvice.ProbeId.class, Long.valueOf(4242L))
                        .to(advice)
                        .on(ElementMatchers.named("sum")))
                .make()
                .load(new ClassLoader(getClass().getClassLoader()) {}, ClassLoadingStrategy.Default.CHILD_FIRST)
                .getLoaded();
        assertThat(woven.getMethod("sum", long.class, double.class, int.class).getDeclaringClass())
                .isSameAs(woven);
        // Direct calls in the woven class's own loop: reflection would box by itself.
        return (Calls) woven.getDeclaredConstructor().newInstance();
    }

    private static long allocated(Calls calls) {
        long checksum = calls.loop(20_000);
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().getId();
        long allocated = threads.getThreadAllocatedBytes(id);
        checksum += calls.loop(200_000);
        allocated = threads.getThreadAllocatedBytes(id) - allocated;
        assertThat(checksum).isPositive();
        return allocated;
    }

    /** The control: reads its arguments on every call, recorded or not, as an eager advice would. */
    public static final class Eager {

        public static volatile Object[] last;

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static long enter(
                @MethodProbeAdvice.Slot int slot,
                @MethodProbeAdvice.ProbeId long id,
                @Advice.AllArguments Object[] arguments) {
            last = arguments;
            return MethodProbes.enter(slot, id);
        }
    }

    /** The woven class's calls, declared apart so the caller never loads the original class's copy of them. */
    public interface Calls {
        long loop(int times);
    }

    /** The woven class: primitive arguments and a primitive return, which the advice would box if it read them. */
    public static class Target implements Calls {

        public double sum(long a, double b, int c) {
            return a + b + c;
        }

        @Override
        public long loop(int times) {
            double total = 0;
            for (int i = 0; i < times; i++) {
                total += sum(i * 1_000_003L, i * 0.5d, i);
            }
            return (long) total;
        }
    }
}
