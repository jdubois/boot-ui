package io.github.jdubois.bootui.engine.staticsecurity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Detects declarations for a small, known set of static security analyzers without running them.
 *
 * <p>Only bounded local project and repository configuration is inspected. Incomplete or unsafe evidence is
 * indeterminate rather than evidence that an analyzer is absent.</p>
 */
public final class StaticSecurityAnalysisDetector {

    public static final String RULE_ID = "SEC-STATIC-001";

    private static final int MAX_FILE_BYTES = 1_048_576;
    private static final int MAX_PARENT_POMS = 5;
    private static final int MAX_WORKFLOW_FILES = 64;
    private static final int GRADLE_PLUGIN_CONTEXT_WINDOW = 128;
    private static final String GRADLE_ANALYZER_IDS =
            "(?:com\\.github\\.spotbugs|org\\.sonarqube|org\\.sonarsource\\.scanner\\.gradle\\.sonarqube)";
    private static final Pattern KNOWN_GRADLE_PLUGIN = Pattern.compile("(?i)(?:\\bid\\s*\\(?\\s*['\"]"
            + GRADLE_ANALYZER_IDS + "['\"]|\\bapply\\s+plugin\\s*:\\s*['\"]" + GRADLE_ANALYZER_IDS
            + "['\"]|\\bapply\\s*\\(\\s*plugin\\s*[:=]\\s*['\"]" + GRADLE_ANALYZER_IDS + "['\"])");
    private static final Pattern GRADLE_PLUGIN_STRING_PREFIX =
            Pattern.compile("(?i)(?:\\bid\\s*\\(\\s*|\\bid\\s+|\\bapply\\s+plugin\\s*:\\s*|"
                    + "\\bapply\\s*\\(\\s*plugin\\s*[:=]\\s*)$");
    private static final Pattern GRADLE_PLUGIN_ALIAS =
            Pattern.compile("\\balias\\s*\\(\\s*libs\\.plugins\\.([A-Za-z0-9_.-]+)");
    private static final Pattern GRADLE_ANY_PLUGIN_ALIAS =
            Pattern.compile("\\balias\\s*\\(\\s*([A-Za-z0-9_]+)\\.plugins\\.");
    private static final Pattern GRADLE_UNINSPECTED_INTEGRATION =
            Pattern.compile("(?i)(?:\\bapply\\s+from\\s*:|\\bincludeBuild\\s*\\()");
    private static final Pattern KNOWN_CATALOG_PLUGIN =
            Pattern.compile("(?i)(?:\\bid\\s*=\\s*['\"]|=\\s*['\"])" + GRADLE_ANALYZER_IDS + "(?:['\":])");
    private static final Pattern KNOWN_WORKFLOW_ACTION =
            Pattern.compile("(?i)^(?:github/codeql-action/(?:init|analyze)@\\S+|"
                    + "(?:semgrep|returntocorp)/semgrep-action\\S*|"
                    + "sonarsource/(?:sonarqube-scan-action|sonarcloud-github-action)\\S*)$");
    private static final Pattern ENVIRONMENT_ASSIGNMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=.*");
    private static final List<Path> SEMGREP_CONFIGS = List.of(
            Path.of(".semgrep.yml"),
            Path.of(".semgrep.yaml"),
            Path.of("semgrep.yml"),
            Path.of("semgrep.yaml"),
            Path.of(".semgrep", "semgrep.yml"),
            Path.of(".semgrep", "config.yml"));

    public enum State {
        NOT_APPLICABLE,
        CONFIGURED,
        NOT_CONFIGURED,
        INDETERMINATE
    }

    public record Inspection(State state) {
        public Inspection {
            if (state == null) {
                throw new IllegalArgumentException("Static analyzer inspection state is required.");
            }
        }

        public boolean evaluated() {
            return state == State.CONFIGURED || state == State.NOT_CONFIGURED;
        }
    }

