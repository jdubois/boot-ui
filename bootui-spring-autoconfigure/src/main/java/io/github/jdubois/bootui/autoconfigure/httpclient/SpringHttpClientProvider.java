package io.github.jdubois.bootui.autoconfigure.httpclient;

import io.github.jdubois.bootui.spi.DiscoveredHttpClient;
import io.github.jdubois.bootui.spi.DiscoveredHttpClientSetting;
import io.github.jdubois.bootui.spi.HttpClientProvider;
import io.github.jdubois.bootui.spi.HttpClientVocabulary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * Discovers the declarative HTTP clients a Spring application registers, from bean definitions only.
 *
 * <p>Everything here is read from metadata that is already in the bean factory: bean definition attributes
 * for Spring HTTP Interface groups, annotation metadata or factory-bean property values for OpenFeign, and
 * type lookups for {@code RestClient.Builder} / {@code WebClient.Builder}. No bean is instantiated, no
 * builder is customized, and no request is sent — {@code getBeanNamesForType} is always called with eager
 * initialization disabled, and HTTP Interface proxies are identified through their bean definition rather
 * than by resolving the proxy.</p>
 *
 * <p>OpenFeign is referenced only by name, so this class stays loadable when Spring Cloud OpenFeign is
 * absent, which is the normal case.</p>
 */
public class SpringHttpClientProvider implements HttpClientProvider {

    private static final Logger log = LoggerFactory.getLogger(SpringHttpClientProvider.class);

    /** Set by {@code AbstractHttpServiceRegistrar} on each generated HTTP Interface client definition. */
    private static final String HTTP_SERVICE_GROUP_NAME_ATTRIBUTE = "httpServiceGroupName";

    private static final String FEIGN_CLIENT_ANNOTATION = "org.springframework.cloud.openfeign.FeignClient";
    private static final String FEIGN_FACTORY_BEAN = "org.springframework.cloud.openfeign.FeignClientFactoryBean";

    private static final String REST_CLIENT_BUILDER = "org.springframework.web.client.RestClient$Builder";
    private static final String WEB_CLIENT_BUILDER =
            "org.springframework.web.reactive.function.client.WebClient$Builder";

    private static final String SERVICE_CLIENT_PREFIX = "spring.http.serviceclient";
    private static final String CLIENTS_PREFIX = "spring.http.clients";
    private static final String LEGACY_IMPERATIVE_PREFIX = "spring.http.client";
    private static final String LEGACY_REACTIVE_PREFIX = "spring.http.reactiveclient";
    private static final String FEIGN_CONFIG_PREFIX = "spring.cloud.openfeign.client.config";

    private static final String REASON_NONE_FOUND =
            "No declarative HTTP client found. Declare a Spring HTTP Interface with @HttpExchange and register it with "
                    + "@ImportHttpServices, add an OpenFeign client, or expose a RestClient.Builder or "
                    + "WebClient.Builder bean.";

    private final ConfigurableListableBeanFactory beanFactory;
    private final PropertyLookup properties;

    public SpringHttpClientProvider(ConfigurableListableBeanFactory beanFactory, Environment environment) {
        this.beanFactory = beanFactory;
        this.properties = new PropertyLookup(environment);
    }

    @Override
    public boolean available() {
        return !clients().isEmpty();
    }

    @Override
    public String unavailableReason() {
        return REASON_NONE_FOUND;
    }

    @Override
    public List<DiscoveredHttpClient> clients() {
        if (beanFactory == null) {
            return List.of();
        }
        List<DiscoveredHttpClient> clients = new ArrayList<>();
        Set<String> claimed = new LinkedHashSet<>();
        try {
            // A group registers one bean definition per declared interface, so the group is collected first
            // and reported once: emitting a card per interface would duplicate the same client and then make
            // the duplicates look like two clients competing for one host.
            Map<String, List<String>> groups = new LinkedHashMap<>();
            for (String beanName : beanFactory.getBeanDefinitionNames()) {
                BeanDefinition definition = beanDefinition(beanName);
                if (definition == null) {
                    continue;
                }
                if (collectHttpInterface(beanName, definition, groups)) {
                    claimed.add(beanName);
                    continue;
                }
                DiscoveredHttpClient client = feignClient(beanName, definition);
                if (client != null) {
                    clients.add(client);
                    claimed.add(beanName);
                }
            }
            groups.forEach((groupName, interfaces) -> clients.add(httpInterfaceClient(groupName, interfaces)));
            clients.addAll(builderClients(claimed));
        } catch (RuntimeException ex) {
            // A partial registry is far more useful than a broken panel, and the engine still reports the
            // panel unavailable when nothing at all was discovered.
            log.debug("BootUI could not fully enumerate declarative HTTP clients", ex);
        }
        return List.copyOf(clients);
    }

