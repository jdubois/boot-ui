package io.github.jdubois.bootui.engine.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Observed class-to-class calls mapped to the beans of those classes (M5-4c). */
class BeanInvocationsTests {

    /**
     * I4: a method's class is the class that declares it, so a call into an inherited method names the superclass; it
     * maps to the bean that extends it, and among several to the one the caller declares a dependency on.
     */
    @Test
    void anInheritedMethodMapsToTheBeanThatExtendsItsClass() {
        List<StructureSnapshot.Bean> beans = List.of(
                bean("orderController", "shop.OrderController", "specialPricing"),
                bean("reportJob", "shop.ReportJob"),
                bean("basePricing", "shop.BasePricing"),
                bean("specialPricing", "shop.SpecialPricing$$SpringCGLIB$$0"),
                bean("auditService", "shop.AuditService"));
        Map<String, List<String>> hierarchy = Map.of(
                "shop.SpecialPricing", List.of("shop.BasePricing", "shop.AbstractPricing"),
                "shop.AuditService", List.of("shop.AbstractAudit"));

        BeanInvocations.Resolved resolved = BeanInvocations.resolve(
                beans,
                List.of(
                        // The controller declares the special pricing: its call into a method BasePricing declares is
                        // the special pricing's, not the base bean's.
                        new ClassInvocation("shop.OrderController", "shop.BasePricing", 4),
                        // A method only an abstract superclass declares maps to its one bean.
                        new ClassInvocation("shop.OrderController", "shop.AbstractAudit", 2),
                        // Ambiguous without a declared dependency: the exact type wins.
                        new ClassInvocation("shop.ReportJob", "shop.BasePricing", 1),
                        // A class of no bean maps to none.
                        new ClassInvocation("shop.OrderController", "shop.Unknown", 3)),
                type -> hierarchy.getOrDefault(type, List.of()));

        assertThat(resolved.edges())
                .extracting(edge -> edge.from() + ">" + edge.to() + ":" + edge.calls())
                .containsExactly(
                        "orderController>specialPricing:4",
                        "orderController>auditService:2",
                        "reportJob>basePricing:1");
        assertThat(resolved.unmappedCalls()).isEqualTo(3);
    }

    @Test
    void superclassesAreReadWithoutInitializingAndNeverThrow() {
        assertThat(BeanInvocations.superclasses(java.util.ArrayList.class.getName()))
                .containsExactly(java.util.AbstractList.class.getName(), java.util.AbstractCollection.class.getName());
        assertThat(BeanInvocations.superclasses("shop.DoesNotExist")).isEmpty();
        assertThat(BeanInvocations.superclasses(null)).isEmpty();
    }

    private static StructureSnapshot.Bean bean(String name, String type, String... dependencies) {
        return new StructureSnapshot.Bean(name, type, false, List.of(dependencies));
    }
}
