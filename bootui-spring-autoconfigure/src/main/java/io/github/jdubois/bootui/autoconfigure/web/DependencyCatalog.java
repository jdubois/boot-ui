package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.DependencyAssessmentDto;
import io.github.jdubois.bootui.core.dto.DependencyCoverageDto;
import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.engine.support.BlankStrings;
import io.github.jdubois.bootui.engine.vulnerabilities.ArchiveNames;
import io.github.jdubois.bootui.engine.vulnerabilities.DependencyInventory;
import io.github.jdubois.bootui.engine.vulnerabilities.DependencyProvider;
import io.github.jdubois.bootui.engine.vulnerabilities.FirstPartyArchives;
import io.github.jdubois.bootui.engine.vulnerabilities.PackageUrls;
import io.github.jdubois.bootui.engine.vulnerabilities.ZipDirectory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves the running Spring application's dependency inventory, and reports how much of its real JAR set
 * that inventory actually covers.
 *
 * <p>Coordinates are resolved from three local sources, in decreasing order of authority:</p>
 *
 * <ol>
 *   <li>the application's embedded <a href="https://cyclonedx.org/">CycloneDX</a> SBOM
 *       ({@code META-INF/sbom/application.cdx.json}, the file Spring Boot's {@code /actuator/sbom} serves),
 *       whose {@code purl} values carry the {@code groupId};</li>
 *   <li>{@code META-INF/maven/*}{@code /*}{@code /pom.properties} descriptors on the classpath;</li>
 *   <li>the {@code java.class.path} entries, read through the Maven repository directory layout or an
 *       adjacent {@code .pom}.</li>
 * </ol>
 *
 * <p>The SBOM matters because sources 2 and 3 leave a large, security-relevant hole. Many artifacts are
 * published with no Maven descriptor at all (Spring Framework, Spring Boot, Spring Security,
 * {@code tomcat-embed-*}, {@code hibernate-core}, {@code kotlin-stdlib}, the PostgreSQL driver, and the
 * {@code opentelemetry-*} and {@code micrometer-*} families among them), and inside a repackaged fat JAR
 * source 3 is dead too, because {@code java.class.path} is then just the application archive. No JAR
 * manifest header carries a {@code groupId} &mdash; {@code Implementation-Title} is a display name as often
 * as an artifact id, and {@code Implementation-Vendor-Id} is not a group id &mdash; so an application
 * without an SBOM genuinely cannot resolve those artifacts locally.</p>
 *
 * <p>That is why the catalogue also takes a census of the application's real archives ({@code BOOT-INF/lib/}
 * entries inside a repackaged JAR or WAR, classpath and application-classloader JARs otherwise) and reports
 * every archive it could not attribute to a resolved coordinate as {@link DependencyCoverageDto#INCOMPLETE}
 * coverage. A scan covering part of the classpath is then visibly partial instead of rendering as a green,
 * full-coverage result. When
 * the census itself cannot run &mdash; no enumerable classpath or classloader archives, as under a native image
 * &mdash; coverage is reported {@link DependencyCoverageDto#UNAVAILABLE} rather than assumed complete.</p>
 *
 * <p>Two kinds of archive are never in an SBOM yet are not a coverage gap, so an archive left unidentified
 * is inspected (manifest and entry names only) before being reported: the application's own module JARs of a
 * multi-module build, whose every class lives in the application's base packages and which a Spring Boot
 * layers index (when present) places in its {@code application} layer, are counted as first-party
 * ({@link FirstPartyArchives}); and {@code spring-boot-jarmode-tools}, which Spring Boot adds at
 * packaging time, is identified from its manifest.</p>
 *
 * <p>Every source fails soft: an unreadable descriptor, a malformed SBOM, or an unreadable archive is logged
 * and skipped without discarding the entries that did resolve.</p>
 */
final class DependencyCatalog implements DependencyProvider {

    private static final String MAVEN_PROPERTIES_PATTERN = "classpath*:META-INF/maven/*/*/pom.properties";

    /** The two SBOM locations Spring Boot's {@code SbomEndpoint} recognizes for the application BOM. */
    static final List<String> SBOM_PATTERNS =
            List.of("classpath*:META-INF/sbom/application.cdx.json", "classpath*:META-INF/sbom/bom.json");

    private static final String MAVEN_METADATA_MARKER = "!/META-INF/maven/";

    /** Manifest attribute Spring Boot's repackaging writes, naming the nested library directory. */
    private static final String SPRING_BOOT_LIB_ATTRIBUTE = "Spring-Boot-Lib";

    private static final List<String> DEFAULT_NESTED_LIBRARY_PREFIXES = List.of("BOOT-INF/lib/", "WEB-INF/lib/");

    /** Manifest attribute Spring Boot's repackaging writes, naming the layers index. */
    private static final String SPRING_BOOT_LAYERS_INDEX = "Spring-Boot-Layers-Index";

    private static final List<String> DEFAULT_LAYERS_INDEXES = List.of("BOOT-INF/layers.idx", "WEB-INF/layers.idx");

    /** Upper bound on a nested archive's manifest, the only nested content ever inflated. */
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;

    /** Upper bound on the archive names carried in the report; the reported counts stay exact regardless. */
    static final int MAX_UNIDENTIFIED_ARCHIVES = 200;

    /** Upper bound on SBOM components read, so a pathological BOM cannot stall a panel load. */
    private static final int MAX_SBOM_COMPONENTS = 10_000;

    private static final System.Logger LOGGER = System.getLogger(DependencyCatalog.class.getName());

    /** The only Spring Boot packaging-time artifact identified from its manifest; see {@link #bootArtifact}. */
    static final String JARMODE_TOOLS_ARTIFACT_ID = "spring-boot-jarmode-tools";

    private static final String JARMODE_TOOLS_PACKAGE = "org/springframework/boot/jarmode/tools/";

    private static final String JARMODE_TOOLS_TITLE = "Spring Boot Jarmode Tools";

    private static final String SPRING_BOOT_GROUP_ID = "org.springframework.boot";

    private static final String BOOT_MANIFEST_SOURCE = "Spring Boot manifest";

    private final ResourcePatternResolver resolver;

    private final Supplier<List<String>> basePackages;

    private final ObjectMapper objectMapper = new ObjectMapper();

    DependencyCatalog() {
        this(new PathMatchingResourcePatternResolver());
    }

    DependencyCatalog(ResourcePatternResolver resolver) {
        this(resolver, List::of);
    }

    /**
     * @param basePackages the application's base packages, read live on every inventory; archives whose every
     *     class lives in them are reported as first-party rather than unidentified
     */
    DependencyCatalog(ResourcePatternResolver resolver, Supplier<List<String>> basePackages) {
        this.resolver = resolver;
        this.basePackages = basePackages == null ? List::of : basePackages;
    }

    @Override
    public List<DependencyDto> dependencies() {
        return inventory().dependencies();
    }

    @Override
    public DependencyInventory inventory() {
        Map<String, DependencyDto> dependencies = new LinkedHashMap<>();
        Set<String> identifiedArchives = new LinkedHashSet<>();

        for (DependencyDto dependency : sbomDependencies()) {
            dependencies.putIfAbsent(key(dependency), dependency);
        }
        for (Resource resource : resources(MAVEN_PROPERTIES_PATTERN)) {
            DependencyDto dependency = dependency(resource);
            if (dependency != null) {
                dependencies.putIfAbsent(key(dependency), dependency);
                String owner = owningArchive(resource);
                if (owner != null) {
                    identifiedArchives.add(owner);
                }
            }
        }
        for (DependencyDto dependency : javaClassPathDependencies()) {
            dependencies.putIfAbsent(key(dependency), dependency);
        }

        DependencyCoverageDto coverage = coverage(dependencies, identifiedArchives);
        List<DependencyDto> resolved = dependencies.values().stream()
                .sorted(Comparator.comparing(DependencyDto::packageName).thenComparing(DependencyDto::version))
                .toList();
        return new DependencyInventory(resolved, coverage);
    }

    private static String key(DependencyDto dependency) {
        return dependency.packageName() + ":" + dependency.version();
    }

    // -----------------------------------------------------------------------------------------------
    // Coverage
    // -----------------------------------------------------------------------------------------------

    /**
     * Compares the resolved coordinates against the application's real archive census. An archive counts as
     * identified when a Maven descriptor was read from inside it, or when its file name is the one Maven
     * would publish for a resolved coordinate.
     *
     * <p>Only an archive still unidentified after that is opened, and only to read its manifest and entry
     * names: a Spring Boot packaging-time artifact is identified from its manifest (and added to
     * {@code dependencies}, so it is scanned too), and an archive whose every class lives in the application's
     * base packages is reported as first-party. Anything else stays unidentified.</p>
     */
    private DependencyCoverageDto coverage(Map<String, DependencyDto> dependencies, Set<String> identifiedArchives) {
        List<CensusArchive> archives = archiveCensus();
        if (archives.isEmpty()) {
            return DependencyCoverageDto.unavailable();
        }
        List<DependencyDto> resolved = List.copyOf(dependencies.values());
        List<String> packages = applicationBasePackages();
        List<String> unidentified = new ArrayList<>();
        List<String> firstParty = new ArrayList<>();
        try (OuterArchives outers = new OuterArchives()) {
            for (CensusArchive archive : archives) {
                if (identifiedArchives.contains(archive.name()) || isIdentified(archive.name(), resolved)) {
                    continue;
                }
                switch (inspect(archive, packages, dependencies, outers)) {
                    case IDENTIFIED -> {
                        // Added to the inventory by inspect.
                    }
                    case FIRST_PARTY -> firstParty.add(archive.name());
                    case UNIDENTIFIED -> unidentified.add(archive.name());
                }
            }
        }
        unidentified.sort(String.CASE_INSENSITIVE_ORDER);
        firstParty.sort(String.CASE_INSENSITIVE_ORDER);
        return DependencyCoverageDto.of(
                archives.size(),
                unidentified.size(),
                unidentified.stream().limit(MAX_UNIDENTIFIED_ARCHIVES).toList(),
                firstParty.size(),
                firstParty.stream().limit(MAX_UNIDENTIFIED_ARCHIVES).toList());
    }

    private static boolean isIdentified(String archive, List<DependencyDto> resolved) {
        for (DependencyDto dependency : resolved) {
            if (ArchiveNames.matches(archive, dependency.artifactId(), dependency.version())) {
                return true;
            }
        }
        return false;
    }

    private List<String> applicationBasePackages() {
        try {
            return FirstPartyArchives.basePackages(basePackages.get());
        } catch (RuntimeException ex) {
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "Could not determine the application base packages for first-party archives: {0}",
                    ex.getMessage());
            return List.of();
        }
    }

    private enum Attribution {
        IDENTIFIED,
        FIRST_PARTY,
        UNIDENTIFIED
    }

    /**
     * Classifies an archive the coordinate sources could not attribute, reading only its manifest and entry
     * names. An on-disk archive is read through its central directory. A nested {@code BOOT-INF/lib/} archive
     * must be stored uncompressed, as Spring Boot writes it: only its central directory is read by offset
     * ({@link ZipDirectory}), nothing is inflated except a bounded manifest, and the outer archive is opened once
     * per census. An archive a layers index assigns outside the {@code application} layer is never first-party.
     * Any failure leaves the archive unidentified.
     */
    private Attribution inspect(
            CensusArchive archive,
            List<String> packages,
            Map<String, DependencyDto> dependencies,
            OuterArchives outers) {
        if (archive.shadowed()) {
            return Attribution.UNIDENTIFIED;
        }
        boolean bootCandidate = archive.name().startsWith(JARMODE_TOOLS_ARTIFACT_ID + "-");
        boolean firstPartyCandidate = !packages.isEmpty() && !Boolean.FALSE.equals(archive.applicationLayer());
        if (!bootCandidate && !firstPartyCandidate) {
            return Attribution.UNIDENTIFIED;
        }
        List<String> accepted = Boolean.TRUE.equals(archive.applicationLayer())
                ? FirstPartyArchives.applicationLayerPackages(packages)
                : packages;
        try {
            if (archive.nestedEntry() == null) {
                try (JarFile jar = new JarFile(archive.file().toFile(), false)) {
                    Manifest manifest = bootCandidate ? jar.getManifest() : null;
                    Iterable<String> names =
                            () -> jar.stream().map(JarEntry::getName).iterator();
                    return classify(archive.name(), manifest, firstPartyCandidate, names, accepted, dependencies);
                }
            }
            JarFile outer = outers.open(archive.file());
            JarEntry nested = outer.getJarEntry(archive.nestedEntry());
            if (nested == null || nested.getMethod() != ZipEntry.STORED || nested.getSize() < 0) {
                return Attribution.UNIDENTIFIED;
            }
            ZipDirectory.Source source = offset -> {
                InputStream input = outer.getInputStream(nested);
                try {
                    input.skipNBytes(offset);
                    return input;
                } catch (IOException | RuntimeException ex) {
                    input.close();
                    throw ex;
                }
            };
            List<ZipDirectory.Entry> entries = ZipDirectory.read(source, nested.getSize());
            Manifest manifest = bootCandidate ? manifest(source, entries) : null;
            List<String> names = entries.stream().map(ZipDirectory.Entry::name).toList();
            return classify(archive.name(), manifest, firstPartyCandidate, names, accepted, dependencies);
        } catch (IOException | RuntimeException ex) {
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "Could not inspect unidentified archive {0}: {1}",
                    archive.name(),
                    ex.getMessage());
            return Attribution.UNIDENTIFIED;
        }
    }

    private static Manifest manifest(ZipDirectory.Source source, List<ZipDirectory.Entry> entries) throws IOException {
        for (ZipDirectory.Entry entry : entries) {
            if (JarFile.MANIFEST_NAME.equalsIgnoreCase(entry.name())) {
                return new Manifest(
                        new ByteArrayInputStream(ZipDirectory.readEntry(source, entry, MAX_MANIFEST_BYTES)));
            }
        }
        return null;
    }

    /**
     * Whether the archive carries the jarmode tools and nothing else executable, so neither a manifest alone nor
     * a real tools class bundled with other code can claim the identity.
     */
    private static boolean containsJarmodeToolsClasses(Iterable<String> entryNames) {
        boolean tools = false;
        for (String name : entryNames) {
            if (name == null) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".jar") || lower.endsWith(".war") || lower.endsWith(".zip")) {
                return false;
            }
            if (name.endsWith(".class")) {
                if (!name.startsWith(JARMODE_TOOLS_PACKAGE)) {
                    return false;
                }
                tools = true;
            }
        }
        return tools;
    }

    private static Attribution classify(
            String archive,
            Manifest manifest,
            boolean firstPartyCandidate,
            Iterable<String> entryNames,
            List<String> packages,
            Map<String, DependencyDto> dependencies) {
        DependencyDto bootArtifact = bootArtifact(archive, manifest);
        if (bootArtifact != null && containsJarmodeToolsClasses(entryNames)) {
            dependencies.putIfAbsent(key(bootArtifact), bootArtifact);
            return Attribution.IDENTIFIED;
        }
        return firstPartyCandidate && FirstPartyArchives.isFirstParty(entryNames, packages)
                ? Attribution.FIRST_PARTY
                : Attribution.UNIDENTIFIED;
    }

    /** The repackaged archives opened during one census pass, each opened once and closed together. */
    private static final class OuterArchives implements AutoCloseable {

        private final Map<Path, JarFile> opened = new HashMap<>();

        private final Set<Path> failed = new HashSet<>();

        JarFile open(Path file) throws IOException {
            JarFile jar = opened.get(file);
            if (jar != null) {
                return jar;
            }
            if (failed.contains(file)) {
                throw new IOException("Unreadable archive " + file.getFileName());
            }
            try {
                jar = new JarFile(file.toFile(), false);
            } catch (IOException | RuntimeException ex) {
                failed.add(file);
                throw ex;
            }
            opened.put(file, jar);
            return jar;
        }

        @Override
        public void close() {
            for (JarFile jar : opened.values()) {
                try {
                    jar.close();
                } catch (IOException ex) {
                    // Nothing was written; a failed close does not affect the census result.
                }
            }
        }
    }

    /**
     * Identifies {@code spring-boot-jarmode-tools}, which Spring Boot's build plugins add to a packaged
     * application at packaging time: it is not a declared dependency, so it is never in the SBOM, and it
     * publishes no Maven descriptor. It is deliberately the only archive identified from a manifest, because
     * its group is fixed and its manifest names it exactly; the file name, {@code Implementation-Title}, and
     * {@code Implementation-Version} must all agree.
     */
    static DependencyDto bootArtifact(String archive, Manifest manifest) {
        if (manifest == null) {
            return null;
        }
        String title = BlankStrings.blankToNullTrimmed(
                manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_TITLE));
        String version = BlankStrings.blankToNullTrimmed(
                manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION));
        if (!JARMODE_TOOLS_TITLE.equals(title)
                || version == null
                || !archive.equals(JARMODE_TOOLS_ARTIFACT_ID + "-" + version + ".jar")) {
            return null;
        }
        return new DependencyDto(
                SPRING_BOOT_GROUP_ID,
                JARMODE_TOOLS_ARTIFACT_ID,
                version,
                SPRING_BOOT_GROUP_ID + ":" + JARMODE_TOOLS_ARTIFACT_ID,
                BOOT_MANIFEST_SOURCE,
                0,
                "NONE",
                List.of(),
                DependencyAssessmentDto.unknown());
    }

    /**
     * One archive of the census: its bare file name, the file it is read from, for a library nested in a
     * repackaged archive its entry name inside that file, and whether a Spring Boot layers index places it in the
     * {@code application} layer ({@code null} when no index describes it). {@code shadowed} marks a bare name
     * that another, different archive also carries: the census counts the name once, so inspecting only one copy
     * could vouch for code in the other, and such an archive is never first-party nor identified from its
     * manifest.
     */
    private record CensusArchive(
            String name, Path file, String nestedEntry, Boolean applicationLayer, boolean shadowed) {

        CensusArchive(String name, Path file, String nestedEntry, Boolean applicationLayer) {
            this(name, file, nestedEntry, applicationLayer, false);
        }

        boolean samePlace(CensusArchive other) {
            return file.equals(other.file) && java.util.Objects.equals(nestedEntry, other.nestedEntry);
        }

        CensusArchive asShadowed() {
            return new CensusArchive(name, file, nestedEntry, applicationLayer, true);
        }
    }

    private static void addToCensus(Map<String, CensusArchive> archives, CensusArchive archive) {
        CensusArchive existing = archives.get(archive.name());
        if (existing == null) {
            archives.put(archive.name(), archive);
        } else if (!existing.samePlace(archive) && !existing.shadowed()) {
            archives.put(archive.name(), existing.asShadowed());
        }
    }

    /**
     * The distinct JAR archives the application actually runs with, or an empty list when they cannot be
     * enumerated from either {@code java.class.path} or the application's classloader,
     * which is reported as unknown coverage rather than as a clean bill of health.
     */
    private List<CensusArchive> archiveCensus() {
        Map<String, CensusArchive> archives = new LinkedHashMap<>();
        Map<Path, Optional<LayersIndex>> explodedIndexes = new HashMap<>();
        for (Path entry : archiveEntries()) {
            if (Files.isDirectory(entry)) {
                continue;
            }
            List<CensusArchive> nested = nestedLibraries(entry);
            if (nested != null) {
                // A repackaged archive is the application's own, not a third-party dependency: its nested
                // libraries are the real dependency set, and the outer archive is deliberately not counted.
                // This is also why the entry is inspected before its extension is considered: an executable
                // WAR is a classpath entry that is not itself a JAR.
                for (CensusArchive archive : nested) {
                    addToCensus(archives, archive);
                }
                continue;
            }
            String archive = ArchiveNames.jarFileName(entry.toString());
            if (archive != null) {
                addToCensus(
                        archives,
                        new CensusArchive(
                                archive, entry, null, explodedApplicationLayer(entry, archive, explodedIndexes)));
            }
        }
        return List.copyOf(archives.values());
    }

    private Set<Path> archiveEntries() {
        Set<Path> entries = new LinkedHashSet<>();
        String classPath = System.getProperty("java.class.path", "");
        for (String entry : classPath.split(Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank()) {
                try {
                    entries.add(Path.of(entry.trim()).toAbsolutePath().normalize());
                } catch (IllegalArgumentException | SecurityException ex) {
                    LOGGER.log(
                            System.Logger.Level.DEBUG,
                            "Could not resolve classpath archive {0}: {1}",
                            entry,
                            ex.getMessage());
                }
            }
        }
        // An exploded JarLauncher adds library URLs to its loader, not to java.class.path.
        try {
            for (ClassLoader loader = resolver.getClassLoader(); loader != null; loader = loader.getParent()) {
                if (!(loader instanceof URLClassLoader urlLoader)) {
                    continue;
                }
                for (URL url : urlLoader.getURLs()) {
                    if (!"file".equals(url.getProtocol())
                            || (url.getAuthority() != null
                                    && !url.getAuthority().isEmpty())) {
                        continue;
                    }
                    try {
                        entries.add(Path.of(url.toURI()).toAbsolutePath().normalize());
                    } catch (URISyntaxException | IllegalArgumentException | SecurityException ex) {
                        LOGGER.log(
                                System.Logger.Level.DEBUG,
                                "Could not resolve classloader archive {0}: {1}",
                                url,
                                ex.getMessage());
                    }
                }
            }
        } catch (SecurityException ex) {
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "Could not enumerate application classloader archives: {0}",
                    ex.getMessage());
        }
        return entries;
    }

    /**
     * The {@code BOOT-INF/lib/} (or {@code WEB-INF/lib/}) archive names of a repackaged Spring Boot archive,
     * or {@code null} when {@code entry} is an ordinary library JAR, is unreadable, or does not exist.
     *
     * <p>Only the archive's central directory and manifest are read; nothing is extracted or decompressed,
     * and nesting is not recursed into, matching how Spring Boot repackaging actually lays an archive
     * out.</p>
     */
    private List<CensusArchive> nestedLibraries(Path entry) {
        File file = entry.toFile();
        if (!file.isFile()) {
            return null;
        }
        try (JarFile jarFile = new JarFile(file, false)) {
            List<String> prefixes = nestedLibraryPrefixes(jarFile);
            LayersIndex layers = null;
            List<CensusArchive> nested = new ArrayList<>();
            boolean layersRead = false;
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry jarEntry = entries.nextElement();
                if (jarEntry.isDirectory()) {
                    continue;
                }
                String name = jarEntry.getName();
                for (String prefix : prefixes) {
                    if (name.startsWith(prefix) && name.indexOf('/', prefix.length()) < 0) {
                        String archive = ArchiveNames.jarFileName(name);
                        if (archive != null) {
                            if (!layersRead) {
                                layers = repackagedLayersIndex(jarFile);
                                layersRead = true;
                            }
                            nested.add(new CensusArchive(
                                    archive, entry, name, layers == null ? null : layers.isApplication(name)));
                        }
                        break;
                    }
                }
            }
            return nested.isEmpty() ? null : nested;
        } catch (IOException | RuntimeException ex) {
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "Could not inspect classpath archive {0} for nested libraries: {1}",
                    entry,
                    ex.getMessage());
            return null;
        }
    }

    /**
     * The layers index of a repackaged archive, {@code null} when it has none, or {@link LayersIndex#UNREADABLE}
     * when it has one that cannot be read.
     */
    private static LayersIndex repackagedLayersIndex(JarFile jarFile) {
        try {
            Manifest manifest = jarFile.getManifest();
            String declared = manifest == null
                    ? null
                    : BlankStrings.blankToNullTrimmed(
                            manifest.getMainAttributes().getValue(SPRING_BOOT_LAYERS_INDEX));
            for (String location : declared != null ? List.of(declared) : DEFAULT_LAYERS_INDEXES) {
                JarEntry index = jarFile.getJarEntry(location);
                if (index != null) {
                    try (InputStream input = jarFile.getInputStream(index)) {
                        return LayersIndex.read(input);
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            LOGGER.log(System.Logger.Level.DEBUG, "Could not read the layers index: {0}", ex.getMessage());
            return LayersIndex.UNREADABLE;
        }
        return null;
    }

    /**
     * Whether an extracted {@code BOOT-INF/lib/} archive is in the application layer of a Spring Boot layers
     * index, or {@code null} when the archive is not in an extracted layout or no index describes it.
     *
     * <p>Two layouts are recognized. When the layers of {@code jarmode=tools extract --layers} are copied into one
     * tree, as a layered image build does, the index sits next to the library at {@code BOOT-INF/layers.idx}.
     * When the extraction is used in place, each layer is its own {@code <layer>/BOOT-INF/} directory and only the
     * {@code application} one holds the index, so a library under a sibling layer directory is looked up in that
     * sibling's index and qualifies only if it is itself under {@code application/}.</p>
     */
    private static Boolean explodedApplicationLayer(Path jar, String archive, Map<Path, Optional<LayersIndex>> cache) {
        Path lib = jar.getParent();
        Path bootInf = lib == null ? null : lib.getParent();
        if (bootInf == null
                || lib.getFileName() == null
                || bootInf.getFileName() == null
                || !"lib".equalsIgnoreCase(lib.getFileName().toString())) {
            return null;
        }
        // Compared without case: a case-insensitive file system resolves a differently cased path to the same
        // extracted layout, and the index is always consulted under its canonical entry name.
        String infDirectory = bootInf.getFileName().toString().toUpperCase(Locale.ROOT);
        if (!"BOOT-INF".equals(infDirectory) && !"WEB-INF".equals(infDirectory)) {
            return null;
        }
        String entryName = infDirectory + "/lib/" + archive;
        Optional<LayersIndex> adjacent = cache.computeIfAbsent(bootInf, DependencyCatalog::explodedLayersIndex);
        if (adjacent.isPresent()) {
            return adjacent.get().isApplication(entryName);
        }
        Path layer = bootInf.getParent();
        Path root = layer == null ? null : layer.getParent();
        if (root == null || layer.getFileName() == null) {
            return null;
        }
        Optional<LayersIndex> sibling = cache.computeIfAbsent(
                root.resolve(LayersIndex.APPLICATION_LAYER).resolve(bootInf.getFileName()),
                DependencyCatalog::explodedLayersIndex);
        if (sibling.isEmpty()) {
            return null;
        }
        Boolean application = sibling.get().isApplication(entryName);
        return application == null
                ? null
                : application
                        && LayersIndex.APPLICATION_LAYER.equals(
                                layer.getFileName().toString());
    }

    /** The index in {@code bootInf}, empty when there is none, or {@link LayersIndex#UNREADABLE}. */
    private static Optional<LayersIndex> explodedLayersIndex(Path bootInf) {
        Path index = bootInf.resolve("layers.idx");
        if (!Files.isRegularFile(index)) {
            return Optional.empty();
        }
        try (InputStream input = Files.newInputStream(index)) {
            return Optional.of(LayersIndex.read(input));
        } catch (IOException | RuntimeException ex) {
            LOGGER.log(System.Logger.Level.DEBUG, "Could not read the layers index {0}: {1}", index, ex.getMessage());
            return Optional.of(LayersIndex.UNREADABLE);
        }
    }

    private static List<String> nestedLibraryPrefixes(JarFile jarFile) {
        try {
            Manifest manifest = jarFile.getManifest();
            String declared = manifest == null
                    ? null
                    : BlankStrings.blankToNullTrimmed(
                            manifest.getMainAttributes().getValue(SPRING_BOOT_LIB_ATTRIBUTE));
            if (declared != null) {
                return List.of(declared.endsWith("/") ? declared : declared + "/");
            }
        } catch (IOException | RuntimeException ex) {
            // Fall through to the conventional prefixes; an unreadable manifest is not an error here.
        }
        return DEFAULT_NESTED_LIBRARY_PREFIXES;
    }

    /**
     * The archive a classpath descriptor was read from, parsed out of its resource URL, or {@code null} for
     * a descriptor on an exploded directory classpath. This attributes a descriptor to its archive exactly,
     * rather than inferring it from coordinates that a shaded archive may not match.
     */
    private String owningArchive(Resource resource) {
        String location = location(resource);
        if (location == null) {
            return null;
        }
        int marker = location.indexOf(MAVEN_METADATA_MARKER);
        if (marker < 0) {
            return null;
        }
        return ArchiveNames.jarFileName(location.substring(0, marker));
    }

    /**
     * The resource's location as text. Spring Boot 3.2+ addresses a descriptor inside a repackaged archive
     * with a {@code jar:nested:} URL, which only resolves as a {@code URL} while Boot's protocol handler is
     * registered; falling back to the URI keeps attribution working regardless.
     */
    private static String location(Resource resource) {
        try {
            return resource.getURL().toString();
        } catch (IOException | RuntimeException ex) {
            // Not URL-addressable; try the URI below.
        }
        try {
            return resource.getURI().toString();
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    // -----------------------------------------------------------------------------------------------
    // CycloneDX SBOM
    // -----------------------------------------------------------------------------------------------

    /**
     * Reads the application's embedded CycloneDX SBOM, taking one dependency per Maven {@code purl}. This is
     * the only local source able to resolve the {@code groupId} of an artifact published without a Maven
     * descriptor, so it is what closes the classpath gap on a repackaged application.
     */
    private List<DependencyDto> sbomDependencies() {
        Map<String, DependencyDto> dependencies = new LinkedHashMap<>();
        for (String pattern : SBOM_PATTERNS) {
            for (Resource resource : resources(pattern)) {
                readSbom(resource, dependencies);
            }
        }
        return List.copyOf(dependencies.values());
    }

    private void readSbom(Resource resource, Map<String, DependencyDto> dependencies) {
        JsonNode root;
        try (InputStream input = resource.getInputStream()) {
            root = objectMapper.readTree(input);
        } catch (IOException | RuntimeException ex) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Could not read the CycloneDX SBOM from {0}; continuing without it: {1}",
                    resource.getDescription(),
                    ex.getMessage());
            return;
        }
        if (root == null || !root.isObject()) {
            return;
        }
        // The BOM's own metadata.component is the application itself, not one of its dependencies, so only
        // the components array is read.
        collectSbomComponents(root.get("components"), dependencies);
    }

    private void collectSbomComponents(JsonNode components, Map<String, DependencyDto> dependencies) {
        if (components == null || !components.isArray()) {
            return;
        }
        for (JsonNode component : components) {
            if (dependencies.size() >= MAX_SBOM_COMPONENTS) {
                return;
            }
            if (!component.isObject()) {
                continue;
            }
            JsonNode purl = component.get("purl");
            PackageUrls.MavenCoordinates coordinates =
                    purl == null || !purl.isString() ? null : PackageUrls.mavenCoordinates(purl.stringValue());
            if (coordinates != null) {
                dependencies.putIfAbsent(
                        coordinates.packageName() + ":" + coordinates.version(),
                        new DependencyDto(
                                coordinates.groupId(),
                                coordinates.artifactId(),
                                coordinates.version(),
                                coordinates.packageName(),
                                "CycloneDX SBOM",
                                0,
                                "NONE",
                                List.of(),
                                DependencyAssessmentDto.unknown()));
            }
            // CycloneDX allows a component to nest the components it in turn assembles.
            collectSbomComponents(component.get("components"), dependencies);
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Maven descriptors and the java.class.path
    // -----------------------------------------------------------------------------------------------

    private Resource[] resources(String pattern) {
        try {
            return resolver.getResources(pattern);
        } catch (IOException | RuntimeException ex) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Could not resolve {0} from the classpath; continuing without it: {1}",
                    pattern,
                    ex.getMessage());
            return new Resource[0];
        }
    }

    private DependencyDto dependency(Resource resource) {
        Properties properties = new Properties();
        try (InputStream input = resource.getInputStream()) {
            properties.load(input);
        } catch (IOException ex) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Could not read Maven metadata from {0}; skipping it: {1}",
                    resource.getDescription(),
                    ex.getMessage());
            return null;
        }
        String groupId = BlankStrings.blankToNullTrimmed(properties.getProperty("groupId"));
        String artifactId = BlankStrings.blankToNullTrimmed(properties.getProperty("artifactId"));
        String version = BlankStrings.blankToNullTrimmed(properties.getProperty("version"));
        if (groupId == null || artifactId == null || version == null) {
            return null;
        }
        String packageName = groupId + ":" + artifactId;
        return new DependencyDto(
                groupId,
                artifactId,
                version,
                packageName,
                "Maven metadata",
                0,
                "NONE",
                List.of(),
                DependencyAssessmentDto.unknown());
    }

    private List<DependencyDto> javaClassPathDependencies() {
        String classPath = System.getProperty("java.class.path", "");
        if (classPath.isBlank()) {
            return List.of();
        }
        return List.of(classPath.split(Pattern.quote(File.pathSeparator))).stream()
                .map(this::dependencyFromClassPathEntry)
                .filter(dependency -> dependency != null)
                .toList();
    }

    private DependencyDto dependencyFromClassPathEntry(String entry) {
        if (entry == null || !entry.endsWith(".jar")) {
            return null;
        }
        Path jar = Path.of(entry).toAbsolutePath().normalize();
        Path versionPath = jar.getParent();
        Path artifactPath = versionPath == null ? null : versionPath.getParent();
        if (artifactPath == null || artifactPath.getParent() == null) {
            return null;
        }
        String artifactId = artifactPath.getFileName().toString();
        String version = versionPath.getFileName().toString();
        String fileName = jar.getFileName().toString();
        String expectedBaseName = artifactId + "-" + version;
        boolean mainArtifact = fileName.equals(expectedBaseName + ".jar");
        boolean classifiedArtifact =
                fileName.startsWith(expectedBaseName + "-") && fileName.length() > expectedBaseName.length() + 5;
        if ((!mainArtifact && !classifiedArtifact) || !fileName.endsWith(".jar")) {
            return null;
        }
        DependencyDto pomDependency = dependencyFromAdjacentPom(versionPath, artifactId, version);
        if (pomDependency != null) {
            return pomDependency;
        }
        String groupId = groupId(artifactPath.getParent());
        if (groupId == null) {
            return null;
        }
        String packageName = groupId + ":" + artifactId;
        return new DependencyDto(
                groupId,
                artifactId,
                version,
                packageName,
                "Java classpath",
                0,
                "NONE",
                List.of(),
                DependencyAssessmentDto.unknown());
    }

    private DependencyDto dependencyFromAdjacentPom(Path versionPath, String artifactId, String version) {
        Path pom = versionPath.resolve(artifactId + "-" + version + ".pom");
        if (!Files.isRegularFile(pom)) {
            return null;
        }
        try (InputStream input = Files.newInputStream(pom)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Element project = factory.newDocumentBuilder().parse(input).getDocumentElement();
            String pomArtifactId = childText(project, "artifactId");
            String pomGroupId = childText(project, "groupId");
            String pomVersion = childText(project, "version");
            Element parent = child(project, "parent");
            if (pomGroupId == null && parent != null) {
                pomGroupId = childText(parent, "groupId");
            }
            if (pomVersion == null && parent != null) {
                pomVersion = childText(parent, "version");
            }
            if (!artifactId.equals(pomArtifactId)
                    || !version.equals(pomVersion)
                    || pomGroupId == null
                    || pomGroupId.contains("${")) {
                return null;
            }
            String packageName = pomGroupId + ":" + artifactId;
            return new DependencyDto(
                    pomGroupId,
                    artifactId,
                    version,
                    packageName,
                    "Adjacent Maven POM",
                    0,
                    "NONE",
                    List.of(),
                    DependencyAssessmentDto.unknown());
        } catch (IOException | ParserConfigurationException | SAXException | IllegalArgumentException ex) {
            return null;
        }
    }

    private static String childText(Element parent, String name) {
        Element child = child(parent, name);
        return child == null ? null : BlankStrings.blankToNullTrimmed(child.getTextContent());
    }

    private static Element child(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element
                    && (name.equals(element.getLocalName()) || name.equals(element.getNodeName()))) {
                return element;
            }
        }
        return null;
    }

    private String groupId(Path groupPath) {
        int repositoryIndex = -1;
        for (int i = groupPath.getNameCount() - 1; i >= 0; i--) {
            if ("repository".equals(groupPath.getName(i).toString())) {
                repositoryIndex = i;
                break;
            }
        }
        if (repositoryIndex < 0 || repositoryIndex + 1 >= groupPath.getNameCount()) {
            return null;
        }
        StringBuilder groupId = new StringBuilder();
        for (int i = repositoryIndex + 1; i < groupPath.getNameCount(); i++) {
            if (!groupId.isEmpty()) {
                groupId.append('.');
            }
            groupId.append(groupPath.getName(i));
        }
        return groupId.toString();
    }
}
