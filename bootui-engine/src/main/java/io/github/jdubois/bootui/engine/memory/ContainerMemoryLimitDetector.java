package io.github.jdubois.bootui.engine.memory;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;

final class ContainerMemoryLimitDetector {

    private static final Logger log = System.getLogger(ContainerMemoryLimitDetector.class.getName());
    private static final long CGROUP_V1_UNLIMITED_SENTINEL_FLOOR = Long.MAX_VALUE / 2;

    private static final List<Path> STANDARD_CGROUP_LIMIT_FILES =
            List.of(Path.of("/sys/fs/cgroup/memory.max"), Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes"));

    private final List<Path> limitFiles;

    private ContainerMemoryLimitDetector(List<Path> limitFiles) {
        this.limitFiles = List.copyOf(limitFiles);
    }

    static ContainerMemoryLimitDetector standard() {
        return new ContainerMemoryLimitDetector(STANDARD_CGROUP_LIMIT_FILES);
    }

    static ContainerMemoryLimitDetector disabled() {
        return new ContainerMemoryLimitDetector(List.of());
    }

    OptionalLong detectLimit() {
        for (Path limitFile : limitFiles) {
            OptionalLong limit = readLimit(limitFile);
            if (limit.isPresent()) {
                return limit;
            }
        }
        return OptionalLong.empty();
    }

    private OptionalLong readLimit(Path limitFile) {
        if (!Files.isRegularFile(limitFile)) {
            return OptionalLong.empty();
        }
        try {
            return parseLimit(Files.readString(limitFile));
        } catch (IOException ex) {
            log.log(Level.DEBUG, "Could not read cgroup memory limit from " + limitFile, ex);
            return OptionalLong.empty();
        }
    }

    static OptionalLong parseLimit(String rawLimit) {
        String value = rawLimit == null ? "" : rawLimit.trim();
        if (value.isEmpty() || "max".equals(value)) {
            return OptionalLong.empty();
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0 || parsed >= CGROUP_V1_UNLIMITED_SENTINEL_FLOOR) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(parsed);
        } catch (NumberFormatException ex) {
            log.log(Level.DEBUG, "Could not parse cgroup memory limit '" + value + "'", ex);
            return OptionalLong.empty();
        }
    }
}