    // ---------------------------------------------------------------- Spring HTTP Interface

    /**
     * Records the group a bean definition belongs to, and returns whether it was an HTTP Interface client at
     * all. Nothing is instantiated: only the attribute the registrar left on the definition is read.
     */
    private boolean collectHttpInterface(String beanName, BeanDefinition definition, Map<String, List<String>> groups) {
        Object group = definition.getAttribute(HTTP_SERVICE_GROUP_NAME_ATTRIBUTE);
        if (group == null) {
            return false;
        }
        String declaredInterface = definition.getBeanClassName();
        if (declaredInterface == null) {
            // The bean name is "<group>#<fully qualified interface>" when the class name is not set.
            int separator = beanName.indexOf('#');
            declaredInterface = separator < 0 ? null : beanName.substring(separator + 1);
        }
        List<String> interfaces = groups.computeIfAbsent(String.valueOf(group), key -> new ArrayList<>());
        if (declaredInterface != null && !interfaces.contains(declaredInterface)) {
            interfaces.add(declaredInterface);
        }
        return true;
    }

    private DiscoveredHttpClient httpInterfaceClient(String groupName, List<String> interfaces) {
        PropertyLookup.Value baseUrl = properties.find(SERVICE_CLIENT_PREFIX, groupName, "base-url");
        List<DiscoveredHttpClientSetting> settings = new ArrayList<>();
        settings.add(clientOrGlobal(
                HttpClientVocabulary.CATEGORY_TIMEOUT, "Connect timeout", groupName, "connect-timeout", true));
        settings.add(
                clientOrGlobal(HttpClientVocabulary.CATEGORY_TIMEOUT, "Read timeout", groupName, "read-timeout", true));
        settings.add(
                clientOrGlobal(HttpClientVocabulary.CATEGORY_REDIRECT, "Redirects", groupName, "redirects", false));
        settings.add(clientOrGlobal(HttpClientVocabulary.CATEGORY_TLS, "SSL bundle", groupName, "ssl.bundle", false));
        settings.add(clientOrGlobal(
                HttpClientVocabulary.CATEGORY_TRANSPORT, "Cookie handling", groupName, "cookie-handling", false));
        if (interfaces.size() > 1) {
            settings.add(new DiscoveredHttpClientSetting(
                    HttpClientVocabulary.CATEGORY_TRANSPORT,
                    "HTTP service interfaces",
                    String.join(", ", interfaces),
                    HttpClientVocabulary.PROVENANCE_ANNOTATION,
                    "@ImportHttpServices(group = \"" + groupName + "\")"));
        }
        settings.addAll(transportImplementations());

        return new DiscoveredHttpClient(
                groupName,
                HttpClientVocabulary.KIND_HTTP_INTERFACE,
                interfaces.size() == 1 ? interfaces.get(0) : null,
                groupName,
                baseUrl == null ? null : baseUrl.raw(),
                baseUrl == null ? null : baseUrl.resolved(),
                baseUrl == null ? null : HttpClientVocabulary.PROVENANCE_CLIENT,
                baseUrl == null ? null : baseUrl.key(),
                settings);
    }

