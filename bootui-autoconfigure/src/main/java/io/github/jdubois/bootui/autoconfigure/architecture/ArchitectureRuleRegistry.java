package io.github.jdubois.bootui.autoconfigure.architecture;

import java.util.List;

/**
 * Fixed, reviewable registry of the curated architecture rules. Adding a rule means adding one
 * focused class plus an entry here; the panel never derives rules from project-specific input.
 */
final class ArchitectureRuleRegistry {

    private static final List<ArchitectureRule> ACTIVE_RULES = List.of(
            new FreeOfPackageCyclesRule(),
            new NoStandardStreamsRule(),
            new NoGenericExceptionsRule(),
            new NoJavaUtilLoggingRule(),
            new NoJodaTimeRule(),
            new NoPrintStackTraceRule(),
            new NoSystemExitRule(),
            new NoJdkInternalApiRule(),
            new NoLegacyDateTimeRule(),
            new NoDeprecatedApiRule(),
            new NoFieldInjectionRule(),
            new ControllersShouldNotDependOnRepositoriesRule(),
            new RepositoriesShouldNotDependOnControllersRule(),
            new RepositoriesShouldNotDependOnServicesRule(),
            new ServicesShouldNotDependOnControllersRule(),
            new NoSelfInvocationOfProxiedMethodsRule(),
            new StereotypesShouldNotResideInDefaultPackageRule(),
            new ExceptionsShouldBeNamedExceptionRule(),
            new InterfacesShouldNotHaveInterfaceSuffixRule(),
            new LoggersShouldBePrivateStaticFinalRule(),
            new NoTestFrameworkDependenciesRule(),
            new ServicesAndRepositoriesShouldNotDependOnServletTypesRule(),
            new TransactionalAnnotationsShouldNotBeDeclaredOnInterfacesRule(),
            new ProxiedMethodsShouldNotBePrivateOrStaticRule(),
            new AsyncMethodsShouldHaveSupportedSignaturesRule(),
            new ScheduledMethodsShouldHaveSupportedSignaturesRule(),
            new AsyncShouldNotBeUsedInConfigurationClassesRule(),
            new NoAopContextCurrentProxyRule(),
            new NoPublicMutableStaticFieldsRule(),
            new UtilityClassesShouldBeFinalWithPrivateConstructorRule(),
            new ConfigurationPropertiesShouldBeImmutableRule(),
            new LayeredArchitectureDirectionRule());

    private ArchitectureRuleRegistry() {}

    static List<ArchitectureRule> activeRules() {
        return ACTIVE_RULES;
    }
}
