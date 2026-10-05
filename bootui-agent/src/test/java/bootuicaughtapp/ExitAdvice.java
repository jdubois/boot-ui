package bootuicaughtapp;

import java.util.concurrent.atomic.AtomicInteger;
import net.bytebuddy.asm.Advice;

/**
 * An advice with an exit on throwables and an enter value, as the code-paths sensor's is, so Byte Buddy checks every
 * frame of the advised method against its declared parameters: the caught-exceptions visit's frames must pass.
 */
public final class ExitAdvice {

    public static final AtomicInteger EXITS = new AtomicInteger();

    private ExitAdvice() {}

    @Advice.OnMethodEnter
    public static long enter() {
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Enter long started) {
        if (started != 0L) {
            EXITS.incrementAndGet();
        }
    }
}
