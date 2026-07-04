package io.github.jdubois.bootui.engine.graalvm;

import io.github.jdubois.bootui.core.dto.GraalVmDependencyDto;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inspects the classpath JARs to report which third-party dependencies ship GraalVM reachability
 * metadata under {@code META-INF/native-image/}, and whether Oracle's GraalVM reachability metadata
 * repository has an entry for the dependency version. Only an actual reachability-metadata JSON file
 * (e.g. {@code reachability-metadata.json} or a {@code *-config.json}) counts as bundled metadata; a
 * bare {@code native-image.properties} only carries build arguments, not reachability metadata.
 * Dependencies without bundled metadata may still be covered by the GraalVM reachability metadata
 * repository, or may need manual hints.
 *
 * <p>The scan only opens JAR files (never the exploded application classes) and is bounded so a very
 * large classpath cannot make the panel expensive; if the bound is hit the survey reports a
 * truncation warning rather than silently dropping entries.</p>
 *
 * <p>A single classpath entry can expand into several reported dependencies: when it is a Spring Boot
 * fat/uber jar, {@code java.class.path} only ever contains the outer launcher jar (Spring Boot's
 * {@code LaunchedURLClassLoader} resolves {@code BOOT-INF/lib/*.jar} through custom {@code nested:}
 * URLs that never populate that system property), so the outer jar is expanded into one inspected
 * dependency per nested library jar instead of being mis-reported as a single dependency named after
 * the application's own launcher jar.</p>
 */
final class GraalVmDependencyScanner {

    private static final String METADATA_PREFIX = "META-INF/native-image/";
    private static final String MAVEN_POM_PREFIX = "META-INF/maven/";
    private static final String NESTED_LIBRARY_PREFIX = "BOOT-INF/lib/";
    private static final int MAX_DEPENDENCIES = 500;
    private static final String REPOSITORY_BROWSER_BASE_URL =
            "https://github.com/oracle/graalvm-reachability-metadata/tree/master/metadata/";
    private static final String REPOSITORY_BROWSER_FILE_BASE_URL =
            "https://github.com/oracle/graalvm-reachability-metadata/blob/master/metadata/";
    private static final String SHIPS_NOTE =
            "Ships GraalVM reachability metadata (reachability-metadata.json / *-config.json).";
    private static final String BUILD_ARGS_NOTE =
            "Bundles native-image build arguments (native-image.properties) but no reachability metadata JSON; "
                    + "runtime features may still need hints.";
    private static final String MISSING_NOTE = "No bundled reachability metadata.";

    private final Supplier<String> classPathSupplier;
    private final ReachabilityMetadataRepository repository;
    final AtomicReference<Progress> progress;
    final AtomicBoolean cancellationRequested;
    private final boolean repositoryLookupEnabled;
    private final int maxRepositoryLookups;

    GraalVmDependencyScanner(Supplier<String> classPathSupplier) {
        this(classPathSupplier, coordinates -> ReachabilityMetadataIndex.of(List.of()));
    }

    /**
     * Production constructor: the adapter supplies the {@link ReachabilityMetadataRepository} transport
     * implementation and BootUI honors the configured {@code bootui.graalvm.*} gating.
     */
    GraalVmDependencyScanner(
            ReachabilityMetadataRepository repository, boolean repositoryLookupEnabled, int maxRepositoryLookups) {
        this(
                () -> System.getProperty("java.class.path", ""),
                repository,
                new AtomicReference<>(Progress.idle()),
                new AtomicBoolean(false),
                repositoryLookupEnabled,
                maxRepositoryLookups);
    }

    GraalVmDependencyScanner(Supplier<String> classPathSupplier, ReachabilityMetadataRepository repository) {
        this(classPathSupplier, repository, new AtomicReference<>(Progress.idle()), new AtomicBoolean(false));
    }

    GraalVmDependencyScanner(
            Supplier<String> classPathSupplier,
            ReachabilityMetadataRepository repository,
            AtomicReference<Progress> progress,
            AtomicBoolean cancellationRequested) {
        this(classPathSupplier, repository, progress, cancellationRequested, true, MAX_DEPENDENCIES);
    }

    GraalVmDependencyScanner(
            Supplier<String> classPathSupplier,
            ReachabilityMetadataRepository repository,
            AtomicReference<Progress> progress,
            AtomicBoolean cancellationRequested,
            boolean repositoryLookupEnabled,
            int maxRepositoryLookups) {
        this.classPathSupplier = classPathSupplier;
        this.repository = repository;
        this.progress = progress;
        this.cancellationRequested = cancellationRequested;
        this.repositoryLookupEnabled = repositoryLookupEnabled;
        this.maxRepositoryLookups = Math.max(0, maxRepositoryLookups);
    }

    void cancel() {
        cancellationRequested.set(true);
    }

    DependencySurvey scan() {
        cancellationRequested.set(false);
        String classPath = classPathSupplier.get();
        if (classPath == null || classPath.isBlank()) {
            return new DependencySurvey(List.of(), false);
        }
        List<InspectedDependency> inspected = new ArrayList<>();
        boolean truncated = false;
        for (String entry : classPath.split(File.pathSeparator)) {
            if (cancellationRequested.get()) {
                break;
            }
            String trimmed = entry.trim();
            if (trimmed.isEmpty() || !trimmed.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            File file = new File(trimmed);
            if (!file.isFile()) {
                continue;
            }
            if (inspected.size() >= MAX_DEPENDENCIES) {
                truncated = true;
                break;
            }
            List<InspectedDependency> found = inspect(file);
            int remaining = MAX_DEPENDENCIES - inspected.size();
            if (found.size() >= remaining) {
                // A single fat/uber jar can expand into more nested dependencies than the remaining
                // budget; take only as many as fit and stop, same as the single-dependency case below.
                inspected.addAll(found.subList(0, remaining));
                truncated = true;
                progress.set(new Progress(
                        true,
                        "dependencies.inspect",
                        inspected.size(),
                        0,
                        "Inspecting classpath JARs (" + inspected.size() + " found)."));
                break;
            }
            inspected.addAll(found);
            progress.set(new Progress(
                    true,
                    "dependencies.inspect",
                    inspected.size(),
                    0,
                    "Inspecting classpath JARs (" + inspected.size() + " found)."));
        }
        progress.set(new Progress(
                true,
                "dependencies.repository",
                inspected.size(),
                inspected.size(),
                "Checking GraalVM reachability metadata repository coverage."));
        List<GraalVmDependencyDto> dependencies = applyRepositoryCoverage(inspected).stream()
                .sorted(Comparator.comparing(GraalVmDependencyDto::name))
                .toList();
        progress.set(Progress.idle());
        boolean cancelled = cancellationRequested.get();
        cancellationRequested.set(false);
        return new DependencySurvey(dependencies, truncated, cancelled);
    }

    static int maxDependencies() {
        return MAX_DEPENDENCIES;
    }

    private List<GraalVmDependencyDto> applyRepositoryCoverage(List<InspectedDependency> inspected) {
        List<GraalVmDependencyDto> dependencies = new ArrayList<>(inspected.size());
        Map<Coordinates, RepositoryCoverage> cache = new LinkedHashMap<>();
        int index = 0;
        for (InspectedDependency dependency : inspected) {
            if (cancellationRequested.get()) {
                break;
            }
            index++;
            progress.set(new Progress(
                    true,
                    "dependencies.repository",
                    index,
                    inspected.size(),
                    "Checking reachability metadata repository coverage (" + index + " of " + inspected.size() + ")."));
            Optional<RepositoryCoverage> coverage = Optional.empty();
            String repositoryNote;
            Coordinates coordinates = dependency.coordinates();
            if (!repositoryLookupEnabled) {
                repositoryNote = "Oracle reachability metadata repository lookup is disabled.";
            } else if (coordinates != null && coordinates.isComplete()) {
                if (!cache.containsKey(coordinates)) {
                    cache.put(
                            coordinates,
                            cache.size() >= maxRepositoryLookups
                                    ? RepositoryCoverage.unavailable("repository lookup limit reached")
                                    : toCoverage(coordinates, repository.fetch(coordinates)));
                }
                coverage = Optional.of(cache.get(coordinates));
                repositoryNote = coverage.get().note(coordinates);
            } else {
                repositoryNote = "Could not determine Maven coordinates for repository coverage lookup.";
            }
            dependencies.add(dependency.toDto(coverage, repositoryNote));
        }
        return dependencies;
    }

    /**
     * Applies the engine's coverage-matching policy to the raw index rows returned by the repository
     * adapter. A {@code lookupError} becomes an unavailable result (sanitized via {@link
     * GraalVmCheckSupport#detail(String)}); otherwise a row whose metadata version equals — or whose
     * tested versions contain — the dependency version marks it covered, and the {@code latest} row
     * drives the "has metadata but not for this version" note.
     */
    private RepositoryCoverage toCoverage(Coordinates coordinates, ReachabilityMetadataIndex index) {
        if (index.lookupError() != null && !index.lookupError().isBlank()) {
            return RepositoryCoverage.unavailable(GraalVmCheckSupport.detail(index.lookupError()));
        }
        String latestMetadataVersion = null;
        List<String> latestTestedVersions = List.of();
        for (ReachabilityMetadataIndex.Entry entry : index.entries()) {
            String metadataVersion = entry.metadataVersion();
            List<String> testedVersions = entry.testedVersions();
            if (latestMetadataVersion == null || entry.latest()) {
                latestMetadataVersion = metadataVersion;
                latestTestedVersions = testedVersions;
            }
            if (coordinates.version().equals(metadataVersion) || testedVersions.contains(coordinates.version())) {
                return new RepositoryCoverage(true, metadataVersion, testedVersions, null);
            }
        }
        return new RepositoryCoverage(
                false,
                latestMetadataVersion != null || !latestTestedVersions.isEmpty(),
                latestMetadataVersion,
                latestTestedVersions,
                null);
    }

    /**
     * Inspects one classpath JAR. Ordinarily this reports exactly one dependency, but a Spring Boot
     * fat/uber jar reports one dependency per {@code BOOT-INF/lib/} nested jar instead: the outer
     * launcher jar is the application's own archive, not a third-party dependency, and {@code
     * java.class.path} would otherwise cause every bundled dependency to be silently missed (see the
     * class-level Javadoc).
     */
    private List<InspectedDependency> inspect(File jar) {
        try (JarFile jarFile = new JarFile(jar)) {
            boolean hasMetadataJson = false;
            boolean hasBuildArgs = false;
            List<Coordinates> pomCandidates = new ArrayList<>();
            List<JarEntry> nestedLibraryEntries = new ArrayList<>();
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith(METADATA_PREFIX)) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".json")) {
                        hasMetadataJson = true;
                    } else if (lower.endsWith("native-image.properties")) {
                        hasBuildArgs = true;
                    }
                }
                if (name.startsWith(MAVEN_POM_PREFIX) && name.endsWith("/pom.properties")) {
                    readPomProperties(jarFile, entry).ifPresent(pomCandidates::add);
                }
                if (!entry.isDirectory() && isNestedLibraryJar(name)) {
                    nestedLibraryEntries.add(entry);
                }
            }
            if (!nestedLibraryEntries.isEmpty()) {
                List<InspectedDependency> nested = new ArrayList<>(nestedLibraryEntries.size());
                for (JarEntry nestedEntry : nestedLibraryEntries) {
                    nested.add(inspectNestedLibrary(jarFile, nestedEntry));
                }
                return nested;
            }
            Coordinates coordinates =
                    selectPomCoordinates(jar.getName(), pomCandidates).orElse(null);
            if (coordinates == null) {
                coordinates = coordinatesFromMavenRepositoryPath(jar).orElse(null);
            }
            if (coordinates == null) {
                coordinates = coordinatesFromJarName(jar.getName()).orElse(null);
            }
            return List.of(toInspectedDependency(jar.getName(), hasMetadataJson, hasBuildArgs, coordinates));
        } catch (Exception ex) {
            return List.of(
                    new InspectedDependency(jar.getName(), false, "Could not read JAR: " + ex.getMessage(), null));
        }
    }

    /**
     * Inspects a single {@code BOOT-INF/lib/} nested jar entry of a fat/uber jar without extracting it
     * to disk: {@link JarInputStream} reads the nested jar's own entries (and, for the rare case of a
     * nested jar that is itself a shaded/uber jar, its own nested candidates would simply not be
     * recursed into further -- one level of nesting matches how Spring Boot repackaging actually
     * works).
     */
    private InspectedDependency inspectNestedLibrary(JarFile outerJarFile, JarEntry nestedEntry) {
        String nestedName = nestedLibraryDisplayName(nestedEntry.getName());
        try (JarInputStream nestedJar = new JarInputStream(outerJarFile.getInputStream(nestedEntry))) {
            boolean hasMetadataJson = false;
            boolean hasBuildArgs = false;
            List<Coordinates> pomCandidates = new ArrayList<>();
            JarEntry inner;
            while ((inner = nestedJar.getNextJarEntry()) != null) {
                String name = inner.getName();
                if (name.startsWith(METADATA_PREFIX)) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".json")) {
                        hasMetadataJson = true;
                    } else if (lower.endsWith("native-image.properties")) {
                        hasBuildArgs = true;
                    }
                }
                if (name.startsWith(MAVEN_POM_PREFIX) && name.endsWith("/pom.properties")) {
                    readPomProperties(nestedJar).ifPresent(pomCandidates::add);
                }
            }
            Coordinates coordinates =
                    selectPomCoordinates(nestedName, pomCandidates).orElse(null);
            if (coordinates == null) {
                coordinates = coordinatesFromJarName(nestedName).orElse(null);
            }
            return toInspectedDependency(nestedName, hasMetadataJson, hasBuildArgs, coordinates);
        } catch (Exception ex) {
            return new InspectedDependency(nestedName, false, "Could not read nested JAR: " + ex.getMessage(), null);
        }
    }

    private InspectedDependency toInspectedDependency(
            String name, boolean hasMetadataJson, boolean hasBuildArgs, Coordinates coordinates) {
        if (hasMetadataJson) {
            return new InspectedDependency(name, true, SHIPS_NOTE, coordinates);
        }
        if (hasBuildArgs) {
            return new InspectedDependency(name, false, BUILD_ARGS_NOTE, coordinates);
        }
        return new InspectedDependency(name, false, MISSING_NOTE, coordinates);
    }

    private static boolean isNestedLibraryJar(String entryName) {
        return entryName.startsWith(NESTED_LIBRARY_PREFIX)
                && entryName.toLowerCase(Locale.ROOT).endsWith(".jar")
                && entryName.indexOf('/', NESTED_LIBRARY_PREFIX.length()) < 0;
    }

    private static String nestedLibraryDisplayName(String entryName) {
        int lastSlash = entryName.lastIndexOf('/');
        return lastSlash >= 0 ? entryName.substring(lastSlash + 1) : entryName;
    }

    private Optional<Coordinates> readPomProperties(JarFile jarFile, JarEntry entry) {
        try (InputStream input = jarFile.getInputStream(entry)) {
            return readPomProperties(input);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private Optional<Coordinates> readPomProperties(InputStream input) {
        try {
            Properties properties = new Properties();
            properties.load(input);
            Coordinates coordinates = new Coordinates(
                    trimToNull(properties.getProperty("groupId")),
                    trimToNull(properties.getProperty("artifactId")),
                    trimToNull(properties.getProperty("version")));
            return coordinates.isComplete() ? Optional.of(coordinates) : Optional.empty();
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    /**
     * Picks the coordinates to report when a jar contains more than one {@code pom.properties}. A
     * shaded/uber jar (e.g. built with the Maven Shade or Gradle Shadow plugin) commonly bundles each
     * relocated dependency's own {@code META-INF/maven/<groupId>/<artifactId>/pom.properties} alongside
     * its own, so naively taking "the first one found" can misreport the shaded jar under one of the
     * dependencies it relocated -- entry order in a JAR's central directory is a build-tool artifact,
     * not a documented contract. Preferring the candidate whose {@code artifactId}/{@code version}
     * matches the jar's own file name recovers the jar's own coordinates in the common case where the
     * shade plugin's default "artifactId-version.jar" naming was left untouched; when no candidate
     * matches the file name (or the file name itself does not parse), this falls back to the first
     * descriptor found, matching the scanner's previous behavior for the common single-pom.properties
     * case.
     */
    private Optional<Coordinates> selectPomCoordinates(String jarFileName, List<Coordinates> candidates) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (candidates.size() == 1) {
            return Optional.of(candidates.get(0));
        }
        Optional<Coordinates> ownNameGuess = coordinatesFromJarName(jarFileName);
        if (ownNameGuess.isPresent()) {
            for (Coordinates candidate : candidates) {
                if (candidate.artifactId().equals(ownNameGuess.get().artifactId())
                        && candidate.version().equals(ownNameGuess.get().version())) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.of(candidates.get(0));
    }

    private Optional<Coordinates> coordinatesFromMavenRepositoryPath(File jar) {
        try {
            String[] parts = jar.toPath().normalize().toString().split(Pattern.quote(File.separator));
            int repositoryIndex = -1;
            for (int i = parts.length - 1; i >= 0; i--) {
                if ("repository".equals(parts[i])) {
                    repositoryIndex = i;
                    break;
                }
            }
            if (repositoryIndex < 0 || parts.length - repositoryIndex < 5) {
                return Optional.empty();
            }
            String artifactId = parts[parts.length - 3];
            String version = parts[parts.length - 2];
            String fileName = parts[parts.length - 1];
            if (!fileName.equals(artifactId + "-" + version + ".jar")) {
                return Optional.empty();
            }
            String groupId =
                    String.join(".", java.util.Arrays.copyOfRange(parts, repositoryIndex + 1, parts.length - 3));
            Coordinates coordinates = new Coordinates(trimToNull(groupId), artifactId, version);
            return coordinates.isComplete() ? Optional.of(coordinates) : Optional.empty();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private Optional<Coordinates> coordinatesFromJarName(String name) {
        Matcher matcher =
                Pattern.compile("^(.+)-([0-9][A-Za-z0-9._+\\-]*)\\.jar$").matcher(name);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Coordinates(null, matcher.group(1), matcher.group(2)));
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /** Outcome of a dependency survey: the inspected dependencies plus whether the bound was hit. */
    record DependencySurvey(List<GraalVmDependencyDto> dependencies, boolean truncated, boolean cancelled) {
        DependencySurvey(List<GraalVmDependencyDto> dependencies, boolean truncated) {
            this(dependencies, truncated, false);
        }
    }

    record InspectedDependency(String name, boolean shipsMetadata, String note, Coordinates coordinates) {
        GraalVmDependencyDto toDto(Optional<RepositoryCoverage> coverage, String repositoryNote) {
            boolean repositoryMetadata =
                    coverage.map(RepositoryCoverage::covered).orElse(false);
            String metadataVersion =
                    coverage.map(RepositoryCoverage::metadataVersion).orElse(null);
            String testedVersions =
                    coverage.map(RepositoryCoverage::testedVersionsDisplay).orElse(null);
            String repositoryUrl = coverage.filter(RepositoryCoverage::repositoryEntryExists)
                    .map(ignored -> repositoryUrl(coordinates))
                    .orElse(null);
            String repositoryMetadataUrl = coverage.filter(RepositoryCoverage::covered)
                    .map(candidate -> repositoryMetadataUrl(coordinates, candidate.metadataVersion()))
                    .orElse(null);
            String coordinatesDisplay = coordinates == null ? null : coordinates.display();
            String combinedNote = note;
            if (repositoryNote != null && !repositoryNote.isBlank()) {
                if (MISSING_NOTE.equals(note) && repositoryNote.startsWith("Oracle reachability metadata repository")) {
                    combinedNote = repositoryNote;
                } else {
                    combinedNote = combinedNote + " " + repositoryNote;
                }
            }
            return new GraalVmDependencyDto(
                    name,
                    shipsMetadata,
                    combinedNote,
                    coordinatesDisplay,
                    repositoryMetadata,
                    metadataVersion,
                    testedVersions,
                    repositoryUrl,
                    repositoryMetadataUrl);
        }
    }

    static String repositoryUrl(Coordinates coordinates) {
        if (coordinates == null || !coordinates.isComplete()) {
            return null;
        }
        return REPOSITORY_BROWSER_BASE_URL + encodeMetadataPath(coordinates.groupId()) + "/"
                + encodeMetadataPath(coordinates.artifactId());
    }

    static String repositoryMetadataUrl(Coordinates coordinates, String metadataVersion) {
        if (coordinates == null || !coordinates.isComplete() || metadataVersion == null || metadataVersion.isBlank()) {
            return null;
        }
        return REPOSITORY_BROWSER_FILE_BASE_URL + encodeMetadataPath(coordinates.groupId()) + "/"
                + encodeMetadataPath(coordinates.artifactId()) + "/" + encodeMetadataPath(metadataVersion)
                + "/reachability-metadata.json";
    }

    private static String encodeMetadataPath(String value) {
        return value.replace(" ", "%20");
    }

    record Progress(boolean running, String phase, int current, int total, String message) {
        static Progress idle() {
            return new Progress(false, "idle", 0, 0, "No dependency metadata survey is running.");
        }
    }

    record RepositoryCoverage(
            boolean covered,
            boolean repositoryEntryExists,
            String metadataVersion,
            List<String> testedVersions,
            String lookupError) {
        RepositoryCoverage(boolean covered, String metadataVersion, List<String> testedVersions, String lookupError) {
            this(
                    covered,
                    covered || metadataVersion != null || (testedVersions != null && !testedVersions.isEmpty()),
                    metadataVersion,
                    testedVersions,
                    lookupError);
        }

        static RepositoryCoverage unavailable(String reason) {
            return new RepositoryCoverage(false, false, null, List.of(), reason);
        }

        String testedVersionsDisplay() {
            return testedVersions == null || testedVersions.isEmpty() ? null : String.join(", ", testedVersions);
        }

        String note(Coordinates coordinates) {
            if (covered) {
                return "Oracle reachability metadata repository covers " + coordinates.version() + " with metadata "
                        + metadataVersion + ".";
            }
            if (lookupError != null && !lookupError.isBlank()) {
                return "Oracle reachability metadata repository lookup unavailable: " + lookupError + ".";
            }
            if (repositoryEntryExists) {
                return "Oracle reachability metadata repository has metadata for this library, but not for "
                        + coordinates.version() + ". Latest metadata version: " + metadataVersion + ".";
            }
            return "Oracle reachability metadata repository has no entry for this library.";
        }
    }
}
