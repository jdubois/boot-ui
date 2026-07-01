package io.github.jdubois.bootui.quarkus.scheduled;

import io.quarkus.runtime.annotations.RecordableConstructor;

/**
 * The raw, verbatim {@code @io.quarkus.scheduler.Scheduled} member values for a single scheduled method,
 * captured at <em>build time</em> by the deployment processor's Jandex scan and replayed into the runtime
 * via a {@code @Recorder} (see {@code ScheduledTasksRecorder}).
 *
 * <p>The strings are stored exactly as written in the annotation — including Quarkus config references
 * ({@code "{property}"} / {@code "${property}"}), cron expressions with embedded spaces and commas, and ISO
 * or shorthand durations. They are deliberately <em>not</em> resolved or parsed at build time: doing so here
 * would (a) lose config references that only have a value at runtime and (b) risk SmallRye
 * {@code ${...}}-expansion or comma-splitting if they were routed through a config key instead of a recorded
 * object. {@code QuarkusScheduledTaskProvider} does the resolution + duration parsing at request time.</p>
 *
 * <p>This record is serialized into the Quarkus bytecode recorder, so its canonical constructor is annotated
 * {@link RecordableConstructor}; the module compiles with {@code -parameters} so the constructor parameter
 * names match the record components, which is what the recorder uses to read the captured values back.</p>
 *
 * @param methodDescription {@code declaringClass#method} identifier shown as the task's runnable name
 * @param cron the {@code cron} member (empty when unset)
 * @param every the {@code every} member (empty when unset)
 * @param delayed the {@code delayed} member (empty when unset)
 * @param delay the {@code delay} member ({@code 0} when unset)
 * @param delayUnit the {@code delayUnit} enum name ({@code "MINUTES"} when unset — the annotation default)
 */
public record RawScheduledTask(
        String methodDescription, String cron, String every, String delayed, long delay, String delayUnit) {

    @RecordableConstructor
    public RawScheduledTask(
            String methodDescription, String cron, String every, String delayed, long delay, String delayUnit) {
        this.methodDescription = methodDescription;
        this.cron = cron;
        this.every = every;
        this.delayed = delayed;
        this.delay = delay;
        this.delayUnit = delayUnit;
    }
}