    public Inspection inspect(Path projectRoot) {
        if (projectRoot == null) {
            return new Inspection(State.NOT_APPLICABLE);
        }
        try {
            Path root = projectRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
                return new Inspection(State.NOT_APPLICABLE);
            }

            List<Path> buildFiles = buildFiles(root);
            if (buildFiles.isEmpty()) {
                return new Inspection(State.NOT_APPLICABLE);
            }

            Path repositoryRoot = repositoryRoot(root);
            boolean indeterminate = false;
            for (Path buildFile : buildFiles) {
                if (buildFile.getFileName().toString().equals("pom.xml")) {
                    MavenInspection inspection = inspectMaven(buildFile, repositoryRoot);
                    if (inspection.configured()) {
                        return new Inspection(State.CONFIGURED);
                    }
                    indeterminate |= inspection.indeterminate();
                } else {
                    TextInspection inspection = inspectGradle(buildFile, repositoryRoot);
                    if (inspection.configured()) {
                        return new Inspection(State.CONFIGURED);
                    }
                    indeterminate |= inspection.indeterminate();
                }
            }

            for (Path candidate : repositoryRoots(root, repositoryRoot)) {
                for (Path config : SEMGREP_CONFIGS) {
                    FileInspection inspection = inspectDeclaredFile(repositoryRoot, candidate.resolve(config));
                    if (inspection == FileInspection.PRESENT) {
                        return new Inspection(State.CONFIGURED);
                    }
                    indeterminate |= inspection == FileInspection.INDETERMINATE;
                }

                FileInspection sonar =
                        inspectDeclaredFile(repositoryRoot, candidate.resolve("sonar-project.properties"));
                if (sonar == FileInspection.PRESENT) {
                    return new Inspection(State.CONFIGURED);
                }
                indeterminate |= sonar == FileInspection.INDETERMINATE;

                for (String name : List.of("build.gradle", "build.gradle.kts")) {
                    Path gradleBuild = candidate.resolve(name);
                    if (!Files.exists(gradleBuild, LinkOption.NOFOLLOW_LINKS) || root.equals(candidate)) {
                        continue;
                    }
                    if (!Files.isRegularFile(gradleBuild, LinkOption.NOFOLLOW_LINKS)
                            || Files.isSymbolicLink(gradleBuild)) {
                        indeterminate = true;
                        continue;
                    }
                    TextInspection inspection = inspectGradle(gradleBuild, repositoryRoot);
                    if (inspection.configured()) {
                        return new Inspection(State.CONFIGURED);
                    }
                    indeterminate |= inspection.indeterminate();
                }

                WorkflowInspection workflows = inspectWorkflows(repositoryRoot, candidate.resolve(".github/workflows"));
                if (workflows.configured()) {
                    return new Inspection(State.CONFIGURED);
                }
                indeterminate |= workflows.indeterminate();
            }

            return new Inspection(indeterminate ? State.INDETERMINATE : State.NOT_CONFIGURED);
        } catch (RuntimeException | LinkageError | javax.xml.parsers.FactoryConfigurationError ex) {
            return new Inspection(State.INDETERMINATE);
        }
    }

    private static List<Path> buildFiles(Path root) {
        List<Path> files = new ArrayList<>();
        for (String name : List.of("pom.xml", "build.gradle", "build.gradle.kts")) {
            Path candidate = root.resolve(name);
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                files.add(candidate);
            }
        }
        return files;
    }

    private static Path repositoryRoot(Path projectRoot) {
        Path current = projectRoot;
        for (int depth = 0; current != null && depth <= MAX_PARENT_POMS; depth++) {
            Path marker = current.resolve(".git");
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(marker)) {
                return current;
            }
            current = current.getParent();
        }
        return projectRoot;
    }

    private static List<Path> repositoryRoots(Path projectRoot, Path repositoryRoot) {
        return repositoryRoot.equals(projectRoot) ? List.of(projectRoot) : List.of(projectRoot, repositoryRoot);
    }

    /** A declared {@code spotbugs-maven-plugin} version too old to read the project's Java release. */
    public record SpotBugsCompatibility(String declaredCore, String minimumCore, int javaRelease) {}

    /**
     * Checks whether a declared Maven {@code spotbugs-maven-plugin} version can analyze the project's Java release.
     *
     * <p>Returns empty when no concrete plugin version is declared, the version or Java release cannot be resolved from
     * bounded local files, or the release is outside the range with a known minimum — so this never raises a false
     * warning. See {@link SpotBugsJavaCompatibility} for the sourced thresholds.
     */
    public Optional<SpotBugsCompatibility> inspectSpotBugsCompatibility(Path projectRoot) {
        if (projectRoot == null) {
            return Optional.empty();
        }
        try {
            Path root = projectRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
                return Optional.empty();
            }
            Path repositoryRoot = repositoryRoot(root);
            for (Path buildFile : buildFiles(root)) {
                if (!buildFile.getFileName().toString().equals("pom.xml")) {
                    continue;
                }
                Optional<SpotBugsCompatibility> result = inspectSpotBugsPom(buildFile, repositoryRoot);
                if (result.isPresent()) {
                    return result;
                }
            }
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<SpotBugsCompatibility> inspectSpotBugsPom(Path initialPom, Path repositoryRoot) {
        Map<String, String> properties = new HashMap<>();
        String declaredVersion = null;
        Path pom = initialPom;
        List<Path> visited = new ArrayList<>();
        for (int depth = 0; depth < MAX_PARENT_POMS; depth++) {
            if (pom == null || visited.contains(pom)) {
                break;
            }
            visited.add(pom);
            Document document;
            try {
                document = parsePom(pom);
            } catch (IOException | ParserConfigurationException | SAXException ex) {
                break;
            }
            mergeProperties(document, properties);
            if (declaredVersion == null) {
                declaredVersion = spotBugsPluginVersion(document);
            }
            pom = localParentPom(document, pom, repositoryRoot);
        }
        if (declaredVersion == null) {
            return Optional.empty();
        }
        String resolvedVersion = resolveProperty(declaredVersion, properties);
        String releaseRaw = properties.get("maven.compiler.release");
        if (releaseRaw == null) {
            releaseRaw = properties.get("maven.compiler.target");
        }
        if (releaseRaw == null) {
            releaseRaw = properties.get("java.version");
        }
        int javaRelease = parseJavaRelease(resolveProperty(releaseRaw, properties));
        return SpotBugsJavaCompatibility.evaluate(resolvedVersion, javaRelease)
                .map(result -> new SpotBugsCompatibility(result.declaredCore(), result.minimumCore(), javaRelease));
    }

    private static void mergeProperties(Document document, Map<String, String> into) {
        NodeList properties = document.getElementsByTagNameNS("*", "properties");
        for (int i = 0; i < properties.getLength(); i++) {
            if (!(properties.item(i) instanceof Element propertiesElement)) {
                continue;
            }
            for (Node child = propertiesElement.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element element) {
                    into.putIfAbsent(
                            localName(element), element.getTextContent().strip());
                }
            }
        }
    }

    private static String spotBugsPluginVersion(Document document) {
        NodeList plugins = document.getElementsByTagNameNS("*", "plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            if (!(plugins.item(i) instanceof Element plugin)) {
                continue;
            }
            if (text(directChild(plugin, "artifactId")).equals("spotbugs-maven-plugin")) {
                String version = text(directChild(plugin, "version"));
                if (!version.isEmpty()) {
                    return version;
                }
            }
        }
        return null;
    }

    private static Path localParentPom(Document document, Path pom, Path repositoryRoot) {
        Element parent = directChild(document.getDocumentElement(), "parent");
        if (parent == null) {
            return null;
        }
        Element relativePathElement = directChild(parent, "relativePath");
        String relativePath = relativePathElement == null
                ? "../pom.xml"
                : relativePathElement.getTextContent().strip();
        if (relativePath.isEmpty()) {
            return null;
        }
        Path parentPom = pom.getParent().resolve(relativePath).normalize();
        if (!parentPom.startsWith(repositoryRoot)) {
            return null;
        }
        if (Files.isDirectory(parentPom, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(parentPom)) {
            parentPom = parentPom.resolve("pom.xml");
        }
        if (!Files.isRegularFile(parentPom, LinkOption.NOFOLLOW_LINKS)
                || containsSymbolicLink(repositoryRoot, parentPom)) {
            return null;
        }
        return parentPom;
    }

    private static String resolveProperty(String value, Map<String, String> properties) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            String key = trimmed.substring(2, trimmed.length() - 1);
            return properties.get(key);
        }
        return trimmed;
    }

    private static int parseJavaRelease(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String release = value.strip();
        if (release.startsWith("1.")) {
            release = release.substring(2);
        }
        try {
            return Integer.parseInt(release);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static MavenInspection inspectMaven(Path initialPom, Path repositoryRoot) {
        Path pom = initialPom;
        List<Path> visited = new ArrayList<>();
        MavenCoordinate expectedParent = null;
        for (int depth = 0; depth < MAX_PARENT_POMS; depth++) {
            if (visited.contains(pom)) {
                return new MavenInspection(false, true);
            }
            visited.add(pom);
            Document document;
            try {
                document = parsePom(pom);
            } catch (IOException | ParserConfigurationException | SAXException ex) {
                return new MavenInspection(false, true);
            }
            if (expectedParent != null) {
                MavenCoordinate actualParent = mavenProjectCoordinate(document);
                if (!actualParent.concrete() || !expectedParent.concrete()) {
                    return new MavenInspection(false, true);
                }
                if (!expectedParent.equals(actualParent)) {
                    return new MavenInspection(false, false);
                }
            }
            if (hasMavenAnalyzer(document)) {
                return new MavenInspection(true, false);
            }

            Element parent = directChild(document.getDocumentElement(), "parent");
            if (parent == null) {
                return new MavenInspection(false, false);
            }
            Element relativePathElement = directChild(parent, "relativePath");
            String relativePath = relativePathElement == null
                    ? "../pom.xml"
                    : relativePathElement.getTextContent().strip();
            if (relativePath.isEmpty()) {
                return new MavenInspection(false, false);
            }
            Path parentPom = pom.getParent().resolve(relativePath).normalize();
            if (!parentPom.startsWith(repositoryRoot)) {
                return new MavenInspection(
                        false, relativePathElement != null || Files.exists(parentPom, LinkOption.NOFOLLOW_LINKS));
            }
            if (relativePathElement == null && !Files.exists(parentPom, LinkOption.NOFOLLOW_LINKS)) {
                return new MavenInspection(false, false);
            }
            expectedParent = mavenCoordinate(parent);
            if (!expectedParent.concrete()) {
                return new MavenInspection(false, true);
            }
            if (Files.isDirectory(parentPom, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(parentPom)) {
                parentPom = parentPom.resolve("pom.xml");
            }
            if (!Files.isRegularFile(parentPom, LinkOption.NOFOLLOW_LINKS)
                    || containsSymbolicLink(repositoryRoot, parentPom)) {
                return new MavenInspection(false, true);
            }
            pom = parentPom;
        }
        return new MavenInspection(false, true);
    }

    private static MavenCoordinate mavenCoordinate(Element element) {
        return new MavenCoordinate(
                text(directChild(element, "groupId")),
                text(directChild(element, "artifactId")),
                text(directChild(element, "version")));
    }

    private static MavenCoordinate mavenProjectCoordinate(Document document) {
        Element project = document.getDocumentElement();
        MavenCoordinate declared = mavenCoordinate(project);
        Element parent = directChild(project, "parent");
        String groupId = declared.groupId().isEmpty() && parent != null
                ? text(directChild(parent, "groupId"))
                : declared.groupId();
        String version = declared.version().isEmpty() && parent != null
                ? text(directChild(parent, "version"))
                : declared.version();
        return new MavenCoordinate(groupId, declared.artifactId(), version);
    }

    private static Document parsePom(Path pom) throws IOException, ParserConfigurationException, SAXException {
        byte[] content = readBounded(pom);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(content));
    }

    private static boolean hasMavenAnalyzer(Document document) {
        NodeList plugins = document.getElementsByTagNameNS("*", "plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            Node node = plugins.item(i);
            if (!(node instanceof Element plugin)) {
                continue;
            }
            String artifactId = text(directChild(plugin, "artifactId")).toLowerCase(Locale.ROOT);
            if (artifactId.equals("spotbugs-maven-plugin")
                    || artifactId.contains("findsecbugs")
                    || artifactId.equals("sonar-maven-plugin")) {
                return true;
            }
            NodeList dependencies = plugin.getElementsByTagNameNS("*", "dependency");
            for (int dependencyIndex = 0; dependencyIndex < dependencies.getLength(); dependencyIndex++) {
                Node dependency = dependencies.item(dependencyIndex);
                if (dependency instanceof Element element
                        && text(directChild(element, "artifactId"))
                                .toLowerCase(Locale.ROOT)
                                .contains("findsecbugs")) {
                    return true;
                }
            }
        }
        NodeList properties = document.getElementsByTagNameNS("*", "properties");
        for (int i = 0; i < properties.getLength(); i++) {
            Node node = properties.item(i);
            if (!(node instanceof Element propertiesElement)) {
                continue;
            }
            for (Node child = propertiesElement.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element element && SetNames.SONAR_PROPERTIES.contains(localName(element))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static TextInspection inspectGradle(Path buildFile, Path repositoryRoot) {
        if (containsSymbolicLink(repositoryRoot, buildFile)) {
            return new TextInspection(false, true);
        }
        GradleText gradleText;
        try {
            gradleText = stripGradleComments(readText(buildFile));
        } catch (IOException ex) {
            return new TextInspection(false, true);
        }
        String text = gradleText.text();
        if (KNOWN_GRADLE_PLUGIN.matcher(text).find()) {
            return new TextInspection(true, false);
        }
        boolean indeterminate =
                gradleText.indeterminate() || hasUninspectedGradleConfiguration(buildFile, repositoryRoot, text);
        Set<String> aliases = new LinkedHashSet<>();
        var aliasMatcher = GRADLE_PLUGIN_ALIAS.matcher(text);
        while (aliasMatcher.find()) {
            aliases.add(normalizeCatalogAlias(aliasMatcher.group(1)));
        }
        var anyAliasMatcher = GRADLE_ANY_PLUGIN_ALIAS.matcher(text);
        while (anyAliasMatcher.find()) {
            indeterminate |= !anyAliasMatcher.group(1).equals("libs");
        }
        if (aliases.isEmpty()) {
            return new TextInspection(false, indeterminate);
        }
        Set<Path> catalogs = new LinkedHashSet<>(List.of(
                buildFile.getParent().resolve("gradle/libs.versions.toml"),
                repositoryRoot.resolve("gradle/libs.versions.toml")));
        boolean foundAlias = false;
        for (Path catalog : catalogs) {
            if (!Files.exists(catalog, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!Files.isRegularFile(catalog, LinkOption.NOFOLLOW_LINKS)
                    || containsSymbolicLink(repositoryRoot, catalog)) {
                indeterminate = true;
                continue;
            }
            try {
                CatalogInspection inspection = inspectCatalogPlugins(readText(catalog), aliases);
                if (inspection.configured()) {
                    return new TextInspection(true, false);
                }
                foundAlias |= inspection.aliasFound();
            } catch (IOException ex) {
                indeterminate = true;
            }
        }
        return new TextInspection(false, indeterminate || !foundAlias);
    }

    private static boolean hasUninspectedGradleConfiguration(Path buildFile, Path repositoryRoot, String text) {
        if (GRADLE_UNINSPECTED_INTEGRATION.matcher(text).find()) {
            return true;
        }
        Set<Path> roots = new LinkedHashSet<>(List.of(buildFile.getParent(), repositoryRoot));
        for (Path root : roots) {
            for (String directory : List.of("buildSrc", "build-logic")) {
                if (Files.exists(root.resolve(directory), LinkOption.NOFOLLOW_LINKS)) {
                    return true;
                }
            }
            for (String name : List.of("settings.gradle", "settings.gradle.kts")) {
                Path settings = root.resolve(name);
                if (!Files.exists(settings, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (!Files.isRegularFile(settings, LinkOption.NOFOLLOW_LINKS)
                        || containsSymbolicLink(repositoryRoot, settings)) {
                    return true;
                }
                try {
                    GradleText settingsText = stripGradleComments(readText(settings));
                    if (settingsText.indeterminate()
                            || GRADLE_UNINSPECTED_INTEGRATION
                                    .matcher(settingsText.text())
                                    .find()
                            || KNOWN_GRADLE_PLUGIN.matcher(settingsText.text()).find()) {
                        return true;
                    }
                    var aliasMatcher = GRADLE_ANY_PLUGIN_ALIAS.matcher(settingsText.text());
                    while (aliasMatcher.find()) {
                        if (!aliasMatcher.group(1).equals("libs")) {
                            return true;
                        }
                    }
                } catch (IOException ex) {
                    return true;
                }
            }
        }
        return false;
    }

    private static GradleText stripGradleComments(String text) {
        StringBuilder cleaned = new StringBuilder(text.length());
        int index = 0;
        boolean indeterminate = false;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\'' || current == '"') {
                boolean tripleQuoted = index + 2 < text.length()
                        && text.charAt(index + 1) == current
                        && text.charAt(index + 2) == current;
                int delimiterLength = tripleQuoted ? 3 : 1;
                int contextStart = Math.max(0, cleaned.length() - GRADLE_PLUGIN_CONTEXT_WINDOW);
                boolean pluginId = GRADLE_PLUGIN_STRING_PREFIX
                        .matcher(cleaned)
                        .region(contextStart, cleaned.length())
                        .useTransparentBounds(true)
                        .find();
                if (pluginId) {
                    cleaned.append(text, index, index + delimiterLength);
                } else {
                    appendMasked(cleaned, text, index, index + delimiterLength);
                }
                index += delimiterLength;
                boolean closed = false;
                while (index < text.length()) {
                    if (!tripleQuoted && text.charAt(index) == '\\' && index + 1 < text.length()) {
                        if (pluginId) {
                            cleaned.append(text, index, index + 2);
                        } else {
                            appendMasked(cleaned, text, index, index + 2);
                        }
                        index += 2;
                    } else if (text.startsWith(String.valueOf(current).repeat(delimiterLength), index)) {
                        if (pluginId) {
                            cleaned.append(text, index, index + delimiterLength);
                        } else {
                            appendMasked(cleaned, text, index, index + delimiterLength);
                        }
                        index += delimiterLength;
                        closed = true;
                        break;
                    } else {
                        char value = text.charAt(index++);
                        if (pluginId) {
                            cleaned.append(value);
                        } else {
                            cleaned.append(value == '\n' ? '\n' : ' ');
                        }
                    }
                }
                indeterminate |= !closed;
                continue;
            }
            if (current == '/' && index + 1 < text.length() && text.charAt(index + 1) == '/') {
                while (index < text.length() && text.charAt(index) != '\n') {
                    index++;
                }
                continue;
            }
            if (current == '/' && index + 1 < text.length() && text.charAt(index + 1) == '*') {
                index += 2;
                while (index + 1 < text.length() && !(text.charAt(index) == '*' && text.charAt(index + 1) == '/')) {
                    if (text.charAt(index) == '\n') {
                        cleaned.append('\n');
                    }
                    index++;
                }
                if (index + 1 >= text.length()) {
                    indeterminate = true;
                    index = text.length();
                } else {
                    index += 2;
                }
                continue;
            }
            cleaned.append(current);
            index++;
        }
        return new GradleText(cleaned.toString(), indeterminate);
    }

    private static void appendMasked(StringBuilder output, String text, int start, int end) {
        for (int index = start; index < end; index++) {
            output.append(text.charAt(index) == '\n' ? '\n' : ' ');
        }
    }

    private static CatalogInspection inspectCatalogPlugins(String text, Set<String> aliases) {
        boolean inPlugins = false;
        boolean aliasFound = false;
        for (String line : text.split("\\R")) {
            String trimmed = stripTomlComment(line).strip();
            if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inPlugins = trimmed.equals("[plugins]");
                continue;
            }
            if (!inPlugins) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator < 0
                    || !aliases.contains(normalizeCatalogAlias(
                            trimmed.substring(0, separator).strip()))) {
                continue;
            }
            aliasFound = true;
            if (KNOWN_CATALOG_PLUGIN.matcher(trimmed).find()) {
                return new CatalogInspection(true, true);
            }
        }
        return new CatalogInspection(false, aliasFound);
    }

    private static String stripTomlComment(String line) {
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (quote != 0) {
                if (quote == '"' && current == '\\' && !escaped) {
                    escaped = true;
                } else {
                    if (current == quote && !escaped) {
                        quote = 0;
                    }
                    escaped = false;
                }
            } else if (current == '"' || current == '\'') {
                quote = current;
            } else if (current == '#') {
                return line.substring(0, index);
            }
        }
        return line;
    }

    private static String normalizeCatalogAlias(String alias) {
        String normalized = alias.strip();
        if (normalized.length() > 1
                && ((normalized.startsWith("\"") && normalized.endsWith("\""))
                        || (normalized.startsWith("'") && normalized.endsWith("'")))) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.replaceAll("[._-]", "").toLowerCase(Locale.ROOT);
    }

    private static boolean hasKnownWorkflowDeclaration(String text) {
        boolean inRunBlock = false;
        boolean foldedRunBlock = false;
        int runIndent = -1;
        StringBuilder blockCommand = new StringBuilder();
        for (String line : text.split("\\R")) {
            int indent = line.length() - line.stripLeading().length();
            String trimmed = line.strip();
            if (inRunBlock) {
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (indent > runIndent) {
                    if (foldedRunBlock) {
                        appendCommandLine(blockCommand, trimmed);
                    } else if (endsWithShellContinuation(trimmed)) {
                        appendCommandLine(blockCommand, removeShellContinuation(trimmed));
                    } else {
                        appendCommandLine(blockCommand, trimmed);
                        if (hasKnownWorkflowCommand(blockCommand.toString())) {
                            return true;
                        }
                        blockCommand.setLength(0);
                    }
                    continue;
                }
                inRunBlock = false;
                if (hasKnownWorkflowCommand(blockCommand.toString())) {
                    return true;
                }
                blockCommand.setLength(0);
            }
            if (trimmed.startsWith("- ")) {
                trimmed = trimmed.substring(2).stripLeading();
            }
            if (trimmed.startsWith("uses:")) {
                String action = trimmed.substring("uses:".length()).strip();
                if ((action.startsWith("\"") && action.endsWith("\""))
                        || (action.startsWith("'") && action.endsWith("'"))) {
                    action = action.substring(1, action.length() - 1);
                }
                if (KNOWN_WORKFLOW_ACTION.matcher(action).matches()) {
                    return true;
                }
            } else if (trimmed.startsWith("run:")) {
                String command = trimmed.substring("run:".length()).strip();
                if (command.isEmpty() || command.startsWith("|") || command.startsWith(">")) {
                    inRunBlock = true;
                    foldedRunBlock = command.startsWith(">");
                    runIndent = indent;
                    blockCommand.setLength(0);
                } else if (hasKnownWorkflowCommand(unquoteWorkflowRunScalar(command))) {
                    return true;
                }
            }
        }
        return !blockCommand.isEmpty() && hasKnownWorkflowCommand(blockCommand.toString());
    }

    private static String unquoteWorkflowRunScalar(String command) {
        if (command.length() < 2) {
            return command;
        }
        char quote = command.charAt(0);
        if ((quote != '\'' && quote != '"') || command.charAt(command.length() - 1) != quote) {
            return command;
        }
        String value = command.substring(1, command.length() - 1);
        return quote == '\''
                ? value.replace("''", "'")
                : value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static boolean endsWithShellContinuation(String command) {
        int backslashes = 0;
        for (int index = command.length() - 1; index >= 0 && command.charAt(index) == '\\'; index--) {
            backslashes++;
        }
        return (backslashes & 1) == 1;
    }

    private static String removeShellContinuation(String command) {
        return command.substring(0, command.length() - 1).stripTrailing();
    }

    private static void appendCommandLine(StringBuilder command, String line) {
        if (!command.isEmpty()) {
            command.append(' ');
        }
        command.append(line);
    }

    private static boolean hasKnownWorkflowCommand(String command) {
        for (String statement : splitShellCommands(command)) {
            List<String> tokens = shellTokens(statement);
            int commandIndex = 0;
            while (commandIndex < tokens.size()) {
                String token = tokens.get(commandIndex);
                if (token.equals("env")
                        || token.equals("sudo")
                        || token.equals("time")
                        || token.equals("command")
                        || token.equals("exec")
                        || ENVIRONMENT_ASSIGNMENT.matcher(token).matches()) {
                    commandIndex++;
                } else {
                    break;
                }
            }
            if (commandIndex >= tokens.size()) {
                continue;
            }

            String executable = tokens.get(commandIndex);
            int slash = executable.lastIndexOf('/');
            String name = slash < 0 ? executable : executable.substring(slash + 1);
            List<String> arguments = tokens.subList(commandIndex + 1, tokens.size());
            if ((name.equals("semgrep") && isSemgrepInvocation(arguments))
                    || (name.equals("codeql") && startsWithAny(arguments, "database", "analyze"))
                    || name.equals("sonar-scanner")
                    || (name.equals("spotbugs") && !arguments.isEmpty())
                    || ((name.equals("mvn") || name.equals("mvnw")) && hasMavenAnalyzerGoal(arguments))
                    || ((name.equals("gradle") || name.equals("gradlew")) && hasGradleAnalyzerTask(arguments))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSemgrepInvocation(List<String> arguments) {
        if (startsWithAny(arguments, "scan", "ci")) {
            return true;
        }
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (argument.startsWith("--config=") && argument.length() > "--config=".length()) {
                return true;
            }
            if ((argument.equals("--config") || argument.equals("-f"))
                    && index + 1 < arguments.size()
                    && !arguments.get(index + 1).isBlank()) {
                return true;
            }
            if (argument.startsWith("-f") && argument.length() > 2) {
                return true;
            }
        }
        return false;
    }

    private static List<String> splitShellCommands(String command) {
        List<String> statements = new ArrayList<>();
        char quote = 0;
        boolean escaped = false;
        int start = 0;
        for (int index = 0; index < command.length(); index++) {
            char current = command.charAt(index);
            if (quote != 0) {
                if (current == '\\' && quote == '"' && !escaped) {
                    escaped = true;
                } else {
                    if (current == quote && !escaped) {
                        quote = 0;
                    }
                    escaped = false;
                }
            } else if (current == '"' || current == '\'') {
                quote = current;
            } else if (current == ';' || current == '|' || current == '&') {
                statements.add(command.substring(start, index));
                if (index + 1 < command.length()
                        && ((current == '|' && command.charAt(index + 1) == '|')
                                || (current == '&' && command.charAt(index + 1) == '&'))) {
                    index++;
                }
                start = index + 1;
            }
        }
        statements.add(command.substring(start));
        return statements;
    }

    private static List<String> shellTokens(String statement) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < statement.length(); index++) {
            char current = statement.charAt(index);
            if (quote != 0) {
                if (current == '\\' && quote == '"' && !escaped) {
                    escaped = true;
                } else if (current == quote && !escaped) {
                    quote = 0;
                    escaped = false;
                } else {
                    token.append(current);
                    escaped = false;
                }
            } else if (current == '"' || current == '\'') {
                quote = current;
            } else if (Character.isWhitespace(current)) {
                if (!token.isEmpty()) {
                    tokens.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(current);
            }
        }
        if (!token.isEmpty()) {
            tokens.add(token.toString());
        }
        return tokens;
    }

    private static boolean startsWithAny(List<String> arguments, String... values) {
        return !arguments.isEmpty() && List.of(values).contains(arguments.get(0));
    }

    private static boolean hasMavenAnalyzerGoal(List<String> arguments) {
        return arguments.stream()
                .anyMatch(argument -> argument.equals("sonar:sonar")
                        || argument.matches("org\\.sonarsource\\.scanner\\.maven:sonar-maven-plugin(?::[^:]+)?:sonar")
                        || argument.matches(
                                "com\\.github\\.spotbugs:spotbugs-maven-plugin(?::[^:]+)?:(?:check|spotbugs|verify)")
                        || argument.matches("spotbugs:(?:check|spotbugs|verify)"));
    }

    private static boolean hasGradleAnalyzerTask(List<String> arguments) {
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (argument.equals("-x") || argument.equals("--exclude-task")) {
                index++;
                continue;
            }
            if (argument.startsWith("--exclude-task=") || (argument.startsWith("-x") && argument.length() > 2)) {
                continue;
            }
            if (argument.matches("(?i)(?:^|:)(?:[A-Za-z0-9_.-]+:)*(?:sonar|spotbugs(?:main|test|all)?)(?:$|:)")) {
                return true;
            }
        }
        return false;
    }

    private static FileInspection inspectDeclaredFile(Path repositoryRoot, Path path) {
        if (containsSymbolicLink(repositoryRoot, path)) {
            return FileInspection.INDETERMINATE;
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return FileInspection.ABSENT;
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return FileInspection.INDETERMINATE;
        }
        try {
            return readText(path).isBlank() ? FileInspection.ABSENT : FileInspection.PRESENT;
        } catch (IOException ex) {
            return FileInspection.INDETERMINATE;
        }
    }

    private static WorkflowInspection inspectWorkflows(Path repositoryRoot, Path directory) {
        if (containsSymbolicLink(repositoryRoot, directory)) {
            return new WorkflowInspection(false, true);
        }
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return new WorkflowInspection(false, false);
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return new WorkflowInspection(false, true);
        }
        int visited = 0;
        boolean indeterminate = false;
        try (var files = Files.list(directory)) {
            var iterator = files.iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!(name.endsWith(".yml") || name.endsWith(".yaml"))) {
                    continue;
                }
                if (++visited > MAX_WORKFLOW_FILES) {
                    return new WorkflowInspection(false, true);
                }
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
                    indeterminate = true;
                    continue;
                }
                try {
                    if (hasKnownWorkflowDeclaration(stripYamlComments(readText(file)))) {
                        return new WorkflowInspection(true, false);
                    }
                } catch (IOException ex) {
                    indeterminate = true;
                }
            }
        } catch (IOException ex) {
            return new WorkflowInspection(false, true);
        }
        return new WorkflowInspection(false, indeterminate);
    }

    private static boolean containsSymbolicLink(Path trustedRoot, Path candidate) {
        Path root = trustedRoot.toAbsolutePath().normalize();
        Path target = candidate.toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            return true;
        }
        Path current = root;
        for (Path segment : root.relativize(target)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readBounded(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("Build file is not a regular local file.");
        }
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] content = input.readNBytes(MAX_FILE_BYTES + 1);
            if (content.length > MAX_FILE_BYTES) {
                throw new IOException("Build file size is outside the inspection limit.");
            }
            return content;
        }
    }

    private static String readText(Path path) throws IOException {
        return new String(readBounded(path), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String stripYamlComments(String text) {
        StringBuilder uncommented = new StringBuilder(text.length());
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (quote != 0) {
                uncommented.append(current);
                if (quote == '"' && current == '\\' && !escaped) {
                    escaped = true;
                } else if (current == quote && !escaped) {
                    if (quote == '\'' && index + 1 < text.length() && text.charAt(index + 1) == '\'') {
                        uncommented.append(text.charAt(++index));
                    } else {
                        quote = 0;
                    }
                } else {
                    escaped = false;
                }
            } else if ((current == '"' || current == '\'') && (index == 0 || text.charAt(index - 1) != '\\')) {
                quote = current;
                uncommented.append(current);
            } else if (current == '#' && (index == 0 || Character.isWhitespace(text.charAt(index - 1)))) {
                while (index < text.length() && text.charAt(index) != '\n') {
                    index++;
                }
                if (index < text.length()) {
                    uncommented.append('\n');
                }
            } else {
                uncommented.append(current);
            }
        }
        return uncommented.toString();
    }

    private static Element directChild(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(localName(element))) {
                return element;
            }
        }
        return null;
    }

    private static String localName(Element element) {
        String name = element.getLocalName();
        return name == null ? element.getTagName() : name;
    }

    private static String text(Element element) {
        return element == null ? "" : element.getTextContent().strip();
    }

    private enum FileInspection {
        ABSENT,
        PRESENT,
        INDETERMINATE
    }

    private record MavenInspection(boolean configured, boolean indeterminate) {}

    private record MavenCoordinate(String groupId, String artifactId, String version) {
        private boolean concrete() {
            return !groupId.isEmpty()
                    && !artifactId.isEmpty()
                    && !version.isEmpty()
                    && !groupId.contains("${")
                    && !artifactId.contains("${")
                    && !version.contains("${");
        }
    }

    private record TextInspection(boolean configured, boolean indeterminate) {}

    private record CatalogInspection(boolean configured, boolean aliasFound) {}

    private record GradleText(String text, boolean indeterminate) {}

    private record WorkflowInspection(boolean configured, boolean indeterminate) {}

    private static final class SetNames {
        private static final java.util.Set<String> SONAR_PROPERTIES =
                java.util.Set.of("sonar.projectKey", "sonar.host.url", "sonar.organization", "sonar.sources");

        private SetNames() {}
    }
}
