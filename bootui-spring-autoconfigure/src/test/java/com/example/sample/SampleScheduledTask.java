package com.example.sample;

/**
 * Stand-in for a host-application-owned {@code @Scheduled} bean, used by
 * {@link io.github.jdubois.bootui.autoconfigure.scheduled.ScheduledTaskRunObservationHandlerTests} to
 * exercise capture of a task outside BootUI's own package (which the self-data filter excludes).
 */
public class SampleScheduledTask {

    public void run() {}
}
