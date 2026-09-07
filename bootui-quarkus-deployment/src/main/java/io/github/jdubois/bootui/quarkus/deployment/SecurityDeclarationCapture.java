package io.github.jdubois.bootui.quarkus.deployment;

import io.github.jdubois.bootui.spi.QuarkusSecurityEndpoint;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.security.spi.AdditionalSecuredMethodsBuildItem;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;

/** Bounded direct REST-server declarations. Inheritance, transformations and programmatic policies are not interpreted. */
final class SecurityDeclarationCapture {
    private static final int LIMIT = 4096;
    private static final DotName PATH = DotName.createSimple("jakarta.ws.rs.Path");
    private static final DotName PRODUCES = DotName.createSimple("jakarta.ws.rs.Produces");
    private static final DotName HTTP_METHOD = DotName.createSimple("jakarta.ws.rs.HttpMethod");
    private static final DotName REST_CLIENT =
            DotName.createSimple("org.eclipse.microprofile.rest.client.inject.RegisterRestClient");
    private static final List<String> SECURITY = List.of(
            "jakarta.annotation.security.RolesAllowed", "jakarta.annotation.security.PermitAll",
            "jakarta.annotation.security.DenyAll", "io.quarkus.security.Authenticated",
            "io.quarkus.security.PermissionsAllowed", "io.quarkus.vertx.http.security.AuthorizationPolicy");

    record Capture(
            List<QuarkusSecurityEndpoint> endpoints,
            boolean incomplete,
            boolean customAuthorization,
            boolean customIdentity,
            boolean grpcServices,
            boolean customTls,
            boolean customHeaders) {}

    record Metadata(Set<MethodInfo> unknownMethods, boolean incomplete) {
        Metadata {
            unknownMethods = Set.copyOf(unknownMethods);
        }
    }

    static Metadata materializedMetadata(
            IndexView application,
            Iterable<BeanInfo> beans,
            List<AdditionalSecuredMethodsBuildItem> additionalSecurity) {
        Set<MethodInfo> unknown = new LinkedHashSet<>();
        int visited = 0;
        int methods = 0;
        for (BeanInfo bean : beans) {
            if (++visited > LIMIT) {
                return new Metadata(unknown, true);
            }
            ClassInfo raw = application.getClassByName(bean.getBeanClass());
            if (!bean.isClassBean() || raw == null) {
                continue;
            }
            // This accessor copies bindings materialized during Arc initialization; it does not query the lazy
            // annotation store.
            var bindings = bean.getInterceptedMethodsBindings();
            if (bindings.size() > LIMIT) {
                return new Metadata(unknown, true);
            }
            for (MethodInfo method : raw.methods()) {
                if (++methods > LIMIT) {
                    return new Metadata(unknown, true);
                }
                var declared = rawBindings(method);
                var actual = bindings.getOrDefault(method, Set.of()).stream()
                        .filter(annotation ->
                                SECURITY.contains(annotation.name().toString()))
                        .toList();
                if (declared.size() != actual.size()
                        || declared.stream()
                                .anyMatch(annotation -> actual.stream().noneMatch(annotation::equivalentTo))) {
                    unknown.add(method);
                }
            }
        }
        for (AdditionalSecuredMethodsBuildItem declaration : additionalSecurity) {
            if (++visited > LIMIT) {
                return new Metadata(unknown, true);
            }
            for (MethodInfo method : declaration.additionalSecuredMethods) {
                if (++methods > LIMIT) {
                    return new Metadata(unknown, true);
                }
                if (application.getClassByName(method.declaringClass().name()) != null) {
                    unknown.add(method);
                }
            }
        }
        return new Metadata(unknown, false);
    }

    static Capture capture(IndexView application, IndexView annotations) {
        return capture(application, annotations, new Metadata(Set.of(), false));
    }

