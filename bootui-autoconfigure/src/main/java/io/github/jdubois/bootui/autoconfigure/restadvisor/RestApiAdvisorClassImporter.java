package io.github.jdubois.bootui.autoconfigure.restadvisor;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;

/**
 * Imports the host application classes that the REST API Advisor analyses. Abstracted behind an
 * interface so the scanner can be exercised in tests with a deterministic set of classes.
 */
interface RestApiAdvisorClassImporter {

    JavaClasses importPackages(List<String> basePackages);
}

/**
 * Default importer that reads compiled classes from the classpath, bounded to the detected base
 * packages so only the application's own controllers are analysed.
 */
final class ClassFileRestApiAdvisorImporter implements RestApiAdvisorClassImporter {

    @Override
    public JavaClasses importPackages(List<String> basePackages) {
        return new ClassFileImporter().importPackages(basePackages);
    }
}
