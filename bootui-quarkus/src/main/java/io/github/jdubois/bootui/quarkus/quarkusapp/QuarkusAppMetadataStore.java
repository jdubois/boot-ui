package io.github.jdubois.bootui.quarkus.quarkusapp;

import io.github.jdubois.bootui.spi.QuarkusAppEvidenceProblem;
import io.github.jdubois.bootui.spi.QuarkusAppMetadata;
import io.github.jdubois.bootui.spi.QuarkusAppMetadata.RestClient;
import io.github.jdubois.bootui.spi.QuarkusAppMetadata.SharedField;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Versioned, bounded declarations captured after Arc validation; never instantiates application beans. */
public final class QuarkusAppMetadataStore {

    public static final String RESOURCE_NAME = "META-INF/bootui/quarkus-app-metadata.bin";
    public static final int MAX_CLASSES = 10_000;
    public static final int MAX_MEMBERS = 100_000;
    public static final int MAX_CLIENTS = 256;
    public static final int MAX_STRING_BYTES = 4_096;
    public static final int MAX_RESOURCE_BYTES = 8 * 1_024 * 1_024;

    public static final String INCOMPLETE = "Application declaration collection reached its safety limit.";
    public static final String UNRESOLVED = "Application declaration metadata could not be resolved.";
    public static final String INVALID_RESOURCE = "Application declaration resource is invalid or unreadable.";

    private static final int MAGIC = 0x42554150;
    private static final int VERSION = 1;
    private static final List<String> RULES =
            List.of("QA-CDI-001", "QA-CDI-002", "QA-CDI-003", "QA-PERF-002", "QA-WEB-003");
    private static final Set<String> MESSAGES = Set.of(INCOMPLETE, UNRESOLVED, INVALID_RESOURCE);
    private static final Comparator<SharedField> FIELD_ORDER = Comparator.comparing(SharedField::className)
            .thenComparing(SharedField::fieldName)
            .thenComparing(SharedField::scope)
            .thenComparing(SharedField::resource);
    private static final Comparator<RestClient> CLIENT_ORDER =
            Comparator.comparing(RestClient::className).thenComparing(RestClient::configKey);
    private static final Comparator<QuarkusAppEvidenceProblem> PROBLEM_ORDER =
            Comparator.comparing(QuarkusAppEvidenceProblem::ruleId).thenComparing(QuarkusAppEvidenceProblem::message);

    private QuarkusAppMetadataStore() {}

