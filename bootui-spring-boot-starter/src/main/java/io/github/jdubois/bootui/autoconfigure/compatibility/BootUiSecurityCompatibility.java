package io.github.jdubois.bootui.autoconfigure.compatibility;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.springframework.aot.AotDetector;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.NativeDetector;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Registration-time checks of the security chains, never a fallback to weaker security. */
public final class BootUiSecurityCompatibility {

    private BootUiSecurityCompatibility() {}

    public static final class Servlet implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            if (!generatedRuntime()) {
                requireMethod(
                        context.getClassLoader(),
                        "org.springframework.security.config.annotation.web.configurers.CsrfConfigurer",
                        "spa",
                        false);
            }
            return true;
        }
    }

    public static final class Reactive implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            if (!generatedRuntime()) {
                ClassLoader loader = context.getClassLoader();
                requireMethod(
                        loader,
                        "org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository",
                        "withHttpOnlyFalse",
                        true);
                requireMethod(
                        loader,
                        "org.springframework.security.config.web.server.ServerHttpSecurity$CsrfSpec",
                        "csrfTokenRequestHandler",
                        false,
                        "org.springframework.security.web.server.csrf.ServerCsrfTokenRequestHandler");
                requireMethod(
                        loader,
                        "org.springframework.security.config.web.server.ServerHttpSecurity$CsrfSpec",
                        "requireCsrfProtectionMatcher",
                        false,
                        "org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher");
            }
            return true;
        }
    }

    static void requireMethod(ClassLoader loader, String owner, String name, boolean isStatic, String... parameters) {
        try {
            Class<?> type = Class.forName(owner, false, loader);
            Class<?>[] arguments = new Class<?>[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                arguments[i] = Class.forName(parameters[i], false, loader);
            }
            MethodType signature = MethodType.methodType(type, arguments);
            if (isStatic) {
                MethodHandles.publicLookup().findStatic(type, name, signature);
            } else {
                MethodHandles.publicLookup().findVirtual(type, name, signature);
            }
        } catch (ReflectiveOperationException | LinkageError ex) {
            throw new BootUiCompatibilityException(
                    "BOOTUI-COMPAT-021: BootUI cannot register its required Spring Security chain: " + owner + "."
                            + name + " is unavailable or cannot be linked.",
                    "Use the Spring Security libraries managed by your supported Spring Boot platform."
                            + " BootUI will not fall back to a weaker security chain.");
        }
    }

    private static boolean generatedRuntime() {
        // Conditions were evaluated while generating these bean definitions, with full library metadata.
        return NativeDetector.inNativeImage() || AotDetector.useGeneratedArtifacts();
    }
}