    static Capture capture(IndexView application, IndexView annotations, Metadata metadata) {
        List<QuarkusSecurityEndpoint> endpoints = new ArrayList<>();
        boolean incomplete = metadata.incomplete()
                || annotations.getAnnotations(DotName.createSimple("jakarta.ws.rs.ApplicationPath")).stream()
                        .anyMatch(annotation -> annotation.value() == null
                                || !Set.of("", "/").contains(annotation.value().asString()));
        boolean grpc = false;
        boolean securityObserver = false;
        boolean identityProducer = false;
        boolean tlsProducer = false;
        int classes = 0;
        int methods = 0;
        outer:
        for (ClassInfo type : application.getKnownClasses()) {
            if (++classes > LIMIT) {
                incomplete = true;
                break;
            }
            grpc |= type.declaredAnnotation(DotName.createSimple("io.quarkus.grpc.GrpcService")) != null;
            for (MethodInfo method : type.methods()) {
                if (++methods > LIMIT) {
                    incomplete = true;
                    break outer;
                }
                for (var parameter : method.parameterTypes()) {
                    securityObserver |= "io.quarkus.vertx.http.security.HttpSecurity"
                            .equals(parameter.name().toString());
                }
                if (method.declaredAnnotation(DotName.createSimple("jakarta.enterprise.inject.Produces")) != null) {
                    identityProducer |=
                            method.returnType().name().toString().equals("io.quarkus.oidc.OidcTenantConfig");
                    tlsProducer |= method.returnType().name().toString().startsWith("io.quarkus.tls.");
                }
            }
            if (type.declaredAnnotation(REST_CLIENT) != null
                    || type.isInterface()
                    || Modifier.isAbstract(type.flags())) {
                continue;
            }
            AnnotationInstance root = type.declaredAnnotation(PATH);
            if (root == null) {
                List<DotName> parents = new ArrayList<>(type.interfaceNames());
                if (type.superName() != null) {
                    parents.add(type.superName());
                }
                for (DotName parent : parents) {
                    ClassInfo declaration = annotations.getClassByName(parent);
                    if (declaration != null && declaration.declaredAnnotation(PATH) != null) {
                        incomplete = true;
                    }
                }
                continue;
            }
            if (!type.interfaceNames().isEmpty()
                    || (type.superName() != null
                            && !"java.lang.Object".equals(type.superName().toString()))) {
                incomplete = true;
            }
            for (MethodInfo method : type.methods()) {
                if (endpoints.size() >= 256) {
                    incomplete = true;
                    break outer;
                }
                boolean endpointMethod = false;
                for (AnnotationInstance annotation : method.declaredAnnotations()) {
                    String verb = verb(annotations, annotation.name());
                    if (verb == null) {
                        continue;
                    }
                    endpointMethod = true;
                    String path = path(root) + "/" + path(method.declaredAnnotation(PATH));
                    path = ("/" + path).replaceAll("/+", "/");
                    if (path.length() > 1 && path.endsWith("/")) {
                        path = path.substring(0, path.length() - 1);
                    }
                    AnnotationInstance produces = method.declaredAnnotation(PRODUCES);
                    if (produces == null) {
                        produces = type.declaredAnnotation(PRODUCES);
                    }
                    boolean document = produces != null
                            && produces.value() != null
                            && java.util.Arrays.stream(produces.value().asStringArray())
                                    .anyMatch(value ->
                                            value.equals("text/html") || value.equals("application/xhtml+xml"));
                    boolean uncertain = metadata.unknownMethods().contains(method);
                    incomplete |= uncertain;
                    endpoints.add(new QuarkusSecurityEndpoint(
                            path, verb, uncertain ? QuarkusSecurityEndpoint.Access.UNKNOWN : access(method), document));
                }
                if (!endpointMethod && method.declaredAnnotation(PATH) != null) {
                    incomplete = true;
                }
            }
        }
        boolean customAuthorization = securityObserver
                || implementsAny(
                        application,
                        List.of(
                                "io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy",
                                "io.quarkus.vertx.http.security.HttpSecurity"));
        boolean customIdentity = identityProducer
                || implementsAny(
                        application,
                        List.of(
                                "io.quarkus.oidc.TenantConfigResolver",
                                "io.quarkus.oidc.TenantResolver",
                                "io.quarkus.oidc.TokenCustomizer",
                                "io.smallrye.jwt.auth.principal.JWTParser",
                                "io.smallrye.jwt.auth.principal.JWTCallerPrincipalFactory",
                                "io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism"));
        boolean customHeaders = implementsAny(application, List.of("jakarta.ws.rs.container.ContainerResponseFilter"));
        return new Capture(
                List.copyOf(endpoints),
                incomplete,
                customAuthorization,
                customIdentity,
                grpc,
                tlsProducer,
                customHeaders);
    }

    static QuarkusSecurityEndpoint.Access access(MethodInfo method) {
        List<String> annotations = securityAnnotations(method).stream()
                .map(annotation -> annotation.name().toString())
                .toList();
        if (annotations.isEmpty()) {
            return QuarkusSecurityEndpoint.Access.UNANNOTATED;
        }
        if (annotations.size() != 1 || annotations.get(0).endsWith(".AuthorizationPolicy")) {
            return QuarkusSecurityEndpoint.Access.UNKNOWN;
        }
        return annotations.get(0).endsWith(".PermitAll")
                ? QuarkusSecurityEndpoint.Access.PERMIT
                : QuarkusSecurityEndpoint.Access.RESTRICTED;
    }

    private static List<AnnotationInstance> securityAnnotations(MethodInfo method) {
        List<AnnotationInstance> annotations = method.declaredAnnotations().stream()
                .filter(annotation -> SECURITY.contains(annotation.name().toString()))
                .toList();
        return annotations.isEmpty()
                ? method.declaringClass().declaredAnnotations().stream()
                        .filter(annotation ->
                                SECURITY.contains(annotation.name().toString()))
                        .toList()
                : annotations;
    }

    private static List<AnnotationInstance> rawBindings(MethodInfo method) {
        // Arc materializes interceptor bindings, not Quarkus's effective SecurityCheck. Different class/method
        // binding types can coexist even when Quarkus's method-level authorization overrides the class declaration.
        var bindings = new java.util.LinkedHashMap<DotName, AnnotationInstance>();
        for (AnnotationInstance annotation : method.declaringClass().declaredAnnotations()) {
            if (SECURITY.contains(annotation.name().toString())) {
                bindings.put(annotation.name(), annotation);
            }
        }
        for (AnnotationInstance annotation : method.declaredAnnotations()) {
            if (SECURITY.contains(annotation.name().toString())) {
                bindings.put(annotation.name(), annotation);
            }
        }
        return List.copyOf(bindings.values());
    }

    private static boolean implementsAny(IndexView index, List<String> types) {
        for (String name : types) {
            DotName type = DotName.createSimple(name);
            if (!index.getAllKnownImplementors(type).isEmpty()
                    || !index.getAllKnownSubclasses(type).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static String verb(IndexView index, DotName name) {
        String value = name.toString();
        if (value.startsWith("jakarta.ws.rs.")) {
            String suffix = value.substring("jakarta.ws.rs.".length());
            if (Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
                    .contains(suffix)) {
                return suffix;
            }
        }
        ClassInfo type = index.getClassByName(name);
        AnnotationInstance method = type == null ? null : type.declaredAnnotation(HTTP_METHOD);
        return method == null || method.value() == null ? null : method.value().asString();
    }

    private static String path(AnnotationInstance annotation) {
        return annotation == null || annotation.value() == null
                ? ""
                : annotation.value().asString();
    }
}
