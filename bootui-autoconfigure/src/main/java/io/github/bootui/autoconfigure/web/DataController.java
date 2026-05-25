package io.github.bootui.autoconfigure.web;

import io.github.bootui.core.BootUiDtos.RepositoriesReport;
import io.github.bootui.core.BootUiDtos.RepositoryDetailDto;
import io.github.bootui.core.BootUiDtos.RepositoryDto;
import io.github.bootui.core.BootUiDtos.RepositoryMethodDto;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.support.RepositoryFactoryInformation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes Spring Data repositories declared in the current application context.
 *
 * <p>This controller is read-only: it never invokes repository methods. It only
 * surfaces metadata that Spring Data has already computed about the repositories
 * it manages.</p>
 */
@RestController
@ConditionalOnClass(RepositoryFactoryInformation.class)
@RequestMapping("/bootui/api/data")
public class DataController {

    private final ObjectProvider<ListableBeanFactory> beanFactoryProvider;

    public DataController(ObjectProvider<ListableBeanFactory> beanFactoryProvider) {
        this.beanFactoryProvider = beanFactoryProvider;
    }

    @GetMapping("/repositories")
    public RepositoriesReport repositories() {
        List<RepositoryEntry> entries = discover();
        List<RepositoryDto> summaries = entries.stream()
                .map(this::toSummary)
                .sorted(Comparator.comparing(RepositoryDto::repositoryInterface,
                        Comparator.nullsLast(String::compareTo)))
                .collect(Collectors.toList());
        return new RepositoriesReport(true, summaries.size(), summaries);
    }

    @GetMapping("/repositories/{name}")
    public ResponseEntity<RepositoryDetailDto> repository(@PathVariable String name) {
        for (RepositoryEntry entry : discover()) {
            if (matches(entry, name)) {
                return ResponseEntity.ok(toDetail(entry));
            }
        }
        return ResponseEntity.notFound().build();
    }

    private List<RepositoryEntry> discover() {
        ListableBeanFactory factory = beanFactoryProvider.getIfAvailable();
        if (factory == null) {
            return List.of();
        }
        String[] beanNames = factory.getBeanNamesForType(RepositoryFactoryInformation.class);
        List<RepositoryEntry> entries = new ArrayList<>(beanNames.length);
        for (String beanName : beanNames) {
            RepositoryFactoryInformation<?, ?> info;
            try {
                info = factory.getBean(beanName, RepositoryFactoryInformation.class);
            } catch (Exception ex) {
                continue;
            }
            RepositoryInformation repositoryInformation;
            try {
                repositoryInformation = info.getRepositoryInformation();
            } catch (Exception ex) {
                continue;
            }
            entries.add(new RepositoryEntry(strip(beanName), info, repositoryInformation));
        }
        return entries;
    }

    private boolean matches(RepositoryEntry entry, String name) {
        if (name == null) {
            return false;
        }
        if (name.equals(entry.beanName())) {
            return true;
        }
        Class<?> iface = entry.information().getRepositoryInterface();
        return iface != null && (name.equals(iface.getName()) || name.equals(iface.getSimpleName()));
    }

    private RepositoryDto toSummary(RepositoryEntry entry) {
        RepositoryInformation info = entry.information();
        Class<?> iface = info.getRepositoryInterface();
        Class<?> domainType = info.getDomainType();
        Class<?> idType = info.getIdType();
        Class<?> custom = info.getRepositoryBaseClass();
        int fragments = fragmentCount(entry);
        int queryMethods = queryMethodCount(info);
        return new RepositoryDto(
                entry.beanName(),
                iface == null ? null : iface.getName(),
                domainType == null ? null : domainType.getName(),
                idType == null ? null : idType.getName(),
                detectStoreModule(iface),
                custom == null ? null : custom.getName(),
                queryMethods,
                fragments);
    }

    private RepositoryDetailDto toDetail(RepositoryEntry entry) {
        RepositoryInformation info = entry.information();
        Class<?> iface = info.getRepositoryInterface();
        Class<?> domainType = info.getDomainType();
        Class<?> idType = info.getIdType();
        Class<?> custom = info.getRepositoryBaseClass();
        List<RepositoryMethodDto> methods = new ArrayList<>();
        if (iface != null) {
            Method[] declared = iface.getMethods();
            Arrays.sort(declared, Comparator.comparing(Method::getName));
            for (Method method : declared) {
                if (method.getDeclaringClass() == Object.class) {
                    continue;
                }
                methods.add(toMethodDto(info, method));
            }
        }
        return new RepositoryDetailDto(
                entry.beanName(),
                iface == null ? null : iface.getName(),
                domainType == null ? null : domainType.getName(),
                idType == null ? null : idType.getName(),
                detectStoreModule(iface),
                custom == null ? null : custom.getName(),
                methods,
                Collections.emptyList());
    }

