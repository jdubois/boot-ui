package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * A single live thread in the Thread / Process Viewer snapshot.
 *
 * <p>All fields are point-in-time and read-only. {@code cpuTimeMillis} and {@code userTimeMillis}
 * are {@code null} when the JVM does not support per-thread CPU timing. {@code stackTrace} is
 * empty when the value-exposure mode hides detailed content or no stack was captured.</p>
 */
public record ThreadInfoDto(
        long id,
        String name,
        String state,
        int priority,
        boolean daemon,
        boolean virtual,
        Long cpuTimeMillis,
        Long userTimeMillis,
        long blockedCount,
        long waitedCount,
        boolean inNative,
        boolean suspended,
        boolean deadlocked,
        String lockName,
        Long lockOwnerId,
        String lockOwnerName,
        List<String> stackTrace) {

    public ThreadInfoDto {
        stackTrace = stackTrace == null ? List.of() : List.copyOf(stackTrace);
    }
}
