package io.github.jdubois.bootui.core.dto;

/**
 * The run of the application BootUI is observing ({@code docs/PLAN-v2.md} §5.1): one application-context start.
 *
 * <p>A Spring DevTools restart or a Quarkus live reload starts a new run inside the same JVM, with a new
 * {@code runId} and the next {@code ordinal}, while {@code instanceId} stays the same until the JVM, or BootUI's own
 * class loader, is replaced. Both ids are random and carry no host, user, or application data.</p>
 *
 * @param instanceId random id of this JVM's BootUI instance, 8 lowercase hexadecimal characters
 * @param runId random id of this run, 8 lowercase hexadecimal characters
 * @param ordinal the run's position among the runs of this instance, starting at 1
 * @param startedAt when the run started, in epoch milliseconds
 */
public record ApplicationRunDto(String instanceId, String runId, int ordinal, long startedAt) {}
