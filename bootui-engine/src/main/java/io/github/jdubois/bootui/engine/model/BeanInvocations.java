package io.github.jdubois.bootui.engine.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Maps the calls Code Paths observed between application classes to calls between the beans of those classes
 * ({@code docs/PLAN-v2.md} §5.14, M5-4c), for the runtime model's {@link EdgeType#INVOKES} edges and Code Paths'
 * <b>Beans at runtime</b>. A method's class is the class that declares it, so an inherited method names its
 * superclass: a class maps to the beans whose type is it or extends it, proxies' generated subclasses ({@code $$})
 * reading as their user class; among several, a called class maps to the one the calling bean declares a dependency on,
 * else to the one whose type it is exactly; a class of no bean, or still of several, maps to none, and a bean calling
 * itself is not an edge. The superclasses are read only when a bean's class can be loaded without initializing it.
 * Stateless.
 */
public final class BeanInvocations {

    private BeanInvocations() {}

    /**
     * One observed bean-to-bean call pair.
     *
     * @param from the calling bean's name
     * @param fromRepository whether the calling bean is a repository
     * @param to the called bean's name
     * @param toRepository whether the called bean is a repository
     * @param calls how many calls Code Paths counted
     */
    public record Edge(String from, boolean fromRepository, String to, boolean toRepository, long calls) {

        public Edge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    /**
     * The observed calls between beans.
     *
     * @param edges the bean-to-bean calls, most calls first
     * @param unmappedCalls calls between classes that are not each exactly one bean's
     */
    public record Resolved(List<Edge> edges, long unmappedCalls) {

        public Resolved {
            edges = List.copyOf(edges);
        }
    }

    /** {@code invocations} between the classes of {@code beans}, reading superclasses by reflection. */
    public static Resolved resolve(List<StructureSnapshot.Bean> beans, List<ClassInvocation> invocations) {
        return resolve(beans, invocations, BeanInvocations::superclasses);
    }

    /**
     * {@code invocations} between the classes of {@code beans}.
     *
     * @param superclasses a bean type's superclasses by binary name, nearest first, {@code Object} left out; empty when
     *     unknown
     */
    public static Resolved resolve(
            List<StructureSnapshot.Bean> beans,
            List<ClassInvocation> invocations,
            Function<String, List<String>> superclasses) {
        if (invocations == null || invocations.isEmpty()) {
            return new Resolved(List.of(), 0L);
        }
        Map<String, List<StructureSnapshot.Bean>> byClass = new HashMap<>();
        Map<String, List<StructureSnapshot.Bean>> bySuperclass = new HashMap<>();
        for (StructureSnapshot.Bean bean : beans == null ? List.<StructureSnapshot.Bean>of() : beans) {
            String type = userClass(bean.type());
            if (type == null) {
                continue;
            }
            byClass.computeIfAbsent(type, ignored -> new ArrayList<>()).add(bean);
            List<String> supers;
            try {
                supers = superclasses == null ? List.of() : superclasses.apply(type);
            } catch (RuntimeException ex) {
                supers = List.of();
            }
            for (String superclass : supers == null ? List.<String>of() : supers) {
                bySuperclass
                        .computeIfAbsent(superclass, ignored -> new ArrayList<>())
                        .add(bean);
            }
        }
        Map<List<String>, long[]> calls = new LinkedHashMap<>();
        Map<String, StructureSnapshot.Bean> named = new HashMap<>();
        long unmapped = 0;
        for (ClassInvocation invocation : invocations) {
            StructureSnapshot.Bean from = bean(userClass(invocation.callerClass()), byClass, bySuperclass, null);
            StructureSnapshot.Bean to = bean(userClass(invocation.calleeClass()), byClass, bySuperclass, from);
            if (from == null || to == null) {
                unmapped += invocation.calls();
                continue;
            }
            if (from.name().equals(to.name())) {
                continue;
            }
            named.put(from.name(), from);
            named.put(to.name(), to);
            calls.computeIfAbsent(List.of(from.name(), to.name()), ignored -> new long[1])[0] += invocation.calls();
        }
        List<Edge> edges = new ArrayList<>();
        calls.forEach((pair, count) -> edges.add(new Edge(
                pair.get(0),
                named.get(pair.get(0)).repository(),
                pair.get(1),
                named.get(pair.get(1)).repository(),
                count[0])));
        edges.sort((left, right) -> {
            int byCalls = Long.compare(right.calls(), left.calls());
            if (byCalls != 0) {
                return byCalls;
            }
            int byFrom = left.from().compareTo(right.from());
            return byFrom != 0 ? byFrom : left.to().compareTo(right.to());
        });
        return new Resolved(edges, unmapped);
    }

    /** The user class of {@code type}: a proxy's generated subclass, {@code Foo$$SpringCGLIB$$0}, reads as {@code Foo}. */
    public static String userClass(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        int generated = type.indexOf("$$");
        return (generated > 0 ? type.substring(0, generated) : type).strip();
    }

    /**
     * The bean of {@code type}, a method's declaring class: the one bean whose type is it or extends it; among several,
     * the one {@code caller} declares a dependency on, else the one whose type it is exactly; else {@code null}.
     */
    private static StructureSnapshot.Bean bean(
            String type,
            Map<String, List<StructureSnapshot.Bean>> byClass,
            Map<String, List<StructureSnapshot.Bean>> bySuperclass,
            StructureSnapshot.Bean caller) {
        if (type == null) {
            return null;
        }
        List<StructureSnapshot.Bean> exact = byClass.getOrDefault(type, List.of());
        Set<StructureSnapshot.Bean> candidates = new LinkedHashSet<>(exact);
        candidates.addAll(bySuperclass.getOrDefault(type, List.of()));
        if (candidates.size() == 1) {
            return candidates.iterator().next();
        }
        if (caller != null && !candidates.isEmpty()) {
            StructureSnapshot.Bean declared = null;
            int count = 0;
            for (StructureSnapshot.Bean candidate : candidates) {
                if (caller.dependencies().contains(candidate.name())) {
                    declared = candidate;
                    count++;
                }
            }
            if (count == 1) {
                return declared;
            }
        }
        return exact.size() == 1 ? exact.get(0) : null;
    }

    /** The most superclasses read for one bean type. */
    static final int MAX_SUPERCLASSES = 16;

    /**
     * The superclasses of {@code type}, nearest first, {@code Object} left out, loaded without initialization from the
     * thread's context class loader, else this class's; empty when it cannot be loaded.
     */
    static List<String> superclasses(String type) {
        if (type == null) {
            return List.of();
        }
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> loaded;
            try {
                loaded = Class.forName(type, false, loader == null ? BeanInvocations.class.getClassLoader() : loader);
            } catch (ClassNotFoundException ex) {
                loaded = Class.forName(type, false, BeanInvocations.class.getClassLoader());
            }
            List<String> names = new ArrayList<>();
            for (Class<?> superclass = loaded.getSuperclass();
                    superclass != null && superclass != Object.class && names.size() < MAX_SUPERCLASSES;
                    superclass = superclass.getSuperclass()) {
                names.add(superclass.getName());
            }
            return names;
        } catch (ClassNotFoundException | LinkageError | RuntimeException ex) {
            return List.of();
        }
    }
}