    private RepositoryMethodDto toMethodDto(RepositoryInformation info, Method method) {
        String origin;
        if (info.isCustomMethod(method)) {
            origin = "FRAGMENT";
        } else if (info.isQueryMethod(method)) {
            origin = "QUERY";
        } else if (method.isDefault()) {
            origin = "DEFAULT";
        } else if (info.isBaseClassMethod(method)) {
            origin = "CRUD";
        } else {
            origin = "DERIVED";
        }
        QueryAnnotation queryAnnotation = readQueryAnnotation(method);
        if (queryAnnotation != null && "QUERY".equals(origin) && queryAnnotation.hasValue) {
            origin = "ANNOTATED";
        }
        return new RepositoryMethodDto(
                method.getName(),
                signatureOf(method),
                origin,
                queryAnnotation == null ? null : queryAnnotation.value,
                queryAnnotation != null && queryAnnotation.nativeQuery,
                queryAnnotation == null ? null : queryAnnotation.name);
    }

    private int queryMethodCount(RepositoryInformation info) {
        int count = 0;
        Class<?> iface = info.getRepositoryInterface();
        if (iface == null) {
            return 0;
        }
        for (Method method : iface.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            if (info.isQueryMethod(method)) {
                count++;
            }
        }
        return count;
    }

    private int fragmentCount(RepositoryEntry entry) {
        Class<?> iface = entry.information().getRepositoryInterface();
        if (iface == null) {
            return 0;
        }
        int count = 0;
        for (Method method : iface.getMethods()) {
            if (entry.information().isCustomMethod(method)) {
                count++;
            }
        }
        return count;
    }

    private String signatureOf(Method method) {
        String params = Arrays.stream(method.getParameterTypes())
                .map(Class::getSimpleName)
                .collect(Collectors.joining(", "));
        String returnType = method.getReturnType().getSimpleName();
        return returnType + " " + method.getName() + "(" + params + ")";
    }

    private String detectStoreModule(@Nullable Class<?> iface) {
        if (iface == null) {
            return "GENERIC";
        }
        String pkg = iface.getPackageName();
        // Walk the interface hierarchy looking for known store-specific marker packages.
        for (Class<?> c : collectRepositoryInterfaces(iface)) {
            String cp = c.getPackageName();
            if (cp.startsWith("org.springframework.data.jpa")) {
                return "JPA";
            }
            if (cp.startsWith("org.springframework.data.jdbc")) {
                return "JDBC";
            }
            if (cp.startsWith("org.springframework.data.mongodb")) {
                return "MONGO";
            }
            if (cp.startsWith("org.springframework.data.r2dbc")) {
                return "R2DBC";
            }
            if (cp.startsWith("org.springframework.data.redis")) {
                return "REDIS";
            }
            if (cp.startsWith("org.springframework.data.cassandra")) {
                return "CASSANDRA";
            }
            if (cp.startsWith("org.springframework.data.neo4j")) {
                return "NEO4J";
            }
            if (cp.startsWith("org.springframework.data.elasticsearch")) {
                return "ELASTICSEARCH";
            }
            if (cp.startsWith("org.springframework.data.couchbase")) {
                return "COUCHBASE";
            }
        }
        if (pkg.startsWith("org.springframework.data")) {
            return "COMMONS";
        }
        return "GENERIC";
    }

    private List<Class<?>> collectRepositoryInterfaces(Class<?> root) {
        List<Class<?>> all = new ArrayList<>();
        collect(root, all);
        return all;
    }

    private void collect(Class<?> type, List<Class<?>> sink) {
        if (type == null || sink.contains(type)) {
            return;
        }
        sink.add(type);
        for (Class<?> i : type.getInterfaces()) {
            collect(i, sink);
        }
    }

    /**
     * Read {@code @org.springframework.data.jpa.repository.Query} (or any store-specific
     * {@code Query} annotation) reflectively so we don't require Spring Data JPA on the
     * classpath.
     */
    @Nullable
    private QueryAnnotation readQueryAnnotation(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            String typeName = annotation.annotationType().getName();
            if (!typeName.endsWith(".Query")) {
                continue;
            }
            if (!typeName.startsWith("org.springframework.data.")) {
                continue;
            }
            String value = readAttribute(annotation, "value");
            String name = readAttribute(annotation, "name");
            Boolean nativeQuery = readBooleanAttribute(annotation, "nativeQuery");
            return new QueryAnnotation(
                    value == null || value.isBlank() ? null : value,
                    name == null || name.isBlank() ? null : name,
                    Boolean.TRUE.equals(nativeQuery),
                    value != null && !value.isBlank());
        }
        return null;
    }

    @Nullable
    private String readAttribute(Annotation annotation, String attribute) {
        try {
            Method m = annotation.annotationType().getMethod(attribute);
            Object value = m.invoke(annotation);
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    @Nullable
    private Boolean readBooleanAttribute(Annotation annotation, String attribute) {
        try {
            Method m = annotation.annotationType().getMethod(attribute);
            Object value = m.invoke(annotation);
            return value instanceof Boolean b ? b : null;
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private String strip(String beanName) {
        return beanName.startsWith("&") ? beanName.substring(1) : beanName;
    }

    private record RepositoryEntry(String beanName,
                                   RepositoryFactoryInformation<?, ?> factoryInformation,
                                   RepositoryInformation information) {
    }

    private record QueryAnnotation(@Nullable String value,
                                   @Nullable String name,
                                   boolean nativeQuery,
                                   boolean hasValue) {
    }
}
