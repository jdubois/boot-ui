package io.github.jdubois.bootui.engine.architecture;

import java.util.List;
import java.util.Set;

/**
 * Fixed, reviewable registry of the curated architecture rules. Adding a rule means adding one
 * focused class plus an entry here; the panel never derives rules from project-specific input.
 */
final class ArchitectureRuleRegistry {

    /**
     * Rule IDs that were removed from the catalogue. They stay reserved so existing dismissals never silently apply
     * to a different check; see the "Retired rule IDs" section of docs/ARCHITECTURE-CHECKS.md.
     */
    static final Set<String> RETIRED_RULE_IDS =
            Set.of("ARCH-CODE-005", "ARCH-CODE-011", "ARCH-SPRING-005", "ARCH-SPRING-016");

    private static final List<ArchitectureRule> ACTIVE_RULES = List.of(
            new FreeOfPackageCyclesRule(),
            new NoStandardStreamsRule(),
            new NoGenericExceptionsRule(),
            new NoJavaUtilLoggingRule(),
            new NoJodaTimeRule(),
            new NoSystemExitRule(),
            new NoJdkInternalApiRule(),
            new NoLegacyDateTimeRule(),
            new NoDeprecatedApiRule(),
            new NoFieldInjectionRule(),
            new FieldsShouldNotUseStandardInjectionAnnotationsRule(),
            new ControllersShouldNotDependOnRepositoriesRule(),
            new RepositoriesShouldNotDependOnControllersRule(),
            new RepositoriesShouldNotDependOnServicesRule(),
            new ServicesShouldNotDependOnControllersRule(),
            new NoSelfInvocationOfProxiedMethodsRule(),
            new ExceptionsShouldBeNamedExceptionRule(),
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
            new LiteModeBeanMethodsShouldNotCallSiblingBeanMethodsRule(),
            new LifecycleCallbacksShouldNotBeProxyDrivenRule(),
            new AsyncAndTransactionalShouldNotBeCombinedRule(),
            new AsyncEventListenersShouldReturnVoidRule(),
            new BeanPostProcessorFactoryMethodsShouldBeStaticRule(),
            new LegacyJavaxTransactionalShouldBeMigratedRule(),
            new InternalPackagesShouldNotBeAccessedExternallyRule(),
            new NoDirectThreadInstantiationRule(),
            new AssertionsShouldHaveDetailMessageRule(),
            new StaticInjectionPointsAreIgnoredRule(),
            new LegacyJavaxInjectionAnnotationsShouldBeMigratedRule());

    private ArchitectureRuleRegistry() {}

    static List<ArchitectureRule> activeRules() {
        return ACTIVE_RULES;
    }
}
