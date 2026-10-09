package io.github.jdubois.bootui.core.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Contract tests over every public record in {@code io.github.jdubois.bootui.core.dto}.
 *
 * <p>The DTO package is discovered reflectively rather than spot-checked, so a newly added DTO that
 * forgets its defensive copy fails here instead of silently shipping a mutable "immutable" record.</p>
 */
@SuppressWarnings("unchecked")
class CoreDtoImmutabilityTests {

    private static final String PROBE = "probe";
    private static final String MUTATION = "mutation";

    /**
     * Guards the discovery itself: if the class scan silently found nothing, every generated test
     * would vacuously pass.
     */
    @Test
    void discoversTheDtoRecordsThatCarryCollections() {
        List<Class<?>> records = dtoRecords();

        assertThat(records).hasSizeGreaterThanOrEqualTo(200);
        assertThat(records.stream().filter(CoreDtoImmutabilityTests::hasCollectionComponent))
                .hasSizeGreaterThanOrEqualTo(130);
        assertThat(records)
                .contains(
                        OverviewDto.class,
                        RepositoriesReport.class,
                        PanelsReport.class,
                        MemoryReport.class,
                        ConfigReport.class);
    }

    @TestFactory
    Stream<DynamicTest> everyCollectionComponentIsDefensivelyCopied() {
        return dtoRecords().stream()
                .filter(CoreDtoImmutabilityTests::hasCollectionCarryingComponent)
                .map(type -> DynamicTest.dynamicTest(type.getSimpleName(), () -> assertDefensivelyCopied(type)));
    }

    @TestFactory
    Stream<DynamicTest> everyCollectionComponentIsNeverNull() {
        return dtoRecords().stream()
                .filter(CoreDtoImmutabilityTests::hasCollectionComponent)
                .map(type ->
                        DynamicTest.dynamicTest(type.getSimpleName(), () -> assertNullCollectionsBecomeEmpty(type)));
    }

