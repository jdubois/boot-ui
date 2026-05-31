package io.github.jdubois.bootui.autoconfigure.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;

/**
 * Imports the host application classes that the architecture rules analyse. Abstracted behind an
 * interface so the scanner can be exercised in tests with a deterministic set of classes.
 */
interface ArchitectureClassImporter {

    JavaClasses importPackages(List<String> basePackages);
}

/**
 * Default importer that reads compiled classes from the classpath, bounded to the detected base
 * packages so only the application's own code is analysed.
 */
final class ClassFileArchitectureImporter implements ArchitectureClassImporter {

    @Override
    public JavaClasses importPackages(List<String> basePackages) {
        return new ClassFileImporter().importPackages(basePackages);
    }
}
