package io.github.jdubois.bootui.autoconfigure;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.beans.factory.annotation.Autowired;

@AnalyzeClasses(packages = "io.github.jdubois.bootui.autoconfigure", importOptions = DoNotIncludeTests.class)
class BootUiArchitectureTests {

    @ArchTest
    static final ArchRule controllersWithMultipleConstructorsMustHaveAutowiredConstructor = classes()
            .that()
            .resideInAPackage("..web..")
            .and()
            .haveSimpleNameEndingWith("Controller")
            .and()
            .doNotHaveSimpleName("LiveMemoryController") // Exempt as it manages its own internal non-bean dependency
            .and()
            .doNotHaveSimpleName("JvmTuningController") // Exempt as it manages its own internal non-bean dependency
            .should(haveExactlyOneAutowiredConstructorIfMultipleConstructorsPresent());

    private static ArchCondition<JavaClass> haveExactlyOneAutowiredConstructorIfMultipleConstructorsPresent() {
        return new ArchCondition<>("have exactly one @Autowired constructor if multiple constructors are present") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (javaClass.getConstructors().size() > 1) {
                    long autowiredCount = javaClass.getConstructors().stream()
                            .filter(c -> c.isAnnotatedWith(Autowired.class))
                            .count();
                    boolean satisfied = autowiredCount == 1;
                    String message = String.format(
                            "Class %s has %d constructors but %d are annotated with @Autowired",
                            javaClass.getName(), javaClass.getConstructors().size(), autowiredCount);
                    events.add(new SimpleConditionEvent(javaClass, satisfied, message));
                }
            }
        };
    }
}
