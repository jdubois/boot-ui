package io.github.jdubois.bootui.autoconfigure;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

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
 *       unless registered, which would otherwise leave the Dependencies and Config panels empty and
 *       the BootUI version reported as {@code unknown}.
 *   <li><b>Reflective method invocations</b> on well-known JDK and Spring Security types that BootUI
 *       discovers at runtime and calls reflectively.
 * </ul>
 *
 * <p>The reflective sites in BootUI that operate on arbitrary user- or library-supplied types (for
 * example cache statistics objects, dev-services beans, or annotation attributes) are intentionally
 * not registered here: those types are only known to the consuming application, are best-effort, and
 * already degrade gracefully when reflection metadata is missing.
 */
class BootUiRuntimeHints implements RuntimeHintsRegistrar {

    /** Maven metadata scanned by {@code DependencyCatalog} for the Dependencies panel. */
    private static final String MAVEN_POM_PROPERTIES_PATTERN = "META-INF/maven/*/*/pom.properties";

    /** Configuration metadata scanned by {@code ConfigMetadataCatalog} for the Config panel. */
    private static final String CONFIGURATION_METADATA_RESOURCE = "META-INF/spring-configuration-metadata.json";

    /** Version file read by {@code BootUiInfo} (lives in bootui-core). */
    private static final String BOOTUI_VERSION_RESOURCE = "bootui-version.properties";

    /** JDK MXBean invoked reflectively by {@code HeapDumpService} for heap dumps. */
    private static final String HOTSPOT_DIAGNOSTIC_MXBEAN = "com.sun.management.HotSpotDiagnosticMXBean";

    /** Spring Security filter chain whose {@code getFilters()} is invoked reflectively by the pentest scanner. */
    private static final String SECURITY_FILTER_CHAIN = "org.springframework.security.web.SecurityFilterChain";

    /** Spring Security authority whose {@code getAuthority()} is invoked reflectively by {@code SecurityController}. */
    private static final String GRANTED_AUTHORITY = "org.springframework.security.core.GrantedAuthority";

    private static final String SIMPLE_GRANTED_AUTHORITY =
            "org.springframework.security.core.authority.SimpleGrantedAuthority";

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources()
                .registerPattern(MAVEN_POM_PROPERTIES_PATTERN)
                .registerPattern(CONFIGURATION_METADATA_RESOURCE)
                .registerPattern(BOOTUI_VERSION_RESOURCE);

        // Heap Dump panel: HeapDumpService reflectively calls HotSpotDiagnosticMXBean#dumpHeap.
        hints.reflection()
                .registerTypeIfPresent(classLoader, HOTSPOT_DIAGNOSTIC_MXBEAN, MemberCategory.INVOKE_PUBLIC_METHODS);

        // Security / Pentest panels: BootUI reflectively invokes public getters on these Spring
        // Security types. registerTypeIfPresent keeps the hints harmless when Spring Security is absent.
        hints.reflection()
                .registerTypeIfPresent(classLoader, SECURITY_FILTER_CHAIN, MemberCategory.INVOKE_PUBLIC_METHODS);
        hints.reflection().registerTypeIfPresent(classLoader, GRANTED_AUTHORITY, MemberCategory.INVOKE_PUBLIC_METHODS);
        hints.reflection()
                .registerTypeIfPresent(classLoader, SIMPLE_GRANTED_AUTHORITY, MemberCategory.INVOKE_PUBLIC_METHODS);
    }
}
