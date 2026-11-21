package io.github.jdubois.bootui.engine.archunit;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.core.importer.Locations;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Optional, Architecture-only provenance bridge. Frozen bytes are the input to ArchUnit's actual
 * reader, not a second read or Source.Md5sum. Original source URIs and normal resolver/header policy
 * are preserved. Unsupported internal APIs fall back to the public importer without precision.
 */
public final class ArchUnitBytecodeImports {
    private static final System.Logger LOGGER = System.getLogger(ArchUnitBytecodeImports.class.getName());
    private static final Limits LIMITS = new Limits(4 * 1024 * 1024, 64L * 1024 * 1024, 8192);

    private ArchUnitBytecodeImports() {}

    public record Result(JavaClasses classes, Bytecode bytecode) {
        public static Result uncaptured(JavaClasses classes) {
            return new Result(classes, Bytecode.EMPTY);
        }
    }

    public static final class Bytecode {
        public static final Bytecode EMPTY = new Bytecode(Map.of());
        private final Map<JavaClass, byte[]> bytes;

        private Bytecode(Map<JavaClass, byte[]> bytes) {
            this.bytes = Collections.unmodifiableMap(new IdentityHashMap<>(bytes));
        }

        public Optional<byte[]> bytesOf(JavaClass type) {
            byte[] value = bytes.get(type);
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }
    }

    public static Result importPackages(List<String> packages) {
        Set<Location> locations = new LinkedHashSet<>();
        packages.forEach(name -> locations.addAll(Locations.ofPackage(name)));
        return importLocations(locations, Hook::new, LIMITS);
    }

    public static Result importClasses(Class<?>... classes) {
        Set<Location> locations = new LinkedHashSet<>();
        Arrays.stream(classes).forEach(type -> locations.addAll(Locations.ofClass(type)));
        return importLocations(locations, Hook::new, LIMITS);
    }

    public static Result importPath(Path path) {
        return importLocations(List.of(Location.of(path)), Hook::new, LIMITS);
    }

    static Result importLocations(Collection<Location> locations, HookFactory factory, Limits limits) {
        return ArchUnitClassImports.withScopedConfiguration(() -> {
            Capture capture = new Capture(limits);
            try {
                ClassFileImportCheck check = new ClassFileImportCheck();
                JavaClasses classes;
                try {
                    Hook hook = factory.create();
                    ClassFileImporter importer = new ClassFileImporter().withImportOption(check);
                    List<Root> roots = hook.roots(importer, locations, limits.entries());
                    classes = hook.process(roots, capture);
                    if (capture.unsupported) throw new HookUnavailableException();
                } catch (ReflectiveOperationException | RuntimeException unavailable) {
                    LOGGER.log(
                            System.Logger.Level.DEBUG,
                            "Optional ArchUnit bytecode capture unavailable; retaining legacy receiver analysis",
                            unavailable);
                    ClassFileImportCheck normalCheck = new ClassFileImportCheck();
                    JavaClasses normal = new ClassFileImporter()
                            .withImportOption(normalCheck)
                            .importLocations(locations);
                    normalCheck.verify();
                    return Result.uncaptured(normal);
                }
                check.verify();
                return new Result(classes, capture.finish(classes));
            } finally {
                capture.clear();
            }
        });
    }

    static boolean isLocalResource(URI resource) {
        for (int depth = 0; depth < 16; depth++) {
            if (resource.getScheme() == null
                    || resource.getRawAuthority() != null
                    || resource.getRawQuery() != null
                    || resource.getRawFragment() != null) return false;
            if ("jar".equals(resource.getScheme())) {
                String nested = resource.getRawSchemeSpecificPart();
                int entry = nested.lastIndexOf("!/");
                if (entry < 0) return false;
                resource = URI.create(nested.substring(0, entry));
                continue;
            }
            String path = resource.getPath();
            return Set.of("file", "jrt", "nested").contains(resource.getScheme())
                    && !resource.isOpaque()
                    && path != null
                    && path.startsWith("/")
                    && !path.startsWith("//")
                    && path.indexOf('\\') < 0;
        }
        return false;
    }