    @Test
    void nestedReportCollectionsStayImmutableThroughEveryLevel() {
        List<String> stackTrace = new ArrayList<>(List.of("com.example.Boom.run(Boom.java:1)"));
        ThreadInfoDto thread = new ThreadInfoDto(
                1L,
                "main",
                "RUNNABLE",
                5,
                false,
                false,
                null,
                null,
                0L,
                0L,
                false,
                false,
                false,
                null,
                null,
                null,
                stackTrace);
        List<Long> deadlocked = new ArrayList<>(List.of(1L));
        List<ThreadInfoDto> threads = new ArrayList<>(List.of(thread));
        ThreadDumpReport report = new ThreadDumpReport(
                true, null, 1L, 1, 0, 1, 1L, true, true, false, deadlocked, new ArrayList<>(), threads, null);

        stackTrace.clear();
        deadlocked.clear();
        threads.clear();

        assertThat(report.threads()).hasSize(1);
        assertThat(report.deadlockedThreadIds()).containsExactly(1L);
        ThreadInfoDto held = report.threads().get(0);
        assertThat(held.stackTrace()).containsExactly("com.example.Boom.run(Boom.java:1)");
        assertThatThrownBy(() -> report.threads().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> report.deadlockedThreadIds().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> held.stackTrace().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mapComponentsKeepInsertionOrderSoSerializationStaysStable() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("zeta", "1");
        headers.put("alpha", "2");
        headers.put("mid", "3");

        HttpProbeRequest request = new HttpProbeRequest("GET", "/api", null, headers);
        headers.put("added", "4");

        assertThat(request.headers().keySet()).containsExactly("zeta", "alpha", "mid");
        assertThatThrownBy(() -> request.headers().put("k", "v")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void defensiveCopiesPreserveRecordEqualityAndHashCode() {
        List<String> profiles = new ArrayList<>(List.of("dev", "local"));
        OverviewDto first = new OverviewDto(
                "1",
                "app",
                "Spring Boot",
                "4.0",
                "17",
                "vendor",
                profiles,
                List.of(),
                "SERVLET",
                8080,
                null,
                "/",
                1L,
                null,
                null);
        OverviewDto second = new OverviewDto(
                "1",
                "app",
                "Spring Boot",
                "4.0",
                "17",
                "vendor",
                List.of("dev", "local"),
                new ArrayList<>(),
                "SERVLET",
                8080,
                null,
                "/",
                1L,
                null,
                null);

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSameHashCodeAs(second);
        assertThat(first.toString()).isEqualTo(second.toString());
    }

    @Test
    void collectionsCarriedByLooselyTypedComponentsAreSnapshotted() {
        List<String> attributeValue = new ArrayList<>(List.of("a", "b"));
        SpanAttributeDto attribute = new SpanAttributeDto("http.methods", "ARRAY", attributeValue);

        Map<String, Object> details = new LinkedHashMap<>();
        List<String> nested = new ArrayList<>(List.of("db1"));
        details.put("databases", nested);
        HealthNodeDto node = new HealthNodeDto("db", "UP", details, List.of());

        List<String> defaultValue = new ArrayList<>(List.of("INFO"));
        ConfigPropertyDto property = new ConfigPropertyDto(
                "logging.level", new ArrayList<>(List.of("DEBUG")), "env", null, false, false, null, defaultValue);

        attributeValue.add("mutated");
        nested.add("mutated");
        details.put("mutated", "mutated");
        defaultValue.add("mutated");

        assertThat((List<Object>) attribute.value()).containsExactly("a", "b");
        assertThatThrownBy(() -> ((List<Object>) attribute.value()).add("x"))
                .isInstanceOf(UnsupportedOperationException.class);

        Map<String, Object> heldDetails = (Map<String, Object>) node.details();
        assertThat(heldDetails).containsOnlyKeys("databases");
        assertThat((List<Object>) heldDetails.get("databases")).containsExactly("db1");
        assertThatThrownBy(() -> ((List<Object>) heldDetails.get("databases")).add("x"))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat((List<Object>) property.defaultValue()).containsExactly("INFO");
        assertThat((List<Object>) property.value()).containsExactly("DEBUG");
    }

    @Test
    void arraysCarriedByLooselyTypedComponentsBecomeImmutableLists() {
        String[] values = {"a", "b"};

        SpanAttributeDto attribute = new SpanAttributeDto("http.methods", "ARRAY", values);
        values[0] = "mutated";

        assertThat((List<Object>) attribute.value()).containsExactly("a", "b");
        assertThatThrownBy(() -> ((List<Object>) attribute.value()).add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void scalarsBehindLooselyTypedComponentsArePassedThroughUnchanged() {
        assertThat(new SpanAttributeDto("http.status", "LONG", 200L).value()).isEqualTo(200L);
        assertThat(new SpanAttributeDto("http.route", "STRING", "/a").value()).isEqualTo("/a");
        assertThat(new SpanAttributeDto("missing", "NULL", null).value()).isNull();
    }

    private static void assertDefensivelyCopied(Class<?> type) throws Exception {
        RecordComponent[] components = type.getRecordComponents();
        Object[] arguments = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            arguments[i] = probeValue(components[i].getType());
        }

        Object instance = canonicalConstructor(type).newInstance(arguments);

        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            if (!carriesCollection(component.getType())) {
                continue;
            }
            String where = type.getSimpleName() + "." + component.getName() + "()";
            mutate(arguments[i], MUTATION);
            Object held = component.getAccessor().invoke(instance);

            assertThat(held).as(where + " must not be null").isNotNull();
            assertThat(sizeOf(held))
                    .as(where + " must not observe mutation of the caller-owned collection")
                    .isEqualTo(1);
            assertThatThrownBy(() -> mutate(held, MUTATION))
                    .as(where + " must reject mutation of the accessor-returned collection")
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    private static void assertNullCollectionsBecomeEmpty(Class<?> type) throws Exception {
        RecordComponent[] components = type.getRecordComponents();
        Object[] arguments = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            Class<?> componentType = components[i].getType();
            arguments[i] = isCollection(componentType) ? null : probeValue(componentType);
        }

        Object instance = canonicalConstructor(type).newInstance(arguments);

        for (RecordComponent component : components) {
            if (!isCollection(component.getType())) {
                continue;
            }
            Object held = component.getAccessor().invoke(instance);
            assertThat(held)
                    .as(type.getSimpleName() + "." + component.getName() + "() must normalize null to empty")
                    .isNotNull();
            assertThat(sizeOf(held)).isZero();
        }
    }

    private static Constructor<?> canonicalConstructor(Class<?> type) throws NoSuchMethodException {
        Class<?>[] parameterTypes = Stream.of(type.getRecordComponents())
                .map(RecordComponent::getType)
                .toArray(Class<?>[]::new);
        Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        return constructor;
    }

    private static boolean hasCollectionComponent(Class<?> type) {
        return Stream.of(type.getRecordComponents()).anyMatch(component -> isCollection(component.getType()));
    }

    private static boolean hasCollectionCarryingComponent(Class<?> type) {
        return Stream.of(type.getRecordComponents()).anyMatch(component -> carriesCollection(component.getType()));
    }

    private static boolean isCollection(Class<?> type) {
        return Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type);
    }

    /**
     * A loosely typed {@code Object} component carries framework-supplied JSON payloads, so it has to
     * snapshot a collection just like a declared collection component does.
     */
    private static boolean carriesCollection(Class<?> type) {
        return isCollection(type) || type == Object.class;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void mutate(Object collection, String value) {
        if (collection instanceof Map map) {
            map.put(value, value);
        } else {
            ((Collection) collection).add(value);
        }
    }

    private static int sizeOf(Object collection) {
        return collection instanceof Map<?, ?> map ? map.size() : ((Collection<?>) collection).size();
    }

    private static Object probeValue(Class<?> type) {
        if (Map.class.isAssignableFrom(type)) {
            Map<Object, Object> map = new LinkedHashMap<>();
            map.put(PROBE, PROBE);
            return map;
        }
        if (Collection.class.isAssignableFrom(type)) {
            List<Object> list = new ArrayList<>();
            list.add(PROBE);
            return list;
        }
        if (type == Object.class) {
            List<Object> list = new ArrayList<>();
            list.add(PROBE);
            return list;
        }
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return ' ';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        return 0d;
    }

    private static List<Class<?>> dtoRecords() {
        Path root;
        try {
            root = Path.of(CoreDtoImmutabilityTests.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        Path packageRoot = root.resolveSibling("classes")
                .resolve(DtoCollections.class.getPackageName().replace('.', '/'));
        if (!Files.isDirectory(packageRoot)) {
            return fail("Could not locate the compiled DTO package at " + packageRoot);
        }
        try (Stream<Path> files = Files.list(packageRoot)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(fileName -> fileName.endsWith(".class") && !fileName.contains("$"))
                    .map(fileName -> fileName.substring(0, fileName.length() - ".class".length()))
                    .map(simpleName -> loadClass(DtoCollections.class.getPackageName() + "." + simpleName))
                    .filter(Class::isRecord)
                    .filter(type -> Modifier.isPublic(type.getModifiers()))
                    .sorted(java.util.Comparator.comparing(Class::getName))
                    .collect(java.util.stream.Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
