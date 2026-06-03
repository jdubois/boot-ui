package io.github.jdubois.bootui.autoconfigure.graalvm;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;

/**
 * Imports the host application classes that the native-image readiness checks analyse. Abstracted
 * behind an interface so the scanner can be exercised in tests with a deterministic set of classes.
 */
interface GraalVmClassImporter {

    JavaClasses importPackages(List<String> basePackages);
}

/**
 * Default importer that reads compiled classes from the classpath, bounded to the detected base
 * packages so only the application's own code is analysed.
 */
final class ClassFileGraalVmImporter implements GraalVmClassImporter {

    @Override
    public JavaClasses importPackages(List<String> basePackages) {
        return new ClassFileImporter().importPackages(basePackages);
    }
}