    /**
     * Resolves one setting through Spring Boot's own precedence: the per-client group first, then the
     * shared {@code spring.http.clients} block, then the legacy imperative/reactive block. The provenance
     * that comes back is the honest one for wherever the value was actually found.
     *
     * <p>A group's bean definitions carry no signal about whether Spring Boot will back it with the
     * imperative or the reactive client, so the legacy fallback reads the imperative block first rather than
     * guessing a stack. The reported configuration key always says which one answered.</p>
     */
    private DiscoveredHttpClientSetting clientOrGlobal(
            String category, String label, String groupName, String suffix, boolean duration) {
        PropertyLookup.Value value = properties.find(SERVICE_CLIENT_PREFIX, groupName, suffix);
        String provenance = HttpClientVocabulary.PROVENANCE_CLIENT;
        if (value == null) {
            value = properties.get(CLIENTS_PREFIX + "." + suffix);
            provenance = HttpClientVocabulary.PROVENANCE_APPLICATION;
        }
        if (value == null) {
            value = properties.get(LEGACY_IMPERATIVE_PREFIX + "." + suffix);
            provenance = HttpClientVocabulary.PROVENANCE_APPLICATION;
        }
        if (value == null) {
            value = properties.get(LEGACY_REACTIVE_PREFIX + "." + suffix);
            provenance = HttpClientVocabulary.PROVENANCE_APPLICATION;
        }
        if (value == null) {
            return DiscoveredHttpClientSetting.unavailable(category, label);
        }
        String display = duration ? PropertyLookup.describeDuration(value.resolved()) : value.resolved();
        return new DiscoveredHttpClientSetting(category, label, display, provenance, value.key());
    }

