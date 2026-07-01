package io.github.jdubois.bootui.engine.archunit;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.engine.archunit.fixtures.BadlyNamedFailure;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ArchUnitClassImportsTests {

    private static final String FIXTURES = "io.github.jdubois.bootui.engine.archunit.fixtures";

    @Test
    void configuresOpenableLocationResolverDuringImport() {
        String[] activeDuringImport = new String[1];

        ArchUnitClassImports.importPackages(List.of(FIXTURES), packages -> {
            activeDuringImport[0] = ArchConfiguration.get().getClassResolver().orElse(null);
            return new ClassFileImporter().importPackages(packages);
        });

        assertThat(activeDuringImport[0]).isEqualTo(OpenableLocationClassResolver.class.getName());
    }

    @Test
    void doesNotLeakResolverConfigurationIntoGlobalStateAfterImport() {
        Optional<String> resolverBefore = ArchConfiguration.get().getClassResolver();
        List<String> argumentsBefore = ArchConfiguration.get().getClassResolverArguments();

        ArchUnitClassImports.importPackages(List.of(FIXTURES));

        assertThat(ArchConfiguration.get().getClassResolver()).isEqualTo(resolverBefore);
        assertThat(ArchConfiguration.get().getClassResolverArguments()).isEqualTo(argumentsBefore);
    }

    @Test
    void doesNotLeakResolverConfigurationIntoGlobalStateWhenImportThrows() {
        Optional<String> resolverBefore = ArchConfiguration.get().getClassResolver();
        RuntimeException boom = new RuntimeException("import failed");

        try {
            ArchUnitClassImports.importPackages(List.of(FIXTURES), packages -> {
                throw boom;
            });
        } catch (RuntimeException thrown) {
            assertThat(thrown).isSameAs(boom);
        }

        assertThat(ArchConfiguration.get().getClassResolver()).isEqualTo(resolverBefore);
    }

    @Test
    void preservesClasspathDependencyResolution() {
        // Importing only the fixtures package still resolves the JDK super-class chain of BadlyNamedFailure
        // (RuntimeException -> Exception) from the classpath. If classpath resolution were disabled the
        // chain would stop at an unresolved stub and isAssignableTo(Exception) would be false, silently
        // changing the curated ARCH-CODE-010 finding. This is the regression guard for that.
        JavaClasses classes = ArchUnitClassImports.importPackages(List.of(FIXTURES));

        JavaClass badlyNamedFailure = classes.get(BadlyNamedFailure.class);
        assertThat(badlyNamedFailure.isAssignableTo(Exception.class)).isTrue();
        assertThat(badlyNamedFailure.isAssignableTo(RuntimeException.class)).isTrue();
    }
}