    record Limits(int classBytes, long totalBytes, int entries) {}

    @FunctionalInterface
    interface HookFactory {
        Hook create() throws ReflectiveOperationException;
    }

    @FunctionalInterface
    interface Opener {
        InputStream open() throws IOException;
    }

    record Root(URI uri, Opener opener) {}

    static final class Capture {
        private final Limits limits;
        private final Map<URI, byte[]> bytes = new HashMap<>();
        private final Set<URI> unverified = new HashSet<>();
        private long retained;
        private boolean exhausted;
        private boolean unsupported;

        Capture(Limits limits) {
            this.limits = limits;
        }

        InputStream open(Root root) throws IOException {
            if (!isLocalResource(root.uri())) throw new HookUnavailableException();
            InputStream original;
            try {
                original = root.opener().open();
            } catch (IOException | RuntimeException failure) {
                invalidate(root.uri());
                throw failure;
            }
            if (exhausted) return original;
            boolean transferred = false;
            Throwable failure = null;
            try {
                byte[] frozen = original.readNBytes(limits.classBytes() + 1);
                if (frozen.length > limits.classBytes()) {
                    LOGGER.log(System.Logger.Level.DEBUG, "Optional ArchUnit class-byte capture budget exceeded");
                    invalidate(root.uri());
                    transferred = true;
                    return new SequenceInputStream(new ByteArrayInputStream(frozen), original);
                }
                record(root.uri(), frozen);
                return new ByteArrayInputStream(frozen);
            } catch (IOException | RuntimeException | Error problem) {
                failure = problem;
                invalidate(root.uri());
                throw problem;
            } finally {
                if (!transferred) {
                    try {
                        original.close();
                    } catch (IOException | RuntimeException closeFailure) {
                        unsupported = true;
                        invalidate(root.uri());
                        if (failure != null) failure.addSuppressed(closeFailure);
                        else throw closeFailure;
                    }
                }
            }
        }

        private void record(URI uri, byte[] frozen) {
            if (exhausted || unverified.contains(uri)) return;
            byte[] previous = bytes.get(uri);
            if (previous != null) {
                if (!Arrays.equals(previous, frozen)) {
                    LOGGER.log(
                            System.Logger.Level.DEBUG,
                            "Conflicting ArchUnit resource reads; withholding receiver proof");
                    invalidate(uri);
                }
                return;
            }
            if (retained + frozen.length > limits.totalBytes()
                    || bytes.size() + unverified.size() >= limits.entries()) {
                LOGGER.log(System.Logger.Level.DEBUG, "Optional ArchUnit bytecode capture budget exhausted");
                clear();
                exhausted = true;
                return;
            }
            bytes.put(uri, frozen);
            retained += frozen.length;
        }

        private void invalidate(URI uri) {
            if (exhausted) return;
            byte[] previous = bytes.remove(uri);
            if (previous != null) retained -= previous.length;
            if (bytes.size() + unverified.size() >= limits.entries()) {
                clear();
                exhausted = true;
            } else {
                unverified.add(uri);
            }
        }

        Bytecode finish(JavaClasses classes) {
            Map<JavaClass, byte[]> result = new IdentityHashMap<>();
            if (!exhausted) {
                for (JavaClass type : classes) {
                    type.getSource().ifPresent(source -> {
                        byte[] frozen = bytes.get(source.getUri());
                        if (frozen != null && !unverified.contains(source.getUri())) result.put(type, frozen);
                    });
                }
            }
            return new Bytecode(result);
        }

        void clear() {
            bytes.clear();
            unverified.clear();
            retained = 0;
        }
    }

    /**
     * Structural checks, not a version allowlist. No application reflection or class initialization:
     * these handles address only the already-selected ArchUnit implementation.
     */
    static class Hook {
        private final Class<?> sourceType;
        private final Class<?> locationType;
        private final Constructor<?> processor;
        private final Method process;
        private final Method asSource;
        private final Method open;
        private final Method uri;
        private final Field options;

