package io.github.jdubois.bootui.autoconfigure;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Arrays;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.ClassUtils;

/**
 * Registers the GraalVM reachability metadata that BootUI itself needs so that applications using
 * the BootUI starter can be compiled to a native image without each one re-declaring these hints.
 *
 * <p>This registrar is imported from {@link BootUiAutoConfiguration} via {@code @ImportRuntimeHints}
 * so the hints are only contributed when BootUI auto-configuration is on the classpath. It covers
 * two kinds of metadata that Spring's generic AOT processing cannot infer on its own:
 *
 * <ul>
 *   <li><b>Classpath resources</b> that BootUI reads at runtime through {@link ClassLoader} or
 *       {@code PathMatchingResourcePatternResolver} scanning. These are excluded from a native image
 *       unless registered, which would otherwise leave the Vulnerabilities and Config panels empty and
 *       the BootUI version reported as {@code unknown}.
 *   <li><b>Reflective method invocations</b> on well-known JDK and Spring Security types that BootUI
 *       discovers at runtime and calls reflectively.
 *   <li><b>DTO binding types</b> under {@code io.github.jdubois.bootui.core.dto} that Jackson can
 *       synthesize while serializing BootUI records, including array types derived from
 *       {@code List<...>} record components.
 * </ul>
 *
 * <p>The reflective sites in BootUI that operate on arbitrary user- or library-supplied types (for
 * example cache statistics objects, dev-services beans, or annotation attributes) are intentionally
 * not registered here: those types are only known to the consuming application, are best-effort, and
 * already degrade gracefully when reflection metadata is missing.
 */
class BootUiRuntimeHints implements RuntimeHintsRegistrar {

    /** Maven metadata scanned by {@code DependencyCatalog} for the Vulnerabilities panel. */
    private static final String MAVEN_POM_PROPERTIES_PATTERN = "META-INF/maven/*/*/pom.properties";

    /** Configuration metadata scanned by {@code ConfigMetadataCatalog} for the Config panel. */
    private static final String CONFIGURATION_METADATA_RESOURCE = "META-INF/spring-configuration-metadata.json";

    /** Version file read by {@code BootUiInfo} (lives in bootui-core). */
    private static final String BOOTUI_VERSION_RESOURCE = "bootui-version.properties";

    private static final String DTO_PACKAGE = "io.github.jdubois.bootui.core.dto";

    private static final String DTO_RESOURCE_PATTERN = "classpath*:io/github/jdubois/bootui/core/dto/*.class";

    /** JDK MXBean invoked reflectively by {@code HeapDumpService} for heap dumps. */
    private static final String HOTSPOT_DIAGNOSTIC_MXBEAN = "com.sun.management.HotSpotDiagnosticMXBean";

    /** Spring Security filter chain whose {@code getFilters()} is invoked reflectively by the pentesting scanner. */
    private static final String SECURITY_FILTER_CHAIN = "org.springframework.security.web.SecurityFilterChain";

    /** Spring Security authority whose {@code getAuthority()} is invoked reflectively by {@code SpringSecurityService}. */
    private static final String GRANTED_AUTHORITY = "org.springframework.security.core.GrantedAuthority";

    private static final String SIMPLE_GRANTED_AUTHORITY =
            "org.springframework.security.core.authority.SimpleGrantedAuthority";

    /** Spring Modulith identifiers whose {@code stream()} is invoked reflectively by {@code FlywayController}. */
    private static final String MODULITH_APPLICATION_MODULE_IDENTIFIERS =
            "org.springframework.modulith.core.ApplicationModuleIdentifiers";

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources()
                .registerPattern(MAVEN_POM_PROPERTIES_PATTERN)
                .registerPattern(CONFIGURATION_METADATA_RESOURCE)
                .registerPattern(BOOTUI_VERSION_RESOURCE);

        registerDtoBindingHints(hints, classLoader);

        // Heap Dump panel: HeapDumpService reflectively calls HotSpotDiagnosticMXBean#dumpHeap.
        hints.reflection()
                .registerTypeIfPresent(classLoader, HOTSPOT_DIAGNOSTIC_MXBEAN, MemberCategory.INVOKE_PUBLIC_METHODS);

        // Security / Pentesting panels: BootUI reflectively invokes public getters on these Spring
        // Security types. registerTypeIfPresent keeps the hints harmless when Spring Security is absent.
        hints.reflection()
                .registerTypeIfPresent(classLoader, SECURITY_FILTER_CHAIN, MemberCategory.INVOKE_PUBLIC_METHODS);
        hints.reflection().registerTypeIfPresent(classLoader, GRANTED_AUTHORITY, MemberCategory.INVOKE_PUBLIC_METHODS);
        hints.reflection()
                .registerTypeIfPresent(classLoader, SIMPLE_GRANTED_AUTHORITY, MemberCategory.INVOKE_PUBLIC_METHODS);

        // Flyway panel: when Spring Modulith module-aware migrations are active, BootUI
        // reflectively reads ApplicationModuleIdentifiers#stream without requiring
        // Spring Modulith as a starter dependency.
        hints.reflection()
                .registerTypeIfPresent(
                        classLoader, MODULITH_APPLICATION_MODULE_IDENTIFIERS, MemberCategory.INVOKE_PUBLIC_METHODS);
    }

    private void registerDtoBindingHints(RuntimeHints hints, ClassLoader classLoader) {
        for (Class<?> dtoType : findDtoTypes(classLoader)) {
            hints.reflection()
                    .registerType(
                            dtoType, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS);
            hints.reflection().registerType(Array.newInstance(dtoType, 0).getClass());
        }
    }

    private Class<?>[] findDtoTypes(ClassLoader classLoader) {
        ClassLoader resourceClassLoader = classLoader != null ? classLoader : BootUiRuntimeHints.class.getClassLoader();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(resourceClassLoader);
        try {
            Resource[] resources = resolver.getResources(DTO_RESOURCE_PATTERN);
            return Arrays.stream(resources)
                    .map(Resource::getFilename)
                    .filter(BootUiRuntimeHints::isTopLevelClassFile)
                    .map(BootUiRuntimeHints::classNameFromResource)
                    .map(className -> ClassUtils.resolveClassName(className, resourceClassLoader))
                    .filter(Class::isRecord)
                    .toArray(Class<?>[]::new);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to resolve BootUI DTO types for native-image hints", ex);
        }
    }

    private static boolean isTopLevelClassFile(String filename) {
        return filename != null && filename.endsWith(".class") && !filename.contains("$");
    }

    private static String classNameFromResource(String filename) {
        return DTO_PACKAGE + '.' + filename.substring(0, filename.length() - ".class".length());
    }
}
