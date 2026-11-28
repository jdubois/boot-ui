package bootui.packaged.threadfactory;

import io.github.jdubois.bootui.engine.architecture.ArchitecturePlatform;
import io.github.jdubois.bootui.engine.architecture.ArchitectureScanner;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ThreadFactory;

public class QuarkusThreadFactoryProbe {
    public static void main(String[] args) throws ClassNotFoundException {
        for (String name : new String[] {"org.hibernate.SessionFactory", "jakarta.persistence.EntityManagerFactory"}) {
            try {
                Class.forName(name, false, Thread.currentThread().getContextClassLoader());
            } catch (ClassNotFoundException expected) {
                continue;
            }
            throw new AssertionError("Optional ORM API unexpectedly installed: " + name);
        }
        System.out.println("OPTIONAL_ORM_APIS_ABSENT");
        Class.forName("com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassReader");
        var report = ArchitectureScanner.usingClasspath(
                        () -> List.of(QuarkusThreadFactoryProbe.class.getPackageName()),
                        ArchitecturePlatform.QUARKUS,
                        Clock.systemUTC())
                .scan();
        if (!report.analysisErrors().isEmpty()) {
            throw new AssertionError(report.analysisErrors());
        }
        var result = report.results().stream()
                .filter(rule -> rule.id().equals("ARCH-CODE-017"))
                .findFirst()
                .orElseThrow();
        if (result.violationCount() != 1 || !result.sampleViolations().get(0).contains("unmanaged()")) {
            throw new AssertionError(result);
        }
        System.out.println("THREAD_FACTORY_PACKAGED_OK");
        System.out.println(QuarkusThreadFactoryProbe.class.getResource("QuarkusThreadFactoryProbe.class"));
    }

    ThreadFactory factory() {
        return task -> new Thread(task);
    }

    Thread unmanaged() {
        return new Thread();
    }
}
