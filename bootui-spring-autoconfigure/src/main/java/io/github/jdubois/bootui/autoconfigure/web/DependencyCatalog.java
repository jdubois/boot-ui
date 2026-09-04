package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.DependencyCoverageDto;
import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.engine.support.BlankStrings;
import io.github.jdubois.bootui.engine.vulnerabilities.ArchiveNames;
import io.github.jdubois.bootui.engine.vulnerabilities.DependencyInventory;
import io.github.jdubois.bootui.engine.vulnerabilities.DependencyProvider;
import io.github.jdubois.bootui.engine.vulnerabilities.PackageUrls;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
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
 * entries inside a repackaged JAR or WAR, classpath JARs otherwise) and reports every archive it could not
 * attribute to a resolved coordinate as {@link DependencyCoverageDto#INCOMPLETE} coverage. A scan covering
 * part of the classpath is then visibly partial instead of rendering as a green, full-coverage result. When
 * the census itself cannot run &mdash; a blank or synthetic {@code java.class.path}, as under a native image
 * &mdash; coverage is reported {@link DependencyCoverageDto#UNAVAILABLE} rather than assumed complete.</p>
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

    /** Upper bound on the archive names carried in the report; the reported counts stay exact regardless. */
    static final int MAX_UNIDENTIFIED_ARCHIVES = 200;

    /** Upper bound on SBOM components read, so a pathological BOM cannot stall a panel load. */
    private static final int MAX_SBOM_COMPONENTS = 10_000;

    private static final System.Logger LOGGER = System.getLogger(DependencyCatalog.class.getName());

    private final ResourcePatternResolver resolver;

    private final ObjectMapper objectMapper = new ObjectMapper();

    DependencyCatalog() {
        this(new PathMatchingResourcePatternResolver());
    }

    DependencyCatalog(ResourcePatternResolver resolver) {
        this.resolver = resolver;
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

        List<DependencyDto> resolved = dependencies.values().stream()
                .sorted(Comparator.comparing(DependencyDto::packageName).thenComparing(DependencyDto::version))
                .toList();
        return new DependencyInventory(resolved, coverage(resolved, identifiedArchives));
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
     */
    private DependencyCoverageDto coverage(List<DependencyDto> resolved, Set<String> identifiedArchives) {
        List<String> archives = archiveCensus();
        if (archives.isEmpty()) {
            return DependencyCoverageDto.unavailable();
        }
        List<String> unidentified = new ArrayList<>();
        for (String archive : archives) {
            if (!identifiedArchives.contains(archive) && !isIdentified(archive, resolved)) {
                unidentified.add(archive);
            }
        }
        unidentified.sort(String.CASE_INSENSITIVE_ORDER);
        return DependencyCoverageDto.of(
                archives.size(),
                unidentified.size(),
                unidentified.stream().limit(MAX_UNIDENTIFIED_ARCHIVES).toList());
    }

    private static boolean isIdentified(String archive, List<DependencyDto> resolved) {
        for (DependencyDto dependency : resolved) {
            if (ArchiveNames.matches(archive, dependency.artifactId(), dependency.version())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The distinct JAR archives the application actually runs with, or an empty list when they cannot be
     * enumerated (a blank {@code java.class.path}, as under a native image or a non-standard launcher),
     * which is reported as unknown coverage rather than as a clean bill of health.
     */
    private List<String> archiveCensus() {
        String classPath = System.getProperty("java.class.path", "");
        if (classPath.isBlank()) {
            return List.of();
        }
        Set<String> archives = new LinkedHashSet<>();
        for (String entry : classPath.split(Pattern.quote(File.pathSeparator))) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            List<String> nested = nestedLibraries(trimmed);
            if (nested != null) {
                // A repackaged archive is the application's own, not a third-party dependency: its nested
                // libraries are the real dependency set, and the outer archive is deliberately not counted.
                // This is also why the entry is inspected before its extension is considered: an executable
                // WAR is a classpath entry that is not itself a JAR.
                archives.addAll(nested);
                continue;
            }
            String archive = ArchiveNames.jarFileName(trimmed);
            if (archive != null) {
                archives.add(archive);
            }
        }
        return List.copyOf(archives);
    }

    /**
     * The {@code BOOT-INF/lib/} (or {@code WEB-INF/lib/}) archive names of a repackaged Spring Boot archive,
     * or {@code null} when {@code entry} is an ordinary library JAR, is unreadable, or does not exist.
     *
     * <p>Only the archive's central directory and manifest are read; nothing is extracted or decompressed,
     * and nesting is not recursed into, matching how Spring Boot repackaging actually lays an archive
     * out.</p>
     */
    private List<String> nestedLibraries(String entry) {
        File file = new File(entry);
        if (!file.isFile()) {
            return null;
        }
        try (JarFile jarFile = new JarFile(file)) {
            List<String> prefixes = nestedLibraryPrefixes(jarFile);
            List<String> nested = new ArrayList<>();
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
                            nested.add(archive);
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
                                List.of()));
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
        return new DependencyDto(groupId, artifactId, version, packageName, "Maven metadata", 0, "NONE", List.of());
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
        return new DependencyDto(groupId, artifactId, version, packageName, "Java classpath", 0, "NONE", List.of());
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
                    pomGroupId, artifactId, version, packageName, "Adjacent Maven POM", 0, "NONE", List.of());
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
