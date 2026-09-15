package bootui.packaged.threadfactory;

import io.github.jdubois.bootui.engine.architecture.ArchitecturePlatform;
import io.github.jdubois.bootui.engine.architecture.ArchitectureScanner;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ThreadFactory;

public class ThreadFactoryProbe {
    public static void main(String[] args) throws Exception {
        Class.forName("com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassReader");
        try (InputStream input = ThreadFactoryProbe.class.getResourceAsStream("NewerClassFile.class")) {
            try {
                Class.forName("org.objectweb.asm.ClassReader")
                        .getConstructor(InputStream.class)
                        .newInstance(input);
                throw new AssertionError("The deliberately old host ASM must reject this class-file version");
            } catch (InvocationTargetException ex) {
                if (!(ex.getCause() instanceof IllegalArgumentException)) {
                    throw ex;
                }
            }
        }
        var report = ArchitectureScanner.usingClasspath(
                        () -> List.of(ThreadFactoryProbe.class.getPackageName()),
                        ArchitecturePlatform.SPRING,
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
        System.out.println(ThreadFactoryProbe.class.getResource("ThreadFactoryProbe.class"));
    }

    ThreadFactory factory() {
        return task -> new Thread(task);
    }

    Thread unmanaged() {
        return new Thread();
    }
}