        Hook() throws ReflectiveOperationException {
            ClassLoader loader = ClassFileImporter.class.getClassLoader();
            Class<?> processorType =
                    Class.forName("com.tngtech.archunit.core.importer.ClassFileProcessor", false, loader);
            sourceType = Class.forName("com.tngtech.archunit.core.importer.ClassFileSource", false, loader);
            locationType = Class.forName("com.tngtech.archunit.core.importer.ClassFileLocation", false, loader);
            Class<?> optionsType = Class.forName("com.tngtech.archunit.core.importer.ImportOptions", false, loader);
            if (!sourceType.isInterface()
                    || !Iterable.class.isAssignableFrom(sourceType)
                    || sourceType.getDeclaredMethods().length != 0
                    || !locationType.isInterface()
                    || locationType.getDeclaredMethods().length != 2)
                throw new NoSuchMethodException("ArchUnit source shape");
            processor = processorType.getDeclaredConstructor();
            process = processorType.getDeclaredMethod("process", sourceType);
            asSource = Location.class.getDeclaredMethod("asClassFileSource", optionsType);
            open = locationType.getDeclaredMethod("openStream");
            uri = locationType.getDeclaredMethod("getUri");
            options = ClassFileImporter.class.getDeclaredField("importOptions");
            if (process.getReturnType() != JavaClasses.class
                    || asSource.getReturnType() != sourceType
                    || open.getReturnType() != InputStream.class
                    || uri.getReturnType() != URI.class
                    || options.getType() != optionsType
                    || !processor.trySetAccessible()
                    || !process.trySetAccessible()
                    || !asSource.trySetAccessible()
                    || !open.trySetAccessible()
                    || !uri.trySetAccessible()
                    || !options.trySetAccessible()) throw new IllegalAccessException("ArchUnit capture access");
        }

        List<Root> roots(ClassFileImporter importer, Collection<Location> locations, int limit)
                throws ReflectiveOperationException {
            List<Root> result = new ArrayList<>();
            for (Location location : locations) {
                if (!isLocalResource(location.asURI())) throw new HookUnavailableException();
                Object source = invoke(asSource, location, options.get(importer));
                for (Object original : (Iterable<?>) source) {
                    if (result.size() >= limit) throw new HookUnavailableException();
                    URI sourceUri = (URI) invoke(uri, original);
                    if (!isLocalResource(sourceUri)) throw new HookUnavailableException();
                    result.add(new Root(sourceUri, () -> {
                        try {
                            return (InputStream) invoke(open, original);
                        } catch (ReflectiveOperationException failure) {
                            throw new HookUnavailableException();
                        }
                    }));
                }
            }
            return result;
        }

        JavaClasses process(List<Root> roots, Capture capture) throws ReflectiveOperationException {
            List<Object> locations = new ArrayList<>();
            for (Root root : roots) {
                locations.add(Proxy.newProxyInstance(
                        locationType.getClassLoader(),
                        new Class<?>[] {locationType},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "getUri" -> root.uri();
                            case "openStream" -> {
                                try {
                                    yield capture.open(root);
                                } catch (HookUnavailableException unavailable) {
                                    capture.unsupported = true;
                                    throw unavailable;
                                }
                            }
                            default -> objectMethod(proxy, method, args);
                        }));
            }
            Object source = Proxy.newProxyInstance(
                    sourceType.getClassLoader(),
                    new Class<?>[] {sourceType},
                    (proxy, method, args) -> method.getName().equals("iterator")
                            ? locations.iterator()
                            : objectMethod(proxy, method, args));
            Object instance;
            try {
                instance = processor.newInstance();
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Error error) throw error;
                throw failure;
            }
            return (JavaClasses) invoke(process, instance, source);
        }

        private static Object objectMethod(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "toString" -> "BootUI captured class resource";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new HookUnavailableException();
            };
        }

        private static Object invoke(Method method, Object target, Object... args) throws ReflectiveOperationException {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Error error) throw error;
                if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                throw failure;
            }
        }
    }

    private static final class HookUnavailableException extends RuntimeException {}
}
