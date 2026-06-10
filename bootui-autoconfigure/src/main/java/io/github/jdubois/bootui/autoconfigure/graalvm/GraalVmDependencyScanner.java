package io.github.jdubois.bootui.autoconfigure.graalvm;

import io.github.jdubois.bootui.core.dto.GraalVmDependencyDto;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
 */
final class GraalVmDependencyScanner {

    private static final String METADATA_PREFIX = "META-INF/native-image/";
    private static final String MAVEN_POM_PREFIX = "META-INF/maven/";
    private static final int MAX_DEPENDENCIES = 500;
    private static final Duration REPOSITORY_LOOKUP_TIMEOUT = Duration.ofSeconds(2);
    private static final String REPOSITORY_RAW_BASE_URL =
            "https://raw.githubusercontent.com/oracle/graalvm-reachability-metadata/master/metadata/";
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

    GraalVmDependencyScanner() {
        this(() -> System.getProperty("java.class.path", ""));
    }

    GraalVmDependencyScanner(Supplier<String> classPathSupplier) {
        this(classPathSupplier, new HttpReachabilityMetadataRepository());
    }

    GraalVmDependencyScanner(Supplier<String> classPathSupplier, ReachabilityMetadataRepository repository) {
        this(classPathSupplier, repository, new AtomicReference<>(Progress.idle()), new AtomicBoolean(false));
    }

