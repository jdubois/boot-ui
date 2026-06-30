package io.github.jdubois.bootui.engine.archunit;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.resolvers.ClassResolver;
import com.tngtech.archunit.core.importer.resolvers.ClassResolverFromClasspath;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.Optional;

/**
 * A {@link ClassResolver} that resolves missing dependencies from the classpath exactly like ArchUnit's
 * default {@link ClassResolverFromClasspath}, but silently skips any resource location that cannot be
 * opened as a {@link java.net.URL}.
 *
 * <p>Motivation: BootUI's advisor scanners import only the host application's own classes, yet ArchUnit
 * still resolves the external types those classes reference (super-classes, interfaces, …) from the
 * classpath so that hierarchy-aware checks (e.g. {@code areAssignableTo(Exception.class)}) work. Under some
 * runtime classloaders a referenced class's resource URL uses a custom protocol that
 * {@link java.net.URI#toURL()} cannot handle — notably the Quarkus runtime classloader, whose resource
 * URLs use a {@code quarkus:} scheme. ArchUnit's default resolver attempts the import anyway, the
 * URL conversion throws {@link MalformedURLException}, and ArchUnit logs a WARN ("falling back to simple
 * import") for every such class, flooding the console during a scan.
 *
 * <p>This resolver pre-checks each resolved location and skips the ones that cannot be turned into a URL,
 * returning {@link Optional#empty()} without the WARN. For every openable location ({@code file:},
 * {@code jar:}, {@code jrt:} — i.e. all of a typical Spring Boot classpath, plus the JDK ancestry that is
 * loaded from the bootstrap loader even on Quarkus) it delegates to the real
 * {@link ClassResolverFromClasspath}, so resolution — and therefore the curated rules' findings — is
 * byte-for-byte identical to ArchUnit's default behaviour. Only locations that ArchUnit could not have
 * resolved anyway (it would have logged a WARN and skipped them) are filtered out, just quietly.
 *
 * <p>The class is {@code public} with a public no-argument constructor because ArchUnit instantiates a
 * configured {@link ClassResolver} reflectively by class name. It carries no framework-specific types (it
 * only inspects URL protocols), so it stays in the framework-neutral engine and serves both adapters.
 */
public final class OpenableLocationClassResolver implements ClassResolver {

    private final ClassResolverFromClasspath delegate = new ClassResolverFromClasspath();

    @Override
    public void setClassUriImporter(ClassUriImporter classUriImporter) {
        // Wrap the importer ArchUnit supplies so unopenable locations are skipped before the import is
        // attempted; everything openable is imported by the unchanged default machinery.
        delegate.setClassUriImporter(uri -> isOpenable(uri) ? classUriImporter.tryImport(uri) : Optional.empty());
    }

    @Override
    public Optional<JavaClass> tryResolve(String typeName) {
        return delegate.tryResolve(typeName);
    }

    /**
     * Whether {@code uri} can be turned into a {@link java.net.URL} — i.e. its protocol is registered and
     * the default {@link ClassResolverFromClasspath#tryResolve(String) importer} could open it. Returns
     * {@code false} for resource locations using an unregistered scheme such as the Quarkus runtime
     * classloader's {@code quarkus:} protocol, which is exactly the case ArchUnit logs a WARN for.
     */
    static boolean isOpenable(URI uri) {
        try {
            uri.toURL();
            return true;
        } catch (MalformedURLException | IllegalArgumentException unopenable) {
            return false;
        }
    }
}
