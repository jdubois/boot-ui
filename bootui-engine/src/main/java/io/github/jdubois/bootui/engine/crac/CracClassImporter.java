package io.github.jdubois.bootui.engine.crac;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;

/**
 * Imports the host application classes that the CRaC readiness checks analyse. Abstracted behind an
 * interface so the scanner can be exercised in tests with a deterministic set of classes.
 */
interface CracClassImporter {

    JavaClasses importPackages(List<String> basePackages);
}

/**
 * Default importer that reads compiled classes from the classpath, bounded to the detected base
 * packages so only the application's own code is analysed.
 */
final class ClassFileCracImporter implements CracClassImporter {

    @Override
    public JavaClasses importPackages(List<String> basePackages) {
        return new ClassFileImporter().importPackages(basePackages);
    }
}