    public static QuarkusAppMetadata load() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            return QuarkusAppMetadata.unavailable();
        }
        try (InputStream input = loader.getResourceAsStream(RESOURCE_NAME)) {
            if (input == null) {
                return QuarkusAppMetadata.unavailable();
            }
            return decode(input.readNBytes(MAX_RESOURCE_BYTES + 1));
        } catch (IOException | RuntimeException exception) {
            return invalid();
        }
    }

    public static byte[] encode(QuarkusAppMetadata metadata) {
        try {
            return write(bounded(metadata));
        } catch (IOException | RuntimeException exception) {
            // Malformed or oversized evidence must not prevent the host application from starting.
            try {
                return write(invalid());
            } catch (IOException impossible) {
                throw new IllegalStateException("Could not encode the fixed application metadata failure.");
            }
        }
    }

    public static QuarkusAppMetadata decode(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_RESOURCE_BYTES) {
            return invalid();
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                return invalid();
            }
            boolean available = input.readBoolean();
            int beans = count(input, MAX_CLASSES);
            int endpoints = count(input, MAX_MEMBERS);
            int properties = count(input, MAX_MEMBERS);
            int mappings = count(input, MAX_CLASSES);
            int scheduled = count(input, MAX_MEMBERS);
            boolean hibernate = input.readBoolean();
            boolean jdbc = input.readBoolean();
            boolean restClient = input.readBoolean();
            List<SharedField> fields = new ArrayList<>();
            for (int remaining = count(input, MAX_MEMBERS); remaining > 0; remaining--) {
                String className = string(input);
                String fieldName = string(input);
                String scope = string(input);
                if (!scope.equals("APPLICATION") && !scope.equals("SINGLETON")) {
                    return invalid();
                }
                fields.add(new SharedField(className, fieldName, scope, input.readBoolean()));
            }
            List<String> methods = new ArrayList<>();
            for (int remaining = count(input, MAX_MEMBERS); remaining > 0; remaining--) {
                methods.add(string(input));
            }
            List<RestClient> clients = new ArrayList<>();
            for (int remaining = count(input, MAX_CLIENTS); remaining > 0; remaining--) {
                clients.add(new RestClient(string(input), string(input)));
            }
            List<QuarkusAppEvidenceProblem> problems = new ArrayList<>();
            for (int remaining = count(input, RULES.size() * MESSAGES.size()); remaining > 0; remaining--) {
                String rule = string(input);
                String message = string(input);
                if (!RULES.contains(rule) || !MESSAGES.contains(message)) {
                    return invalid();
                }
                problems.add(new QuarkusAppEvidenceProblem(rule, message));
            }
            if (input.read() != -1) {
                return invalid();
            }
            return new QuarkusAppMetadata(
                    available,
                    beans,
                    endpoints,
                    properties,
                    mappings,
                    scheduled,
                    hibernate,
                    jdbc,
                    restClient,
                    fields,
                    methods,
                    clients,
                    problems);
        } catch (IOException | RuntimeException exception) {
            return invalid();
        }
    }

    private static QuarkusAppMetadata bounded(QuarkusAppMetadata source) {
        Set<QuarkusAppEvidenceProblem> problems = new TreeSet<>(PROBLEM_ORDER);
        for (int i = 0; i < Math.min(source.problems().size(), RULES.size() * MESSAGES.size()); i++) {
            QuarkusAppEvidenceProblem problem = source.problems().get(i);
            if (RULES.contains(problem.ruleId())) {
                problems.add(new QuarkusAppEvidenceProblem(
                        problem.ruleId(), MESSAGES.contains(problem.message()) ? problem.message() : UNRESOLVED));
            }
        }
        // Reserve enough space for the fixed header and every possible sanitized problem.
        int remainingBytes = MAX_RESOURCE_BYTES - 8_192;
        List<SharedField> fields = new ArrayList<>();
        if (source.sharedFields().size() > MAX_MEMBERS) {
            addProblems(problems, RULES.subList(0, 3));
        } else {
            for (SharedField field :
                    source.sharedFields().stream().sorted(FIELD_ORDER).toList()) {
                int bytes = size(field.className()) + size(field.fieldName()) + size(field.scope()) + 1;
                if (bytes < 0 || bytes > remainingBytes || !validField(field)) {
                    problems.add(new QuarkusAppEvidenceProblem(fieldRule(field), INCOMPLETE));
                    continue;
                }
                fields.add(field);
                remainingBytes -= bytes;
            }
        }
        List<String> methods = new ArrayList<>();
        if (source.synchronizedVirtualThreadMethods().size() > MAX_MEMBERS) {
            addProblems(problems, List.of("QA-PERF-002"));
        } else {
            for (String method : new TreeSet<>(source.synchronizedVirtualThreadMethods())) {
                int bytes = size(method);
                if (bytes < 0 || bytes > remainingBytes) {
                    addProblems(problems, List.of("QA-PERF-002"));
                    continue;
                }
                methods.add(method);
                remainingBytes -= bytes;
            }
        }
        List<RestClient> clients = new ArrayList<>();
        if (source.restClients().size() > MAX_CLIENTS) {
            addProblems(problems, List.of("QA-WEB-003"));
        } else {
            for (RestClient client :
                    source.restClients().stream().sorted(CLIENT_ORDER).toList()) {
                int bytes = size(client.className()) + size(client.configKey());
                if (size(client.className()) < 0 || size(client.configKey()) < 0 || bytes > remainingBytes) {
                    addProblems(problems, List.of("QA-WEB-003"));
                    continue;
                }
                clients.add(client);
                remainingBytes -= bytes;
            }
        }
        if (source.beanCount() < 0
                || source.beanCount() > MAX_CLASSES
                || source.endpointCount() < 0
                || source.endpointCount() > MAX_MEMBERS
                || source.configPropertyCount() < 0
                || source.configPropertyCount() > MAX_MEMBERS
                || source.configMappingCount() < 0
                || source.configMappingCount() > MAX_CLASSES
                || source.scheduledDeclarationCount() < 0
                || source.scheduledDeclarationCount() > MAX_MEMBERS) {
            addProblems(problems, RULES);
        }
        return new QuarkusAppMetadata(
                source.available(),
                clamp(source.beanCount(), MAX_CLASSES),
                clamp(source.endpointCount(), MAX_MEMBERS),
                clamp(source.configPropertyCount(), MAX_MEMBERS),
                clamp(source.configMappingCount(), MAX_CLASSES),
                clamp(source.scheduledDeclarationCount(), MAX_MEMBERS),
                source.hibernateOrmSupported(),
                source.jdbcDatasourceSupported(),
                source.restClientSupported(),
                fields,
                methods,
                clients,
                List.copyOf(problems));
    }

    private static boolean validField(SharedField field) {
        return size(field.className()) >= 0
                && size(field.fieldName()) >= 0
                && (field.scope().equals("APPLICATION") || field.scope().equals("SINGLETON"));
    }

    private static String fieldRule(SharedField field) {
        return field.resource() ? "QA-CDI-002" : field.scope().equals("SINGLETON") ? "QA-CDI-003" : "QA-CDI-001";
    }

    private static void addProblems(Set<QuarkusAppEvidenceProblem> problems, List<String> rules) {
        for (String rule : rules) {
            problems.add(new QuarkusAppEvidenceProblem(rule, INCOMPLETE));
        }
    }

    private static int clamp(int count, int maximum) {
        return Math.max(0, Math.min(count, maximum));
    }

    private static int size(String value) {
        if (value == null || value.length() > MAX_STRING_BYTES || value.chars().anyMatch(Character::isISOControl)) {
            return -1;
        }
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        return length > MAX_STRING_BYTES ? -1 : Integer.BYTES + length;
    }

    private static byte[] write(QuarkusAppMetadata metadata) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeBoolean(metadata.available());
            output.writeInt(metadata.beanCount());
            output.writeInt(metadata.endpointCount());
            output.writeInt(metadata.configPropertyCount());
            output.writeInt(metadata.configMappingCount());
            output.writeInt(metadata.scheduledDeclarationCount());
            output.writeBoolean(metadata.hibernateOrmSupported());
            output.writeBoolean(metadata.jdbcDatasourceSupported());
            output.writeBoolean(metadata.restClientSupported());
            output.writeInt(metadata.sharedFields().size());
            for (SharedField field : metadata.sharedFields()) {
                string(output, field.className());
                string(output, field.fieldName());
                string(output, field.scope());
                output.writeBoolean(field.resource());
            }
            output.writeInt(metadata.synchronizedVirtualThreadMethods().size());
            for (String method : metadata.synchronizedVirtualThreadMethods()) {
                string(output, method);
            }
            output.writeInt(metadata.restClients().size());
            for (RestClient client : metadata.restClients()) {
                string(output, client.className());
                string(output, client.configKey());
            }
            output.writeInt(metadata.problems().size());
            for (QuarkusAppEvidenceProblem problem : metadata.problems()) {
                string(output, problem.ruleId());
                string(output, problem.message());
            }
        }
        return bytes.toByteArray();
    }

    private static void string(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String string(DataInputStream input) throws IOException {
        int length = count(input, MAX_STRING_BYTES);
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException();
        }
        try {
            String value = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            if (size(value) < 0) {
                throw new IOException();
            }
            return value;
        } catch (CharacterCodingException exception) {
            throw new IOException();
        }
    }

    private static int count(DataInputStream input, int maximum) throws IOException {
        int value = input.readInt();
        if (value < 0 || value > maximum) {
            throw new IOException();
        }
        return value;
    }

    private static QuarkusAppMetadata invalid() {
        return new QuarkusAppMetadata(
                false,
                0,
                0,
                0,
                0,
                0,
                false,
                false,
                false,
                List.of(),
                List.of(),
                List.of(),
                RULES.stream()
                        .map(rule -> new QuarkusAppEvidenceProblem(rule, INVALID_RESOURCE))
                        .toList());
    }
}
