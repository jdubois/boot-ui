package io.github.jdubois.bootui.core.dto;

/**
 * The scan of the application's own class files that gives Code Inventory its methods and their hashes.
 *
 * @param status {@code PENDING}, {@code RUNNING}, {@code COMPLETE}, {@code PARTIAL} (stopped at its class limit or
 *     deadline), or {@code FAILED}
 * @param reason why it is partial or failed, or {@code null}
 * @param roots the class directories and jars it read
 * @param classes the classes it hashed
 * @param reused the classes whose hashes came from the previous scans, unchanged on disk
 * @param skipped the class files it could not parse
 * @param durationMillis how long it took, or {@code null} before it ended
 */
public record CodeInventoryScanDto(
        String status, String reason, int roots, int classes, int reused, int skipped, Long durationMillis) {}
