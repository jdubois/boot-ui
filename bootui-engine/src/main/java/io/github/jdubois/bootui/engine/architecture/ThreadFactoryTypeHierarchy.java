package io.github.jdubois.bootui.engine.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassReader;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Scan-local hierarchy lookup for types ArchUnit omits from invokedynamic-only dependencies. */
final class ThreadFactoryTypeHierarchy {
    private final Map<String, JavaClass> imported = new HashMap<>();
    private final Map<String, List<String>> parents = new HashMap<>();

    ThreadFactoryTypeHierarchy(JavaClasses classes) {
        for (JavaClass type : classes) {
            imported.put(type.getName(), type);
        }
    }

    void observe(JavaClass type) {
        type.getDirectDependenciesFromSelf()
                .forEach(dependency ->
                        imported.putIfAbsent(dependency.getTargetClass().getName(), dependency.getTargetClass()));
    }

    boolean isAssignableTo(String name, Class<?> target) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(name);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (current.equals(target.getName())) {
                return true;
            }
            if (!visited.add(current) || current.equals(Object.class.getName())) {
                continue;
            }
            JavaClass known = imported.get(current);
            if (known != null && known.isAssignableTo(target)) {
                return true;
            }
            pending.addAll(parents.computeIfAbsent(current, this::readParents));
        }
        return false;
    }

    private List<String> readParents(String name) {
        JavaClass known = imported.get(name);
        if (known != null && known.isFullyImported()) {
            List<String> result = new ArrayList<>();
            known.getRawSuperclass().ifPresent(parent -> result.add(parent.getName()));
            known.getRawInterfaces().forEach(parent -> result.add(parent.getName()));
            return List.copyOf(result);
        }
        String path = name.replace('.', '/') + ".class";
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        URL resource = contextLoader == null ? null : contextLoader.getResource(path);
        if (resource == null) {
            resource = ThreadFactoryTypeHierarchy.class.getClassLoader().getResource(path);
        }
        if (resource == null) {
            throw new IllegalStateException("ThreadFactory hierarchy bytecode is unavailable");
        }
        ClassReader reader = ThreadFactoryLambdaAnalysis.classReader(resource);
        if (!reader.getClassName().replace('/', '.').equals(name)) {
            throw new IllegalStateException("ThreadFactory hierarchy bytecode does not match the requested class");
        }
        List<String> result = new ArrayList<>();
        if (reader.getSuperName() != null) {
            result.add(reader.getSuperName().replace('/', '.'));
        }
        Arrays.stream(reader.getInterfaces())
                .map(type -> type.replace('/', '.'))
                .forEach(result::add);
        return List.copyOf(result);
    }
}