    /** The first of several equivalent keys that is set, so a deprecated spelling is still reported. */
    private PropertyLookup.Value firstOf(String... keys) {
        for (String key : keys) {
            PropertyLookup.Value value = properties.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * The configured client library, when the application selected one. An HTTP Interface group can be
     * backed by either the imperative or the reactive stack, so both selections are reported when the
     * application set them, and the pair collapses to one explicitly unavailable entry when it set neither.
     * BootUI does not guess: Spring Boot's automatic selection depends on the runtime classpath, which is
     * not a value the framework has committed to in configuration.
     */
    private List<DiscoveredHttpClientSetting> transportImplementations() {
        List<DiscoveredHttpClientSetting> settings = new ArrayList<>();
        PropertyLookup.Value factory =
                firstOf(CLIENTS_PREFIX + ".imperative.factory", LEGACY_IMPERATIVE_PREFIX + ".factory");
        if (factory != null) {
            settings.add(new DiscoveredHttpClientSetting(
                    HttpClientVocabulary.CATEGORY_TRANSPORT,
                    "Client factory",
                    factory.resolved(),
                    HttpClientVocabulary.PROVENANCE_APPLICATION,
                    factory.key()));
        }
        PropertyLookup.Value connector =
                firstOf(CLIENTS_PREFIX + ".reactive.connector", LEGACY_REACTIVE_PREFIX + ".connector");
        if (connector != null) {
            settings.add(new DiscoveredHttpClientSetting(
                    HttpClientVocabulary.CATEGORY_TRANSPORT,
                    "Client connector",
                    connector.resolved(),
                    HttpClientVocabulary.PROVENANCE_APPLICATION,
                    connector.key()));
        }
        if (settings.isEmpty()) {
            settings.add(DiscoveredHttpClientSetting.unavailable(
                    HttpClientVocabulary.CATEGORY_TRANSPORT, "Client factory or connector"));
        }
        return settings;
    }

    // ---------------------------------------------------------------- OpenFeign

    private DiscoveredHttpClient feignClient(String beanName, BeanDefinition definition) {
        Map<String, Object> attributes = feignAttributes(definition);
        if (attributes == null) {
            return null;
        }
        String name = text(attributes.get("contextId"));
        if (name == null) {
            name = text(attributes.get("name"));
        }
        if (name == null) {
            name = text(attributes.get("value"));
        }
        if (name == null) {
            name = beanName;
        }
        String declaredInterface = text(attributes.get("type"));
        if (declaredInterface == null) {
            declaredInterface = definition instanceof AnnotatedBeanDefinition annotated
                    ? annotated.getMetadata().getClassName()
                    : null;
        }

        String rawUrl = text(attributes.get("url"));
        String resolvedUrl = rawUrl == null ? null : properties.resolveText(rawUrl);
        String path = text(attributes.get("path"));
        // Spring Cloud lets `spring.cloud.openfeign.client.config.<name>.url` override the annotation, so the
        // property has to win here too, or the panel would report a URL the application does not actually use.
        PropertyLookup.Value configuredUrl = properties.find(FEIGN_CONFIG_PREFIX, name, "url");
        if (configuredUrl != null) {
            rawUrl = configuredUrl.raw();
            resolvedUrl = configuredUrl.resolved();
        }

        List<DiscoveredHttpClientSetting> settings = new ArrayList<>();
        settings.add(
                feignSetting(HttpClientVocabulary.CATEGORY_TIMEOUT, "Connect timeout", name, "connectTimeout", true));
        settings.add(feignSetting(HttpClientVocabulary.CATEGORY_TIMEOUT, "Read timeout", name, "readTimeout", true));
        settings.add(feignSetting(
                HttpClientVocabulary.CATEGORY_REDIRECT, "Follow redirects", name, "followRedirects", false));
        settings.add(feignSetting(HttpClientVocabulary.CATEGORY_RETRY, "Retryer", name, "retryer", false));
        settings.add(globalSetting(
                HttpClientVocabulary.CATEGORY_CONNECTION_POOL,
                "Max connections",
                "spring.cloud.openfeign.httpclient.max-connections"));
        settings.add(globalSetting(
                HttpClientVocabulary.CATEGORY_CONNECTION_POOL,
                "Max connections per route",
                "spring.cloud.openfeign.httpclient.max-connections-per-route"));
        if (path != null) {
            settings.add(new DiscoveredHttpClientSetting(
                    HttpClientVocabulary.CATEGORY_TRANSPORT,
                    "Path prefix",
                    path,
                    HttpClientVocabulary.PROVENANCE_ANNOTATION,
                    "@FeignClient(path)"));
        }

        String provenance = configuredUrl != null
                ? HttpClientVocabulary.PROVENANCE_CLIENT
                : (rawUrl == null ? null : HttpClientVocabulary.PROVENANCE_ANNOTATION);
        String source = configuredUrl != null ? configuredUrl.key() : (rawUrl == null ? null : "@FeignClient(url)");
        return new DiscoveredHttpClient(
                name,
                HttpClientVocabulary.KIND_OPEN_FEIGN,
                declaredInterface,
                name,
                rawUrl,
                resolvedUrl,
                provenance,
                source,
                settings);
    }

    /**
     * OpenFeign registrations are read without loading a Spring Cloud class: either from the annotation
     * metadata already parsed by the scanner, or from the property values the registrar set on the factory
     * bean definition.
     */
    private Map<String, Object> feignAttributes(BeanDefinition definition) {
        if (definition instanceof AnnotatedBeanDefinition annotated) {
            try {
                Map<String, Object> attributes =
                        annotated.getMetadata().getAnnotationAttributes(FEIGN_CLIENT_ANNOTATION);
                if (attributes != null) {
                    return attributes;
                }
            } catch (RuntimeException ex) {
                log.trace("BootUI could not read @FeignClient metadata", ex);
            }
        }
        if (!FEIGN_FACTORY_BEAN.equals(definition.getBeanClassName())) {
            return null;
        }
        Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        for (PropertyValue property : definition.getPropertyValues().getPropertyValues()) {
            attributes.put(property.getName(), property.getValue());
        }
        return attributes;
    }

    private DiscoveredHttpClientSetting feignSetting(
            String category, String label, String name, String suffix, boolean duration) {
        PropertyLookup.Value value = properties.find(FEIGN_CONFIG_PREFIX, name, suffix);
        String provenance = HttpClientVocabulary.PROVENANCE_CLIENT;
        if (value == null) {
            value = properties.find(FEIGN_CONFIG_PREFIX, "default", suffix);
            provenance = HttpClientVocabulary.PROVENANCE_APPLICATION;
        }
        if (value == null) {
            return DiscoveredHttpClientSetting.unavailable(category, label);
        }
        // Feign expresses timeouts as bare milliseconds; the panel renders every adapter's timeout the same.
        String display = duration ? PropertyLookup.describeDuration(value.resolved()) : value.resolved();
        return new DiscoveredHttpClientSetting(category, label, display, provenance, value.key());
    }

    private DiscoveredHttpClientSetting globalSetting(String category, String label, String key) {
        PropertyLookup.Value value = properties.get(key);
        if (value == null) {
            return DiscoveredHttpClientSetting.unavailable(category, label);
        }
        return new DiscoveredHttpClientSetting(
                category, label, value.resolved(), HttpClientVocabulary.PROVENANCE_APPLICATION, value.key());
    }

    // ---------------------------------------------------------------- Builder beans

    private List<DiscoveredHttpClient> builderClients(Set<String> claimed) {
        List<DiscoveredHttpClient> clients = new ArrayList<>();
        clients.addAll(
                buildersOfType(REST_CLIENT_BUILDER, HttpClientVocabulary.KIND_REST_CLIENT_BUILDER, claimed, false));
        clients.addAll(buildersOfType(WEB_CLIENT_BUILDER, HttpClientVocabulary.KIND_WEB_CLIENT_BUILDER, claimed, true));
        return clients;
    }

    private List<DiscoveredHttpClient> buildersOfType(
            String typeName, String kind, Set<String> claimed, boolean reactiveBuilder) {
        ClassLoader classLoader = beanFactory.getBeanClassLoader();
        if (!ClassUtils.isPresent(typeName, classLoader)) {
            return List.of();
        }
        Class<?> type;
        try {
            type = ClassUtils.forName(typeName, classLoader);
        } catch (ClassNotFoundException | LinkageError ex) {
            return List.of();
        }
        List<DiscoveredHttpClient> clients = new ArrayList<>();
        // allowEagerInit = false: factory beans are never resolved, so a lazy client is never created.
        for (String beanName : beanFactory.getBeanNamesForType(type, true, false)) {
            if (claimed.contains(beanName)) {
                continue;
            }
            BeanDefinition definition = beanDefinition(beanName);
            boolean frameworkOwned = definition == null || definition.getRole() != BeanDefinition.ROLE_APPLICATION;
            List<DiscoveredHttpClientSetting> settings = new ArrayList<>();
            settings.add(builderGlobal(
                    HttpClientVocabulary.CATEGORY_TIMEOUT, "Connect timeout", "connect-timeout", reactiveBuilder));
            settings.add(builderGlobal(
                    HttpClientVocabulary.CATEGORY_TIMEOUT, "Read timeout", "read-timeout", reactiveBuilder));
            settings.add(
                    builderGlobal(HttpClientVocabulary.CATEGORY_REDIRECT, "Redirects", "redirects", reactiveBuilder));
            settings.add(builderGlobal(HttpClientVocabulary.CATEGORY_TLS, "SSL bundle", "ssl.bundle", reactiveBuilder));
            settings.add(builderTransport(reactiveBuilder));
            settings.add(new DiscoveredHttpClientSetting(
                    HttpClientVocabulary.CATEGORY_TRANSPORT,
                    "Ownership",
                    frameworkOwned ? "Framework-managed builder" : "Application-defined builder",
                    frameworkOwned
                            ? HttpClientVocabulary.PROVENANCE_FRAMEWORK
                            : HttpClientVocabulary.PROVENANCE_APPLICATION,
                    null));
            // A builder carries no declared target: whatever base URL it is given is applied by the code
            // that consumes it, so reporting one here would be a guess.
            clients.add(new DiscoveredHttpClient(
                    beanName, kind, typeName.replace('$', '.'), null, null, null, null, null, settings));
        }
        return clients;
    }

    private DiscoveredHttpClientSetting builderGlobal(
            String category, String label, String suffix, boolean reactiveBuilder) {
        PropertyLookup.Value value = properties.get(CLIENTS_PREFIX + "." + suffix);
        if (value == null) {
            value = properties.get(
                    (reactiveBuilder ? LEGACY_REACTIVE_PREFIX : LEGACY_IMPERATIVE_PREFIX) + "." + suffix);
        }
        if (value == null) {
            return DiscoveredHttpClientSetting.unavailable(category, label);
        }
        return new DiscoveredHttpClientSetting(
                category, label, value.resolved(), HttpClientVocabulary.PROVENANCE_APPLICATION, value.key());
    }

    private DiscoveredHttpClientSetting builderTransport(boolean reactiveBuilder) {
        String key = reactiveBuilder ? CLIENTS_PREFIX + ".reactive.connector" : CLIENTS_PREFIX + ".imperative.factory";
        PropertyLookup.Value value = properties.get(key);
        String label = reactiveBuilder ? "Client connector" : "Client factory";
        if (value == null) {
            return DiscoveredHttpClientSetting.unavailable(HttpClientVocabulary.CATEGORY_TRANSPORT, label);
        }
        return new DiscoveredHttpClientSetting(
                HttpClientVocabulary.CATEGORY_TRANSPORT,
                label,
                value.resolved(),
                HttpClientVocabulary.PROVENANCE_APPLICATION,
                value.key());
    }

    // ---------------------------------------------------------------- helpers

    private BeanDefinition beanDefinition(String beanName) {
        try {
            return beanFactory.getBeanDefinition(beanName);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Class<?> type) {
            return type.getName();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
