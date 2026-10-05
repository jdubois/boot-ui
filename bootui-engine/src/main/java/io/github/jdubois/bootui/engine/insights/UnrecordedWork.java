package io.github.jdubois.bootui.engine.insights;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Work an application can do that the runtime journal does not record, named up front in Runtime Insights so that a
 * service whose work happens there is never read as idle or healthy ({@code docs/PLAN-v2.md} M4-22). Detection is by
 * class presence only: it never initializes a class or touches the library.
 */
public final class UnrecordedWork {

    /** The class that tells Kafka Streams is on the classpath. */
    static final String KAFKA_STREAMS_CLASS = "org.apache.kafka.streams.KafkaStreams";

    /** Why a Kafka Streams topology's work is missing from every observation. */
    public static final String KAFKA_STREAMS = "Kafka Streams is on this application's classpath, and BootUI does not"
            + " record stream processing: the records a topology consumes and produces are neither executions nor"
            + " messages here, so a service that only processes streams can show no observation and no traffic."
            + " Messages its Kafka listeners consume and its producers send are still recorded.";

    private UnrecordedWork() {}

    /** The lines for what {@code classLoader} can run that the journal does not record, possibly none. */
    public static List<String> detect(ClassLoader classLoader) {
        return detect(className -> present(className, classLoader));
    }

    /** The lines for the classes {@code present} says are on the classpath. */
    static List<String> detect(Predicate<String> present) {
        List<String> lines = new ArrayList<>();
        if (present.test(KAFKA_STREAMS_CLASS)) {
            lines.add(KAFKA_STREAMS);
        }
        return List.copyOf(lines);
    }

    static boolean present(String className, ClassLoader classLoader) {
        ClassLoader loader = classLoader != null ? classLoader : UnrecordedWork.class.getClassLoader();
        if (loader == null) {
            return false;
        }
        // Not initialized, and works in a native image, where a class file is not a resource.
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError | RuntimeException ex) {
            return false;
        }
    }
}