    GraalVmDependencyScanner(
            Supplier<String> classPathSupplier,
            ReachabilityMetadataRepository repository,
            AtomicReference<Progress> progress,
            AtomicBoolean cancellationRequested) {
        this.classPathSupplier = classPathSupplier;
        this.repository = repository;
        this.progress = progress;
        this.cancellationRequested = cancellationRequested;
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
            inspected.add(inspect(file));
            progress.set(new Progress(true, "dependencies.inspect", inspected.size(), 0,
                    "Inspecting classpath JARs (" + inspected.size() + " found)."));
        }
        progress.set(new Progress(true, "dependencies.repository", inspected.size(), inspected.size(),
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
            progress.set(new Progress(true, "dependencies.repository", index, inspected.size(),
                    "Checking reachability metadata repository coverage (" + index + " of "
                            + inspected.size() + ")."));
            Optional<RepositoryCoverage> coverage = Optional.empty();
            String repositoryNote = null;
            Coordinates coordinates = dependency.coordinates();
            if (coordinates != null && coordinates.isComplete()) {
                if (!cache.containsKey(coordinates)) {
                    cache.put(coordinates, repository.coverage(coordinates));
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

    private InspectedDependency inspect(File jar) {
        try (JarFile jarFile = new JarFile(jar)) {
            boolean hasMetadataJson = false;
            boolean hasBuildArgs = false;
            Coordinates coordinates = null;
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
                if (coordinates == null && name.startsWith(MAVEN_POM_PREFIX) && name.endsWith("/pom.properties")) {
                    coordinates = readPomProperties(jarFile, entry).orElse(null);
                }
                if (hasMetadataJson && coordinates != null) {
                    break;
                }
            }
            if (coordinates == null) {
                coordinates = coordinatesFromMavenRepositoryPath(jar).orElse(null);
            }
            if (coordinates == null) {
                coordinates = coordinatesFromJarName(jar.getName()).orElse(null);
            }
            if (hasMetadataJson) {
                return new InspectedDependency(jar.getName(), true, SHIPS_NOTE, coordinates);
            }
            if (hasBuildArgs) {
                return new InspectedDependency(jar.getName(), false, BUILD_ARGS_NOTE, coordinates);
            }
            return new InspectedDependency(jar.getName(), false, MISSING_NOTE, coordinates);
        } catch (Exception ex) {
            return new InspectedDependency(jar.getName(), false, "Could not read JAR: " + ex.getMessage(), null);
        }
    }

    private Optional<Coordinates> readPomProperties(JarFile jarFile, JarEntry entry) {
        try (InputStream input = jarFile.getInputStream(entry)) {
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
            String groupId = String.join(".", java.util.Arrays.copyOfRange(parts, repositoryIndex + 1, parts.length - 3));
            Coordinates coordinates = new Coordinates(trimToNull(groupId), artifactId, version);
            return coordinates.isComplete() ? Optional.of(coordinates) : Optional.empty();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private Optional<Coordinates> coordinatesFromJarName(String name) {
        Matcher matcher = Pattern.compile("^(.+)-([0-9][A-Za-z0-9._+\\-]*)\\.jar$").matcher(name);
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

    record Coordinates(String groupId, String artifactId, String version) {
        boolean isComplete() {
            return groupId != null && artifactId != null && version != null;
        }

        String display() {
            if (groupId == null) {
                return artifactId + ":" + version;
            }
            return groupId + ":" + artifactId + ":" + version;
        }
    }

    record InspectedDependency(String name, boolean shipsMetadata, String note, Coordinates coordinates) {
        GraalVmDependencyDto toDto(Optional<RepositoryCoverage> coverage, String repositoryNote) {
            boolean repositoryMetadata = coverage.map(RepositoryCoverage::covered).orElse(false);
            String metadataVersion = coverage.map(RepositoryCoverage::metadataVersion).orElse(null);
            String testedVersions = coverage.map(RepositoryCoverage::testedVersionsDisplay).orElse(null);
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

    interface ReachabilityMetadataRepository {
        RepositoryCoverage coverage(Coordinates coordinates);
    }

    record RepositoryCoverage(
            boolean covered,
            boolean repositoryEntryExists,
            String metadataVersion,
            List<String> testedVersions,
            String lookupError) {
        RepositoryCoverage(boolean covered, String metadataVersion, List<String> testedVersions, String lookupError) {
            this(covered, covered || metadataVersion != null || (testedVersions != null && !testedVersions.isEmpty()),
                    metadataVersion, testedVersions, lookupError);
        }

        static RepositoryCoverage unavailable(String reason) {
            return new RepositoryCoverage(false, false, null, List.of(), reason);
        }

        String testedVersionsDisplay() {
            return testedVersions == null || testedVersions.isEmpty() ? null : String.join(", ", testedVersions);
        }

        String note(Coordinates coordinates) {
            if (covered) {
                return "Oracle reachability metadata repository covers " + coordinates.version()
                        + " with metadata " + metadataVersion + ".";
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

    static final class HttpReachabilityMetadataRepository implements ReachabilityMetadataRepository {

        private final HttpClient client;
        private final ObjectMapper objectMapper;
        private final String indexBaseUrl;

        HttpReachabilityMetadataRepository() {
            this(HttpClient.newBuilder().connectTimeout(REPOSITORY_LOOKUP_TIMEOUT).build(), new ObjectMapper(),
                    REPOSITORY_RAW_BASE_URL);
        }

        HttpReachabilityMetadataRepository(HttpClient client, ObjectMapper objectMapper, String indexBaseUrl) {
            this.client = client;
            this.objectMapper = objectMapper;
            this.indexBaseUrl = indexBaseUrl;
        }

        @Override
        public RepositoryCoverage coverage(Coordinates coordinates) {
            try {
                HttpRequest request = HttpRequest.newBuilder(indexUri(coordinates))
                        .timeout(REPOSITORY_LOOKUP_TIMEOUT)
                        .header("Accept", "application/json")
                        .header("User-Agent", "BootUI GraalVM readiness")
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 404) {
                    return new RepositoryCoverage(false, false, null, List.of(), null);
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return RepositoryCoverage.unavailable("HTTP " + response.statusCode());
                }
                return parse(coordinates, response.body());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return RepositoryCoverage.unavailable("interrupted");
            } catch (Exception ex) {
                return RepositoryCoverage.unavailable(GraalVmCheckSupport.detail(ex.getMessage()));
            }
        }

        private URI indexUri(Coordinates coordinates) {
            return URI.create(indexBaseUrl + encodeMetadataPath(coordinates.groupId()) + "/"
                    + encodeMetadataPath(coordinates.artifactId()) + "/index.json");
        }

        private RepositoryCoverage parse(Coordinates coordinates, String body) throws Exception {
            JsonNode root = objectMapper.readTree(body);
            if (!root.isArray()) {
                return RepositoryCoverage.unavailable("unexpected index format");
            }
            String latestMetadataVersion = null;
            List<String> latestTestedVersions = List.of();
            for (JsonNode entry : root) {
                String metadataVersion = text(entry, "metadata-version");
                List<String> testedVersions = stringList(entry.get("tested-versions"));
                if (latestMetadataVersion == null || entry.path("latest").asBoolean(false)) {
                    latestMetadataVersion = metadataVersion;
                    latestTestedVersions = testedVersions;
                }
                if (coordinates.version().equals(metadataVersion) || testedVersions.contains(coordinates.version())) {
                    return new RepositoryCoverage(true, metadataVersion, testedVersions, null);
                }
            }
            return new RepositoryCoverage(false, latestMetadataVersion != null || !latestTestedVersions.isEmpty(),
                    latestMetadataVersion, latestTestedVersions, null);
        }

        private String text(JsonNode node, String field) {
            JsonNode value = node.get(field);
            return value == null || value.isNull() ? null : value.asText();
        }

        private List<String> stringList(JsonNode node) {
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            for (JsonNode value : node) {
                if (value != null && !value.isNull()) {
                    values.add(value.asText());
                }
            }
            return List.copyOf(values);
        }
    }
}
