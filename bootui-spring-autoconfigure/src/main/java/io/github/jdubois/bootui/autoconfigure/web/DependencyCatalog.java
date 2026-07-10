package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.engine.support.BlankStrings;
import io.github.jdubois.bootui.engine.vulnerabilities.DependencyProvider;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
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

final class DependencyCatalog implements DependencyProvider {

    private static final String MAVEN_PROPERTIES_PATTERN = "classpath*:META-INF/maven/*/*/pom.properties";

    private final ResourcePatternResolver resolver;

    DependencyCatalog() {
        this(new PathMatchingResourcePatternResolver());
    }

    DependencyCatalog(ResourcePatternResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public List<DependencyDto> dependencies() {
        Map<String, DependencyDto> dependencies = new LinkedHashMap<>();
        for (Resource resource : mavenPomProperties()) {
            DependencyDto dependency = dependency(resource);
            if (dependency != null) {
                dependencies.putIfAbsent(dependency.packageName() + ":" + dependency.version(), dependency);
            }
        }
        for (DependencyDto dependency : javaClassPathDependencies()) {
            dependencies.putIfAbsent(dependency.packageName() + ":" + dependency.version(), dependency);
        }
        return dependencies.values().stream()
                .sorted(Comparator.comparing(DependencyDto::packageName).thenComparing(DependencyDto::version))
                .toList();
    }

    private Resource[] mavenPomProperties() {
        try {
            return resolver.getResources(MAVEN_PROPERTIES_PATTERN);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not inspect classpath Maven metadata", ex);
        }
    }

    private DependencyDto dependency(Resource resource) {
        Properties properties = new Properties();
        try (InputStream input = resource.getInputStream()) {
            properties.load(input);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read Maven metadata from " + resource.getDescription(), ex);
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
        return List.of(classPath.split(java.util.regex.Pattern.quote(File.pathSeparator))).stream()
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
        if (!fileName.startsWith(artifactId + "-" + version) || !fileName.endsWith(".jar")) {
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
