package io.github.jdubois.bootui.engine.archunit;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the Kotlin recognition the advisors rely on against real Kotlin bytecode (the fixtures in
 * {@code src/test/kotlin}). Everything is decided from names recorded in the bytecode, so a Java-only
 * application is unaffected and the engine keeps no Kotlin dependency.
 */
class KotlinBytecodeTests {

    private static final JavaClasses KOTLIN_FIXTURES =
            new ClassFileImporter().importPackages("io.github.jdubois.bootui.engine.architecture.kotlinfixtures");

    private JavaClass type(String simpleName) {
        return KOTLIN_FIXTURES.stream()
                .filter(candidate -> candidate.getSimpleName().equals(simpleName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No imported class named " + simpleName));
    }

    private JavaMethod method(JavaClass type, String name) {
        return type.getMethods().stream()
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No method named " + name));
    }

    @Test
    void recognizesKotlinClassesAndLeavesJavaClassesAlone() {
        assertThat(KotlinBytecode.isKotlinClass(type("KotlinOrderService"))).isTrue();
        assertThat(KotlinBytecode.isKotlinClass(new ClassFileImporter().importClass(KotlinBytecodeTests.class)))
                .isFalse();
    }

    @Test
    void recognizesCompilerGeneratedClasses() {
        assertThat(KotlinBytecode.isCompilerGenerated(type("KotlinArchitectureFixturesKt")))
                .isTrue();
        assertThat(KotlinBytecode.isCompilerGenerated(type("Companion"))).isTrue();
        assertThat(KotlinBytecode.isCompilerGenerated(type("KotlinOrder"))).isFalse();
    }

    @Test
    void dropsCompilerGeneratedMembers() {
        List<String> facadeMethods = KotlinBytecode.declaredMethods(type("KotlinArchitectureFixturesKt")).stream()
                .map(JavaMethod::getName)
                .toList();
        assertThat(facadeMethods).containsExactlyInAnyOrder("formatOrderId", "parseOrderId");

        List<String> dataClassMethods = KotlinBytecode.declaredMethods(type("KotlinOrder")).stream()
                .map(JavaMethod::getName)
                .toList();
        assertThat(dataClassMethods)
                .containsExactlyInAnyOrder("getId", "getCustomer", "toString", "hashCode", "equals");

        List<String> serviceMethods = KotlinBytecode.declaredMethods(type("KotlinOrderService")).stream()
                .map(JavaMethod::getName)
                .toList();
        assertThat(serviceMethods)
                .containsExactlyInAnyOrder(
                        "refreshOrders", "countOrders", "notifyCustomer", "loadOrder", "auditOrder", "audit");
    }

    @Test
    void readsTheDeclaredResultOfASuspendingFunction() {
        JavaClass service = type("KotlinOrderService");

        JavaMethod unitReturning = method(service, "refreshOrders");
        assertThat(KotlinBytecode.isSuspendFunction(unitReturning)).isTrue();
        assertThat(unitReturning.getRawReturnType().getName()).isEqualTo("java.lang.Object");
        assertThat(KotlinBytecode.suspendResultType(unitReturning)
                        .orElseThrow()
                        .toErasure()
                        .getName())
                .isEqualTo(KotlinBytecode.UNIT_TYPE);

        JavaMethod valueReturning = method(service, "countOrders");
        assertThat(KotlinBytecode.suspendResultType(valueReturning)
                        .orElseThrow()
                        .toErasure()
                        .getName())
                .isEqualTo("java.lang.Long");

        JavaMethod plain = method(service, "audit");
        assertThat(KotlinBytecode.isSuspendFunction(plain)).isFalse();
        assertThat(KotlinBytecode.suspendResultType(plain)).isEmpty();
    }

    @Test
    void dropsTheContinuationFromTheDeclaredParameters() {
        JavaClass service = type("KotlinOrderService");

        assertThat(KotlinBytecode.declaredParameters(method(service, "refreshOrders")))
                .isEmpty();
        assertThat(KotlinBytecode.declaredParameters(method(service, "loadOrder")))
                .hasSize(1);
        assertThat(KotlinBytecode.declaredParameters(method(service, "audit"))).hasSize(1);
    }
}
