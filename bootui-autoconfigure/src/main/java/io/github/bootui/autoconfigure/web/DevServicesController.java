package io.github.bootui.autoconfigure.web;

import io.github.bootui.autoconfigure.BootUiProperties;
import io.github.bootui.autoconfigure.BootUiProperties.ValueExposure;
import io.github.bootui.core.BootUiDtos.DevServiceDto;
import io.github.bootui.core.BootUiDtos.DevServiceLogReport;
import io.github.bootui.core.BootUiDtos.DevServicePortDto;
import io.github.bootui.core.BootUiDtos.DevServiceRestartResult;
import io.github.bootui.core.BootUiDtos.DevServicesReport;
import io.github.bootui.core.SecretMasker;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.container.ContainerImageMetadata;
import org.springframework.boot.autoconfigure.service.connection.ConnectionDetails;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/bootui/api/dev-services")
public class DevServicesController implements ApplicationListener<ApplicationEvent> {

    private static final String DOCKER_COMPOSE_EVENT =
            "org.springframework.boot.docker.compose.lifecycle.DockerComposeServicesReadyEvent";

    private static final Set<String> SKIPPED_DETAIL_PROPERTIES = Set.of(
            "class",
            "origin",
            "sslBundle");

    private static final Pattern URL_CREDENTIALS = Pattern.compile("([a-z][a-z0-9+.-]*://)([^:/@\\s]+):([^@\\s]+)@",
            Pattern.CASE_INSENSITIVE);

    private final ConfigurableApplicationContext applicationContext;

    private final BootUiProperties properties;

    private final SecretMasker masker = new SecretMasker();

    private final Map<String, DevServiceDto> dockerComposeServices = new ConcurrentHashMap<>();

    private volatile long dockerComposeSnapshotTimestamp;

    public DevServicesController(ConfigurableApplicationContext applicationContext, BootUiProperties properties) {
        this.applicationContext = applicationContext;
        this.properties = properties;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (!DOCKER_COMPOSE_EVENT.equals(event.getClass().getName())) {
            return;
        }
        List<?> runningServices = invokeList(event, "getRunningServices");
        Map<String, DevServiceDto> snapshot = new LinkedHashMap<>();
        for (Object runningService : runningServices) {
            DevServiceDto dto = dockerComposeDto(runningService);
            snapshot.put(dto.id(), dto);
        }
        this.dockerComposeServices.clear();
        this.dockerComposeServices.putAll(snapshot);
        this.dockerComposeSnapshotTimestamp = System.currentTimeMillis();
    }

    @GetMapping
    public DevServicesReport list() {
        Map<String, DevServiceDto> services = new LinkedHashMap<>();
        this.dockerComposeServices.values().stream()
                .sorted(Comparator.comparing(DevServiceDto::name))
                .forEach(service -> services.put(service.id(), service));
        discoverTestcontainers(services);
        discoverConnectionDetails(services);
        List<DevServiceDto> sorted = services.values().stream()
                .sorted(Comparator.comparing(DevServiceDto::source).thenComparing(DevServiceDto::name))
                .toList();
        return new DevServicesReport(
                isPresent("org.springframework.boot.docker.compose.lifecycle.DockerComposeServicesReadyEvent"),
                isPresent("org.testcontainers.lifecycle.Startable"),
                resolveSnapshotTimestamp(),
                sorted.size(),
                sorted);
    }

    @GetMapping("/{id}/logs")
    public DevServiceLogReport logs(@PathVariable String id) {
        Object bean = findBeanBackedService(id);
        if (bean == null || findNoArgMethod(bean.getClass(), "getLogs") == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Logs are not available for this service");
        }
        Object logs = invoke(bean, "getLogs");
        String text = logs == null ? "" : String.valueOf(logs);
        int maxBytes = Math.max(1024, properties.getDevServices().getLogTailBytes());
        boolean truncated = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes;
        if (truncated) {
            text = tailByBytes(text, maxBytes);
        }
        return new DevServiceLogReport(id, text, truncated, maxBytes);
    }

