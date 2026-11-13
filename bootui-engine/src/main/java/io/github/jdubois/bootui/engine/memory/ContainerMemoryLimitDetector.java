package io.github.jdubois.bootui.engine.memory;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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
                    List.of(new CgroupDirectoryFiles(
                            Path.of("/sys/fs/cgroup/memory.max"),
                            Path.of("/sys/fs/cgroup/memory.current"),
                            Path.of("/sys/fs/cgroup/memory.stat"),
                            Path.of("/sys/fs/cgroup/memory.high"),
                            Path.of("/sys/fs/cgroup/memory.events"))),
                    "inactive_file"),
            new CgroupMemoryFiles(
                    List.of(new CgroupDirectoryFiles(
                            Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes"),
                            Path.of("/sys/fs/cgroup/memory/memory.usage_in_bytes"),
                            Path.of("/sys/fs/cgroup/memory/memory.stat"))),
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
            CgroupSampleSelection maxSelection =
                    selectEffectiveDirectory(candidate.hierarchy(), CgroupDirectoryFiles::limitFile);
            CgroupSampleSelection highSelection =
                    selectEffectiveDirectory(candidate.hierarchy(), CgroupDirectoryFiles::highFile);
            MemoryContext.CgroupMemoryEvents events = readEvents(candidate.hierarchy());
            if (maxSelection != null || highSelection != null || (events != null && events.hasAnyEvent())) {
                CgroupDirectoryFiles primaryDir = maxSelection != null
                        ? maxSelection.directory()
                        : (highSelection != null
                                ? highSelection.directory()
                                : candidate.hierarchy().get(0));
                CgroupDirectoryFiles usageSource = Files.isRegularFile(primaryDir.currentFile())
                        ? primaryDir
                        : candidate.hierarchy().get(0);
                return new CgroupMemorySample(
                        maxSelection != null ? OptionalLong.of(maxSelection.limit()) : OptionalLong.empty(),
                        readUsage(usageSource.currentFile()),
                        readInactiveFile(usageSource.statFile(), candidate.inactiveFileKey()),
                        highSelection != null ? OptionalLong.of(highSelection.limit()) : OptionalLong.empty(),
                        events);
            }
        }
        return CgroupMemorySample.unavailable();
    }

    private CgroupSampleSelection selectEffectiveDirectory(
            List<CgroupDirectoryFiles> hierarchy,
            java.util.function.Function<CgroupDirectoryFiles, Path> fileSelector) {
        CgroupSampleSelection effective = null;
        for (CgroupDirectoryFiles dir : hierarchy) {
            Path file = fileSelector.apply(dir);
            if (file == null) {
                continue;
            }
            OptionalLong limit = readLimit(file);
            if (limit.isPresent() && (effective == null || limit.getAsLong() < effective.limit())) {
                effective = new CgroupSampleSelection(limit.getAsLong(), dir);
            }
        }
        return effective;
    }

    private MemoryContext.CgroupMemoryEvents readEvents(List<CgroupDirectoryFiles> hierarchy) {
        long high = 0;
        long max = 0;
        long oom = 0;
        long oomKill = 0;
        boolean found = false;
        for (CgroupDirectoryFiles dir : hierarchy) {
            Path file = dir.eventsFile();
            if (file == null || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                boolean parsedAnyKey = false;
                for (String line : Files.readAllLines(file)) {
                    String[] parts = line.trim().split("\\s+", 2);
                    if (parts.length != 2) {
                        continue;
                    }
                    OptionalLong value = parseNonNegative(parts[1]);
                    if (value.isEmpty()) {
                        continue;
                    }
                    switch (parts[0]) {
                        case "high" -> {
                            high = Math.max(high, value.getAsLong());
                            parsedAnyKey = true;
                        }
                        case "max" -> {
                            max = Math.max(max, value.getAsLong());
                            parsedAnyKey = true;
                        }
                        case "oom" -> {
                            oom = Math.max(oom, value.getAsLong());
                            parsedAnyKey = true;
                        }
                        case "oom_kill" -> {
                            oomKill = Math.max(oomKill, value.getAsLong());
                            parsedAnyKey = true;
                        }
                        default -> {}
                    }
                }
                if (parsedAnyKey) {
                    found = true;
                }
            } catch (IOException ex) {
                log.log(Level.DEBUG, "Could not read cgroup memory events from " + file, ex);
            }
        }
        return found ? new MemoryContext.CgroupMemoryEvents(high, max, oom, oomKill) : null;
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
            Path limitFile = limitFiles.get(index);
            Path currentFile = currentFiles.get(index);
            boolean v2 = limitFile.getFileName() != null
                    && "memory.max".equals(limitFile.getFileName().toString())
                    && limitFile.getParent() != null;
            Path parent = limitFile.getParent();
            pairs.add(new CgroupMemoryFiles(
                    List.of(new CgroupDirectoryFiles(
                            limitFile,
                            currentFile,
                            v2 ? parent.resolve("memory.stat") : null,
                            v2 ? parent.resolve("memory.high") : null,
                            v2 ? parent.resolve("memory.events") : null)),
                    v2 ? "inactive_file" : null));
        }
        return pairs;
    }

    private static List<CgroupMemoryFiles> resolveProcessCgroupFiles(Path selfCgroupFile, Path selfMountinfoFile) {
        List<CgroupMemoryFiles> files = new ArrayList<>();
        if (!Files.isRegularFile(selfCgroupFile) || !Files.isRegularFile(selfMountinfoFile)) {
            return List.of();
        }
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
        } catch (NoSuchFileException ex) {
            return List.of();
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
        List<CgroupDirectoryFiles> hierarchy = ancestorDirectoryFiles(
                directory, Path.of(mount.mountPoint()).normalize(), limitFileName, currentFileName);
        if (hierarchy.isEmpty()) {
            return List.of();
        }
        return List.of(new CgroupMemoryFiles(hierarchy, inactiveFileKey));
    }

    private static List<CgroupDirectoryFiles> ancestorDirectoryFiles(
            Path directory, Path mountPoint, String limitFileName, String currentFileName) {
        List<CgroupDirectoryFiles> hierarchy = new ArrayList<>();
        boolean v2 = "memory.max".equals(limitFileName);
        Path current = directory;
        while (current != null && current.startsWith(mountPoint)) {
            hierarchy.add(new CgroupDirectoryFiles(
                    current.resolve(limitFileName),
                    current.resolve(currentFileName),
                    current.resolve("memory.stat"),
                    v2 ? current.resolve("memory.high") : null,
                    v2 ? current.resolve("memory.events") : null));
            if (current.equals(mountPoint)) {
                break;
            }
            current = current.getParent();
        }
        return List.copyOf(hierarchy);
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

    record CgroupMemorySample(
            OptionalLong limit,
            OptionalLong current,
            OptionalLong inactiveFile,
            OptionalLong high,
            MemoryContext.CgroupMemoryEvents events) {

        CgroupMemorySample(OptionalLong limit, OptionalLong current, OptionalLong inactiveFile) {
            this(limit, current, inactiveFile, OptionalLong.empty(), null);
        }

        CgroupMemorySample {
            limit = limit == null ? OptionalLong.empty() : limit;
            current = current == null ? OptionalLong.empty() : current;
            inactiveFile = inactiveFile == null ? OptionalLong.empty() : inactiveFile;
            high = high == null ? OptionalLong.empty() : high;
        }

        static CgroupMemorySample unavailable() {
            return new CgroupMemorySample(
                    OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(), null);
        }

        OptionalLong workingSet() {
            if (current.isEmpty() || inactiveFile.isEmpty()) {
                return OptionalLong.empty();
            }
            long used = current.getAsLong();
            return OptionalLong.of(Math.max(0, used - inactiveFile.getAsLong()));
        }
    }

    private record CgroupDirectoryFiles(
            Path limitFile, Path currentFile, Path statFile, Path highFile, Path eventsFile) {

        CgroupDirectoryFiles(Path limitFile, Path currentFile, Path statFile) {
            this(limitFile, currentFile, statFile, null, null);
        }
    }

    private record CgroupSampleSelection(long limit, CgroupDirectoryFiles directory) {}

    private record CgroupMemoryFiles(List<CgroupDirectoryFiles> hierarchy, String inactiveFileKey) {}

    private record CgroupPath(String path) {}

    private record CgroupMount(String root, String mountPoint, String fileSystemType, boolean hasMemoryController) {}
}
