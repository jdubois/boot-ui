package io.github.jdubois.bootui.engine.memory;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

final class ContainerMemoryLimitDetector {

    private static final Logger log = System.getLogger(ContainerMemoryLimitDetector.class.getName());
    private static final long CGROUP_V1_UNLIMITED_SENTINEL_FLOOR = Long.MAX_VALUE / 2;
    private static final Path SELF_CGROUP_FILE = Path.of("/proc/self/cgroup");
    private static final Path SELF_MOUNTINFO_FILE = Path.of("/proc/self/mountinfo");

    private static final List<CgroupMemoryFiles> ROOT_CGROUP_FILES = List.of(
            new CgroupMemoryFiles(
                    List.of(Path.of("/sys/fs/cgroup/memory.max")),
                    Path.of("/sys/fs/cgroup/memory.current"),
                    Path.of("/sys/fs/cgroup/memory.stat"),
                    "inactive_file"),
            new CgroupMemoryFiles(
                    List.of(Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes")),
                    Path.of("/sys/fs/cgroup/memory/memory.usage_in_bytes"),
                    Path.of("/sys/fs/cgroup/memory/memory.stat"),
                    "total_inactive_file"));

    private final List<CgroupMemoryFiles> files;

    ContainerMemoryLimitDetector(List<Path> limitFiles, List<Path> currentFiles) {
        this(pairFiles(limitFiles, currentFiles));
    }

    private ContainerMemoryLimitDetector(List<CgroupMemoryFiles> files) {
        this.files = List.copyOf(files);
    }

    static ContainerMemoryLimitDetector standard() {
        List<CgroupMemoryFiles> files =
                new ArrayList<>(resolveProcessCgroupFiles(SELF_CGROUP_FILE, SELF_MOUNTINFO_FILE));
        files.addAll(ROOT_CGROUP_FILES);
        return new ContainerMemoryLimitDetector(files);
    }

    static ContainerMemoryLimitDetector fromProcFiles(Path selfCgroupFile, Path selfMountinfoFile) {
        return new ContainerMemoryLimitDetector(resolveProcessCgroupFiles(selfCgroupFile, selfMountinfoFile));
    }

    static ContainerMemoryLimitDetector disabled() {
        return new ContainerMemoryLimitDetector(List.<CgroupMemoryFiles>of());
    }

    OptionalLong detectLimit() {
        return detect().limit();
    }

    OptionalLong detectCurrentUsage() {
        return detect().current();
    }

    CgroupMemorySample detect() {
        for (CgroupMemoryFiles candidate : files) {
            OptionalLong limit = readEffectiveLimit(candidate.limitFiles());
            if (limit.isPresent()) {
                return new CgroupMemorySample(
                        limit,
                        readUsage(candidate.currentFile()),
                        readInactiveFile(candidate.statFile(), candidate.inactiveFileKey()));
            }
        }
        return CgroupMemorySample.unavailable();
    }

    private OptionalLong readEffectiveLimit(List<Path> limitFiles) {
        OptionalLong effectiveLimit = OptionalLong.empty();
        for (Path limitFile : limitFiles) {
            OptionalLong limit = readLimit(limitFile);
            if (limit.isPresent() && (effectiveLimit.isEmpty() || limit.getAsLong() < effectiveLimit.getAsLong())) {
                effectiveLimit = limit;
            }
        }
        return effectiveLimit;
    }

    private OptionalLong readLimit(Path file) {
        if (!Files.isRegularFile(file)) {
            return OptionalLong.empty();
        }
        try {
            return parseLimit(Files.readString(file));
        } catch (IOException ex) {
            log.log(Level.DEBUG, "Could not read cgroup memory value from " + file, ex);
            return OptionalLong.empty();
        }
    }

    private OptionalLong readUsage(Path file) {
        if (!Files.isRegularFile(file)) {
            return OptionalLong.empty();
        }
        try {
            return parseNonNegative(Files.readString(file));
        } catch (IOException ex) {
            log.log(Level.DEBUG, "Could not read cgroup memory value from " + file, ex);
            return OptionalLong.empty();
        }
    }

    private OptionalLong readInactiveFile(Path file, String key) {
        if (file == null || key == null || !Files.isRegularFile(file)) {
            return OptionalLong.empty();
        }
        try {
            for (String line : Files.readAllLines(file)) {
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length == 2 && key.equals(parts[0])) {
                    return parseNonNegative(parts[1]);
                }
            }
        } catch (IOException ex) {
            log.log(Level.DEBUG, "Could not read cgroup memory statistics from " + file, ex);
        }
        return OptionalLong.empty();
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

    private static OptionalLong parseNonNegative(String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        if (value.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0 ? OptionalLong.of(parsed) : OptionalLong.empty();
        } catch (NumberFormatException ex) {
            log.log(Level.DEBUG, "Could not parse cgroup memory value '" + value + "'", ex);
            return OptionalLong.empty();
        }
    }

    private static List<CgroupMemoryFiles> pairFiles(List<Path> limitFiles, List<Path> currentFiles) {
        List<CgroupMemoryFiles> pairs = new ArrayList<>();
        int size = Math.min(limitFiles.size(), currentFiles.size());
        for (int index = 0; index < size; index++) {
            pairs.add(new CgroupMemoryFiles(List.of(limitFiles.get(index)), currentFiles.get(index), null, null));
        }
        return pairs;
    }

    private static List<CgroupMemoryFiles> resolveProcessCgroupFiles(Path selfCgroupFile, Path selfMountinfoFile) {
        List<CgroupMemoryFiles> files = new ArrayList<>();
        try {
            List<String> cgroupLines = Files.readAllLines(selfCgroupFile);
            List<CgroupMount> mounts = parseMounts(Files.readAllLines(selfMountinfoFile));
            CgroupPath v2 = findCgroupPath(cgroupLines, true);
            if (v2 != null) {
                for (CgroupMount mount : mounts) {
                    if ("cgroup2".equals(mount.fileSystemType())) {
                        files.addAll(filesFor(v2, mount, "memory.max", "memory.current", "inactive_file"));
                    }
                }
            }
            CgroupPath v1Memory = findCgroupPath(cgroupLines, false);
            if (v1Memory != null) {
                for (CgroupMount mount : mounts) {
                    if ("cgroup".equals(mount.fileSystemType()) && mount.hasMemoryController()) {
                        files.addAll(filesFor(
                                v1Memory,
                                mount,
                                "memory.limit_in_bytes",
                                "memory.usage_in_bytes",
                                "total_inactive_file"));
                    }
                }
            }
        } catch (IOException ex) {
            log.log(Level.DEBUG, "Could not resolve the process cgroup memory paths", ex);
        }
        return List.copyOf(files);
    }

    private static List<CgroupMemoryFiles> filesFor(
            CgroupPath cgroup,
            CgroupMount mount,
            String limitFileName,
            String currentFileName,
            String inactiveFileKey) {
        Path directory = resolveCgroupDirectory(cgroup.path(), mount);
        if (directory == null) {
            return List.of();
        }
        return List.of(new CgroupMemoryFiles(
                ancestorLimitFiles(directory, Path.of(mount.mountPoint()).normalize(), limitFileName),
                directory.resolve(currentFileName),
                directory.resolve("memory.stat"),
                inactiveFileKey));
    }

    private static List<Path> ancestorLimitFiles(Path directory, Path mountPoint, String limitFileName) {
        List<Path> limitFiles = new ArrayList<>();
        Path current = directory;
        while (current != null && current.startsWith(mountPoint)) {
            limitFiles.add(current.resolve(limitFileName));
            if (current.equals(mountPoint)) {
                break;
            }
            current = current.getParent();
        }
        return List.copyOf(limitFiles);
    }

    private static Path resolveCgroupDirectory(String cgroupPath, CgroupMount mount) {
        Path root = Path.of(mount.root()).normalize();
        Path processPath = Path.of(cgroupPath).normalize();
        Path mountPoint = Path.of(mount.mountPoint()).normalize();
        if (!processPath.startsWith(root)) {
            // A cgroup namespace can expose the process as "/" while mountinfo retains the host
            // hierarchy root. In that view the mount point is already the process cgroup.
            return mountPoint;
        }
        Path directory = mountPoint.resolve(root.relativize(processPath)).normalize();
        return directory.startsWith(mountPoint) ? directory : null;
    }

    private static CgroupPath findCgroupPath(List<String> lines, boolean unified) {
        for (String line : lines) {
            String[] fields = line.split(":", 3);
            if (fields.length != 3) {
                continue;
            }
            if (unified && fields[1].isEmpty()) {
                return new CgroupPath(unescapeProcPath(fields[2]));
            }
            if (!unified) {
                for (String controller : fields[1].split(",")) {
                    if ("memory".equals(controller)) {
                        return new CgroupPath(unescapeProcPath(fields[2]));
                    }
                }
            }
        }
        return null;
    }

    private static List<CgroupMount> parseMounts(List<String> lines) {
        List<CgroupMount> mounts = new ArrayList<>();
        for (String line : lines) {
            int separator = line.indexOf(" - ");
            if (separator < 0) {
                continue;
            }
            String[] beforeSeparator = line.substring(0, separator).split("\\s+");
            String[] afterSeparator = line.substring(separator + 3).split("\\s+");
            if (beforeSeparator.length < 5 || afterSeparator.length < 3) {
                continue;
            }
            String fileSystemType = afterSeparator[0];
            String mountRoot = unescapeProcPath(beforeSeparator[3]);
            String mountPoint = unescapeProcPath(beforeSeparator[4]);
            boolean hasMemoryController = hasMemoryController(afterSeparator[2])
                    || hasMemoryController(
                            Path.of(mountPoint).getFileName() == null
                                    ? ""
                                    : Path.of(mountPoint).getFileName().toString());
            mounts.add(new CgroupMount(mountRoot, mountPoint, fileSystemType, hasMemoryController));
        }
        return mounts;
    }

    private static boolean hasMemoryController(String value) {
        for (String token : value.split(",")) {
            if ("memory".equals(token)) {
                return true;
            }
        }
        return false;
    }

    private static String unescapeProcPath(String path) {
        return path.replace("\\040", " ")
                .replace("\\011", "\t")
                .replace("\\012", "\n")
                .replace("\\134", "\\");
    }

    record CgroupMemorySample(OptionalLong limit, OptionalLong current, OptionalLong inactiveFile) {

        CgroupMemorySample {
            limit = limit == null ? OptionalLong.empty() : limit;
            current = current == null ? OptionalLong.empty() : current;
            inactiveFile = inactiveFile == null ? OptionalLong.empty() : inactiveFile;
        }

        static CgroupMemorySample unavailable() {
            return new CgroupMemorySample(OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty());
        }

        OptionalLong workingSet() {
            if (current.isEmpty() || inactiveFile.isEmpty()) {
                return OptionalLong.empty();
            }
            long used = current.getAsLong();
            return OptionalLong.of(Math.max(0, used - inactiveFile.getAsLong()));
        }
    }

    private record CgroupMemoryFiles(List<Path> limitFiles, Path currentFile, Path statFile, String inactiveFileKey) {}

    private record CgroupPath(String path) {}

    private record CgroupMount(String root, String mountPoint, String fileSystemType, boolean hasMemoryController) {}
}