    @PostMapping("/{id}/restart")
    public DevServiceRestartResult restart(@PathVariable String id) {
        Object bean = findBeanBackedService(id);
        if (bean == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found");
        }
        if (!properties.getDevServices().isRestartEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Restart is disabled. Set bootui.dev-services.restart-enabled=true to allow it.");
        }
        Method start = findNoArgMethod(bean.getClass(), "start");
        Method stop = findNoArgMethod(bean.getClass(), "stop");
        if (start == null || stop == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Service does not support restart");
        }
        try {
            stop.invoke(bean);
            start.invoke(bean);
            return new DevServiceRestartResult(id, "restarted",
                    "Service restarted. Already-created client beans may need an application restart to reconnect.");
        }
        catch (IllegalAccessException | InvocationTargetException ex) {
            Throwable cause = ex instanceof InvocationTargetException ite && ite.getCause() != null
                    ? ite.getCause()
                    : ex;
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, cause.getMessage(), cause);
        }
    }

    private long resolveSnapshotTimestamp() {
        if (this.dockerComposeSnapshotTimestamp > 0) {
            return this.dockerComposeSnapshotTimestamp;
        }
        return Instant.now().toEpochMilli();
    }

    private void discoverTestcontainers(Map<String, DevServiceDto> services) {
        if (!isPresent("org.testcontainers.lifecycle.Startable")) {
            return;
        }
        ListableBeanFactory beanFactory = applicationContext.getBeanFactory();
        for (String beanName : BeanFactoryUtils.beanNamesIncludingAncestors(beanFactory)) {
            Class<?> type = safeGetType(beanFactory, beanName);
            if (type == null || !isTestcontainerType(type)) {
                continue;
            }
            Object bean = safeGetBean(beanFactory, beanName);
            if (bean == null) {
                continue;
            }
            DevServiceDto dto = testcontainersDto(beanName, bean);
            services.put(dto.id(), dto);
        }
    }

    private void discoverConnectionDetails(Map<String, DevServiceDto> services) {
        ListableBeanFactory beanFactory = applicationContext.getBeanFactory();
        String[] names = beanFactory.getBeanNamesForType(ConnectionDetails.class, false, false);
        for (String beanName : names) {
            ConnectionDetails details = safeGetBean(beanFactory, beanName, ConnectionDetails.class);
            if (details == null) {
                continue;
            }
            DevServiceDto dto = connectionDetailsDto(beanFactory, beanName, details);
            services.put(dto.id(), dto);
        }
    }

    private DevServiceDto dockerComposeDto(Object runningService) {
        String name = stringValue(invoke(runningService, "name"));
        String image = stringValue(invoke(runningService, "image"));
        String host = stringValue(invoke(runningService, "host"));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("snapshot", "Captured when Spring Boot reported Docker Compose services ready");
        Map<?, ?> labels = asMap(invoke(runningService, "labels"));
        String type = inferType(name, image, labels);
        return new DevServiceDto(
                "compose:" + slug(name),
                name,
                type,
                "Docker Compose",
                image,
                "READY_AT_STARTUP",
                host,
                composePorts(invoke(runningService, "ports")),
                sanitizeDetails(details),
                false,
                false,
                "Docker Compose status is a startup snapshot; Spring Boot does not expose live per-service restart.");
    }

    private DevServiceDto testcontainersDto(String beanName, Object bean) {
        String image = stringValue(invoke(bean, "getDockerImageName"));
        String host = firstNonBlank(stringValue(invoke(bean, "getHost")),
                stringValue(invoke(bean, "getTestHostIpAddress")));
        boolean running = Boolean.TRUE.equals(invoke(bean, "isRunning"));
        String name = firstNonBlank(stringValue(invoke(bean, "getContainerName")), beanName);
        Map<String, Object> details = new LinkedHashMap<>();
        addIfPresent(details, "containerId", invoke(bean, "getContainerId"));
        addIfPresent(details, "networkMode", invoke(bean, "getNetworkMode"));
        addIfPresent(details, "reuse", invoke(bean, "isShouldBeReused"));
        boolean restartable = properties.getDevServices().isRestartEnabled()
                && findNoArgMethod(bean.getClass(), "start") != null
                && findNoArgMethod(bean.getClass(), "stop") != null;
        return new DevServiceDto(
                "bean:" + beanName,
                name,
                inferType(name, image, Map.of()),
                "Testcontainers",
                image,
                running ? "RUNNING" : "STOPPED",
                host,
                testcontainersPorts(bean),
                sanitizeDetails(details),
                restartable,
                findNoArgMethod(bean.getClass(), "getLogs") != null,
                properties.getDevServices().isRestartEnabled()
                        ? "Restart may require application clients to reconnect."
                        : "Restart disabled by default; set bootui.dev-services.restart-enabled=true to allow it.");
    }

    private DevServiceDto connectionDetailsDto(ListableBeanFactory beanFactory, String beanName,
                                               ConnectionDetails details) {
        ContainerImageMetadata metadata = containerImageMetadata(beanFactory, beanName);
        String image = metadata == null ? null : metadata.imageName();
        Map<String, Object> connectionDetails = new LinkedHashMap<>();
        collectDetails("", details, connectionDetails, 0);
        return new DevServiceDto(
                "connection:" + beanName,
                readableName(beanName),
                inferType(beanName, image, Map.of()),
                "Connection details",
                image,
                "AVAILABLE",
                null,
                List.of(),
                sanitizeDetails(connectionDetails),
                false,
                false,
                "Spring Boot connection details bean; lifecycle is managed by Docker Compose or Testcontainers.");
    }

    private ContainerImageMetadata containerImageMetadata(ListableBeanFactory beanFactory, String beanName) {
        if (!(beanFactory instanceof ConfigurableListableBeanFactory configurableBeanFactory)) {
            return null;
        }
        try {
            return ContainerImageMetadata.getFrom(configurableBeanFactory.getBeanDefinition(beanName));
        }
        catch (NoSuchBeanDefinitionException ex) {
            return null;
        }
    }

    private void collectDetails(String prefix, Object source, Map<String, Object> details, int depth) {
        if (source == null || depth > 1) {
            return;
        }
        Method[] methods = source.getClass().getMethods();
        List<Method> sorted = new ArrayList<>(List.of(methods));
        sorted.sort(Comparator.comparing(Method::getName));
        for (Method method : sorted) {
            if (!isReadableProperty(method)) {
                continue;
            }
            String property = propertyName(method);
            if (SKIPPED_DETAIL_PROPERTIES.contains(property)) {
                continue;
            }
            Object value = invoke(source, method);
            if (value == null) {
                continue;
            }
            String key = prefix.isBlank() ? property : prefix + "." + property;
            if (isSimpleValue(value)) {
                details.put(key, value);
            }
            else if (depth == 0 && shouldRecurseInto(value)) {
                collectDetails(key, value, details, depth + 1);
            }
        }
    }

    private Map<String, Object> sanitizeDetails(Map<String, Object> details) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            sanitized.put(entry.getKey(), displayValue(entry.getKey(), entry.getValue()));
        }
        return sanitized;
    }

    private Object displayValue(String key, Object value) {
        if (properties.getExposeValues() == ValueExposure.METADATA_ONLY) {
            return null;
        }
        if (value == null || !properties.isMaskSecrets()) {
            return value;
        }
        if (masker.isSecret(key)) {
            return SecretMasker.MASKED_VALUE;
        }
        if (value instanceof CharSequence text && looksLikeUrlKey(key)) {
            return URL_CREDENTIALS.matcher(text).replaceAll("$1******@");
        }
        return value;
    }

    private boolean looksLikeUrlKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.endsWith("url") || normalized.endsWith("uri") || normalized.endsWith("uris");
    }

    private List<DevServicePortDto> composePorts(Object ports) {
        if (ports == null) {
            return List.of();
        }
        List<DevServicePortDto> result = new ArrayList<>();
        for (Object hostPort : invokeList(ports, "getAll")) {
            Integer port = asInteger(hostPort);
            if (port != null) {
                result.add(new DevServicePortDto(null, port, "tcp"));
            }
        }
        return result;
    }

    private List<DevServicePortDto> testcontainersPorts(Object bean) {
        List<DevServicePortDto> result = new ArrayList<>();
        for (Object exposed : invokeList(bean, "getExposedPorts")) {
            Integer containerPort = asInteger(exposed);
            Integer hostPort = containerPort == null ? null : asInteger(invoke(bean, "getMappedPort", containerPort));
            result.add(new DevServicePortDto(containerPort, hostPort, "tcp"));
        }
        return result;
    }

    private Object findBeanBackedService(String id) {
        if (id == null || !id.startsWith("bean:")) {
            if (this.dockerComposeServices.containsKey(id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Docker Compose services are snapshots and cannot be controlled by BootUI");
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found");
        }
        String beanName = id.substring("bean:".length());
        Object bean = safeGetBean(applicationContext.getBeanFactory(), beanName);
        if (bean == null || !isTestcontainerType(bean.getClass())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found");
        }
        return bean;
    }

    private boolean isReadableProperty(Method method) {
        if (method.getParameterCount() != 0 || !Modifier.isPublic(method.getModifiers())) {
            return false;
        }
        if (method.getReturnType() == Void.TYPE) {
            return false;
        }
        String name = method.getName();
        return (name.startsWith("get") && name.length() > 3) || (name.startsWith("is") && name.length() > 2);
    }

    private String propertyName(Method method) {
        String name = method.getName();
        String stripped = name.startsWith("is") ? name.substring(2) : name.substring(3);
        return Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1);
    }

    private boolean isSimpleValue(Object value) {
        Class<?> type = value.getClass();
        return value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || type.isEnum()
                || value instanceof URI;
    }

    private boolean shouldRecurseInto(Object value) {
        String packageName = value.getClass().getPackageName();
        return !packageName.startsWith("java.")
                && !packageName.startsWith("jdk.")
                && !packageName.startsWith("sun.");
    }

    private boolean isTestcontainerType(Class<?> type) {
        if (hasTypeName(type, "org.testcontainers.lifecycle.Startable")
                || hasTypeName(type, "org.testcontainers.containers.Container")) {
            return true;
        }
        String name = type.getName();
        return name.startsWith("org.testcontainers.") || name.contains("Testcontainer");
    }

    private boolean hasTypeName(Class<?> type, String expectedName) {
        if (type == null) {
            return false;
        }
        if (expectedName.equals(type.getName())) {
            return true;
        }
        for (Class<?> interfaceType : type.getInterfaces()) {
            if (hasTypeName(interfaceType, expectedName)) {
                return true;
            }
        }
        return hasTypeName(type.getSuperclass(), expectedName);
    }

    private String inferType(String name, String image, Map<?, ?> labels) {
        String combined = (String.valueOf(name) + " " + String.valueOf(image) + " " + labels).toLowerCase(Locale.ROOT);
        if (combined.contains("postgres")) {
            return "PostgreSQL";
        }
        if (combined.contains("mysql") || combined.contains("mariadb")) {
            return "MySQL";
        }
        if (combined.contains("redis")) {
            return "Redis";
        }
        if (combined.contains("kafka") || combined.contains("redpanda")) {
            return "Kafka";
        }
        if (combined.contains("mongo")) {
            return "MongoDB";
        }
        if (combined.contains("rabbit")) {
            return "RabbitMQ";
        }
        if (combined.contains("elasticsearch")) {
            return "Elasticsearch";
        }
        if (combined.contains("zipkin")) {
            return "Zipkin";
        }
        return "Service";
    }

    private String readableName(String beanName) {
        return beanName.replaceAll("([a-z])([A-Z])", "$1 $2");
    }

    private String slug(String value) {
        String normalized = value == null ? "service" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return normalized.isBlank() ? "service" : normalized;
    }

    private void addIfPresent(Map<String, Object> details, String key, Object value) {
        if (value != null) {
            details.put(key, value);
        }
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            }
            catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private Class<?> safeGetType(ListableBeanFactory beanFactory, String beanName) {
        try {
            return beanFactory.getType(beanName, false);
        }
        catch (BeansException ex) {
            return null;
        }
    }

    private Object safeGetBean(ListableBeanFactory beanFactory, String beanName) {
        try {
            return beanFactory.getBean(beanName);
        }
        catch (BeansException ex) {
            return null;
        }
    }

    private <T> T safeGetBean(ListableBeanFactory beanFactory, String beanName, Class<T> type) {
        try {
            return beanFactory.getBean(beanName, type);
        }
        catch (BeansException ex) {
            return null;
        }
    }

    private Object invoke(Object target, String methodName, Object... args) {
        if (target == null) {
            return null;
        }
        Method method = findMethod(target.getClass(), methodName, args);
        if (method == null) {
            return null;
        }
        return invoke(target, method, args);
    }

    private Object invoke(Object target, Method method, Object... args) {
        try {
            return method.invoke(target, args);
        }
        catch (IllegalAccessException | InvocationTargetException ex) {
            return null;
        }
    }

    private List<?> invokeList(Object target, String methodName) {
        Object value = invoke(target, methodName);
        if (value instanceof List<?> list) {
            return list;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> values = new ArrayList<>();
            iterable.forEach(values::add);
            return values;
        }
        return List.of();
    }

    private Method findNoArgMethod(Class<?> type, String methodName) {
        return findMethod(type, methodName);
    }

    private Method findMethod(Class<?> type, String methodName, Object... args) {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != args.length) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                if (args[i] != null && !ClassUtils.isAssignableValue(parameterTypes[i], args[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return method;
            }
        }
        return null;
    }

    private boolean isPresent(String className) {
        return ClassUtils.isPresent(className, applicationContext.getClassLoader());
    }

    private String tailByBytes(String text, int maxBytes) {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        String tail = new String(bytes, bytes.length - maxBytes, maxBytes, java.nio.charset.StandardCharsets.UTF_8);
        int replacement = tail.indexOf('\uFFFD');
        while (replacement == 0 && tail.length() > 1) {
            tail = tail.substring(1);
            replacement = tail.indexOf('\uFFFD');
        }
        return tail;
    }
}
