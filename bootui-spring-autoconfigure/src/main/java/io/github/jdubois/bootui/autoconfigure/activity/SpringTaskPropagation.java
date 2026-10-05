package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.engine.correlation.ManagedTasks;
import java.util.concurrent.RunnableScheduledFuture;

/**
 * BootUI's task propagation for Spring's executors and schedulers ({@code docs/PLAN-v2.md} D30, M4-15, M4-22), with the
 * one exception Spring's schedulers need: a trigger-based task, such as a cron {@code @Scheduled} method or
 * {@code schedule(task, trigger)}, reschedules itself at the end of each run from inside that run, through
 * {@code ReschedulingRunnable.run()} calling its own {@code schedule()}, and the scheduler decorates that next run there.
 * Propagating it would make every later run the request's that scheduled the first. That future is left undecorated,
 * so the later runs belong to no request. Every other submission, a scheduler's {@code execute} or a one-shot
 * {@code schedule(task, instant)} from inside a request's task included, keeps its request.
 */
final class SpringTaskPropagation {

    private static final String RESCHEDULING_RUNNABLE =
            "org.springframework.scheduling.concurrent.ReschedulingRunnable";

    /** Deep enough for the scheduler's own frames and a few composed decorators between them. */
    private static final int MAX_FRAMES = 40;

    private static final StackWalker WALKER = StackWalker.getInstance();

    private SpringTaskPropagation() {}

    /** {@code task} carrying the submitting request's correlation, unless it is a trigger rescheduling itself. */
    static Runnable propagate(Runnable task) {
        if (task instanceof RunnableScheduledFuture<?> && rescheduling()) {
            return task;
        }
        return ManagedTasks.propagate(task);
    }

    /** Whether this thread is in {@code ReschedulingRunnable.schedule()} called by that runnable's own {@code run()}. */
    static boolean rescheduling() {
        return WALKER.walk(frames -> {
            boolean inSchedule = false;
            for (StackWalker.StackFrame frame : (Iterable<StackWalker.StackFrame>) frames.limit(MAX_FRAMES)::iterator) {
                boolean rescheduling = RESCHEDULING_RUNNABLE.equals(frame.getClassName());
                if (inSchedule) {
                    return rescheduling && "run".equals(frame.getMethodName());
                }
                inSchedule = rescheduling && "schedule".equals(frame.getMethodName());
            }
            return false;
        });
    }
}
