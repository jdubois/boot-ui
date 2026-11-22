package io.github.jdubois.bootui.cli;

import io.github.jdubois.bootui.client.JsonValue;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.engine.activity.ActivityStoreException;
import io.github.jdubois.bootui.engine.activity.SimpleDriverDataSource;
import io.github.jdubois.bootui.engine.journal.DurableJournalArchiveReport;
import io.github.jdubois.bootui.engine.journal.DurableJournalArchiveService;
import io.github.jdubois.bootui.engine.journal.DurableJournalFeedItem;
import io.github.jdubois.bootui.engine.journal.DurableJournalFeedPage;
import io.github.jdubois.bootui.engine.journal.DurableJournalFeedQuery;
import io.github.jdubois.bootui.engine.journal.DurableJournalGlobalCursor;
import io.github.jdubois.bootui.engine.journal.DurableJournalQuery;
import io.github.jdubois.bootui.engine.journal.DurableJournalRun;
import io.github.jdubois.bootui.engine.journal.DurableJournalScope;
import io.github.jdubois.bootui.engine.journal.DurableJournalSettings;
import io.github.jdubois.bootui.engine.journal.DurableJournalStoreException;
import io.github.jdubois.bootui.engine.journal.DurableJournalUnavailableException;
import io.github.jdubois.bootui.engine.journal.JdbcDurableJournalStore;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.lang.reflect.RecordComponent;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

/** Client-side archive reads directly from BootUI's durable journal JDBC tables. */
final class ArchiveCommands {

    private static final String JDBC_URL_ENV = "BOOTUI_ARCHIVE_JDBC_URL";
    private static final String USERNAME_ENV = "BOOTUI_ARCHIVE_USERNAME";
    private static final String PASSWORD_ENV = "BOOTUI_ARCHIVE_PASSWORD";
    private static final String DRIVER_ENV = "BOOTUI_ARCHIVE_DRIVER";
    private static final String TABLE_PREFIX_ENV = "BOOTUI_ARCHIVE_TABLE_PREFIX";
    private static final String NAMESPACE_ENV = "BOOTUI_ARCHIVE_NAMESPACE";
    private static final String APPLICATION_ENV = "BOOTUI_ARCHIVE_APPLICATION";
    private static final String RUN_ENV = "BOOTUI_ARCHIVE_RUN";

    private static final ExposurePolicy MASKED = new ExposurePolicy() {
        @Override
        public ValueExposure valueExposure() {
            return ValueExposure.MASKED;
        }

        @Override
        public boolean maskSecrets() {
            return true;
        }
    };

    private ArchiveCommands() {}

    static void add(CliContext context, CommandSpec root) {
        ArchiveOptions options = new ArchiveOptions(context);
        CommandSpec archive = usageGroup();
        configureArchive(archive, options);
        archive.addSubcommand("runs", new CommandLine(runs(context, options)));
        archive.addSubcommand("activity", new CommandLine(activity(context, options)));
        archive.addSubcommand("profile", new CommandLine(profile(context, options)));
        archive.addSubcommand("insights", new CommandLine(insights(context, options)));
        root.addSubcommand("archive", new CommandLine(archive));
    }

    private static CommandSpec usageGroup() {
        UsageCommand command = new UsageCommand();
        CommandSpec spec = CommandSpec.wrapWithoutInspection(command).name("archive");
        command.spec = spec;
        spec.mixinStandardHelpOptions(true);
        spec.exitCodeOnInvalidInput(ExitCodes.ERROR);
        spec.exitCodeOnExecutionException(ExitCodes.ERROR);
        spec.usageMessage()
                .autoWidth(true)
                .description(
                        "Read durable runtime-journal history directly over JDBC, without a running application.",
                        "Archive reads are masked and strict: the store never creates or changes schema.",
                        "Put the database's JDBC driver on the Java classpath; --driver names its Driver class.")
                .footer(
                        "",
                        "Examples: bootui archive runs --jdbc-url <url> --namespace <ns> --application <app>",
                        "          bootui archive profile <requestId> --run <run> ...",
                        "",
                        "For user-supplied drivers, launch the BootUiCli main class with java -cp and both the BootUI"
                                + " all jar and driver jar; java -jar cannot extend its classpath.",
                        "Credentials can be supplied through BOOTUI_ARCHIVE_* environment variables.",
                        "Command guide: bootui docs cli");
        return spec;
    }

    private static CommandSpec runs(CliContext context, ArchiveOptions options) {
        Runs command = new Runs(context, options);
        CommandSpec spec = localCommand(command, "runs", "List durable runs newest-first.");
        configureArchive(spec, options);
        spec.addOption(limitOption(
                DurableJournalArchiveService.MAX_MULTI_RUNS,
                DurableJournalArchiveService.MAX_MULTI_RUNS,
                value -> command.limit = value));
        return spec;
    }

    private static CommandSpec activity(CliContext context, ArchiveOptions options) {
        Activity command = new Activity(context, options);
        CommandSpec spec = localCommand(command, "activity", "Read the masked, persisted Live Activity feed.");
        configureArchive(spec, options);
        spec.addOption(limitOption(
                DurableJournalQuery.MAX_PAGE_SIZE,
                DurableJournalQuery.DEFAULT_PAGE_SIZE,
                value -> command.limit = value));
        addStringOption(
                spec, "--instance", "<id>", "Exact persisted instance id.", value -> command.instanceId = value);
        addStringOption(
                spec,
                "--source",
                "<source>",
                "Exact journal source, such as HTTP or SQL.",
                value -> command.source = value);
        addStringOption(spec, "--type", "<type>", "Exact projected activity row type.", value -> command.type = value);
        addStringOption(
                spec, "--severity", "<severity>", "Exact projected severity.", value -> command.severity = value);
        addStringOption(
                spec,
                "-q",
                "--query",
                "<text>",
                "Case-insensitive text match in persisted projected fields.",
                value -> command.query = value);
        addStringOption(spec, "--route", "<route>", "Exact route filter.", value -> command.route = value);
        addStringOption(
                spec,
                "--request-id",
                "<requestId>",
                "Exact request or execution id.",
                value -> command.requestId = value);
        spec.addOption(OptionSpec.builder("--since")
                .paramLabel("<epochMillis>")
                .type(Long.class)
                .description("Inclusive occurrence-time lower bound.")
                .setter(CommandTree.setter((Long value) -> command.since = value))
                .build());
        spec.addOption(OptionSpec.builder("--until")
                .paramLabel("<epochMillis>")
                .type(Long.class)
                .description("Inclusive occurrence-time upper bound.")
                .setter(CommandTree.setter((Long value) -> command.until = value))
                .build());
        spec.addOption(OptionSpec.builder("--no-request")
                .type(boolean.class)
                .description("Return only rows not linked to a request or execution.")
                .setter(CommandTree.setter((Boolean value) -> command.noRequest = Boolean.TRUE.equals(value)))
                .build());
        addStringOption(
                spec,
                "--cursor",
                "<cursor>",
                "Next cursor from a previous archive activity response.",
                value -> command.cursor = value);
        return spec;
    }

    private static CommandSpec profile(CliContext context, ArchiveOptions options) {
        Profile command = new Profile(context, options);
        CommandSpec spec = localCommand(command, "profile", "Reconstruct one request profile from one persisted run.");
        configureArchive(spec, options);
        spec.addPositional(PositionalParamSpec.builder()
                .index("0")
                .arity("1")
                .required(true)
                .paramLabel("<requestId>")
                .type(String.class)
                .description("Request or execution id stored in the selected run.")
                .setter(CommandTree.setter((String value) -> command.requestId = value))
                .build());
        return spec;
    }

    private static CommandSpec insights(CliContext context, ArchiveOptions options) {
        Insights command = new Insights(context, options);
        CommandSpec spec = localCommand(
                command,
                "insights",
                "Project Runtime Insights from one run, or from the newest runs when --run is omitted.");
        configureArchive(spec, options);
        spec.addOption(OptionSpec.builder("--scope")
                .paramLabel("<namespace/application/run>")
                .type(List.class)
                .auxiliaryTypes(String.class)
                .arity("1")
                .description("Explicit durable scope; repeat for several runs or applications (at most 100).")
                .setter(CommandTree.setter((List<String> values) -> command.scopes.addAll(values)))
                .build());
        spec.addOption(limitOption(
                DurableJournalArchiveService.MAX_MULTI_RUNS,
                DurableJournalArchiveService.MAX_MULTI_RUNS,
                value -> command.limit = value));
        return spec;
    }

    private static CommandSpec localCommand(Callable<Integer> command, String name, String description) {
        CommandSpec spec = CommandSpec.wrapWithoutInspection(command).name(name);
        spec.mixinStandardHelpOptions(true);
        spec.exitCodeOnInvalidInput(ExitCodes.ERROR);
        spec.exitCodeOnExecutionException(ExitCodes.ERROR);
        spec.usageMessage().autoWidth(true).description(description);
        return spec;
    }

    private static OptionSpec limitOption(
            int maximum, int defaultValue, java.util.function.Consumer<Integer> consumer) {
        return OptionSpec.builder("-n", "--limit")
                .paramLabel("<count>")
                .type(Integer.class)
                .defaultValue(Integer.toString(defaultValue))
                .description("Maximum page or run count (at most " + maximum + ").")
                .converters(value -> positiveLimit(value, maximum))
                .setter(CommandTree.setter(consumer))
                .build();
    }

    private static void configureArchive(CommandSpec spec, ArchiveOptions options) {
        addOutputOptions(spec, options.context.options());
        addStringOption(
                spec, "--jdbc-url", "<url>", "JDBC URL. Or $" + JDBC_URL_ENV + ".", value -> options.jdbcUrl = value);
        addStringOption(
                spec,
                "--username",
                "<user>",
                "Database user. Or $" + USERNAME_ENV + ".",
                value -> options.username = value);
        addStringOption(
                spec,
                "--password",
                "<password>",
                "Database password. Prefer $" + PASSWORD_ENV + ".",
                value -> options.password = value);
        addStringOption(
                spec,
                "--driver",
                "<class>",
                "Optional user-supplied JDBC Driver class. Or $" + DRIVER_ENV + ".",
                value -> options.driver = value);
        addStringOption(
                spec,
                "--table-prefix",
                "<prefix>",
                "Durable table prefix. Default "
                        + DurableJournalSettings.DEFAULT_TABLE_PREFIX
                        + ", or $"
                        + TABLE_PREFIX_ENV
                        + ".",
                value -> options.tablePrefix = value);
        addStringOption(
                spec,
                "--namespace",
                "<namespace>",
                "Durable journal namespace. Or $" + NAMESPACE_ENV + ".",
                value -> options.namespace = value);
        addStringOption(
                spec,
                "--application",
                "<application>",
                "Durable journal application. Or $" + APPLICATION_ENV + ".",
                value -> options.application = value);
        addStringOption(
                spec, "--run", "<runId>", "Exact durable run id. Or $" + RUN_ENV + ".", value -> options.run = value);
        spec.addOption(OptionSpec.builder("--allow-cross-namespace")
                .type(boolean.class)
                .description("Allow archive activity or insights reads across namespaces.")
                .setter(CommandTree.setter((Boolean value) -> options.allowCrossNamespace = Boolean.TRUE.equals(value)))
                .build());
    }

    private static void addOutputOptions(CommandSpec spec, GlobalOptions globals) {
        spec.addOption(OptionSpec.builder("--json")
                .type(boolean.class)
                .description("Print generated archive JSON. Implied when output is not a terminal.")
                .setter(CommandTree.setter(globals::setJson))
                .build());
        spec.addOption(OptionSpec.builder("--no-color")
                .type(boolean.class)
                .description("Disable ANSI colour. Also honours $NO_COLOR.")
                .setter(CommandTree.setter(globals::setNoColor))
                .build());
        spec.addOption(OptionSpec.builder("-v", "--verbose")
                .type(boolean.class)
                .description("Request verbose CLI errors; archive failures never include JDBC credentials.")
                .setter(CommandTree.setter(globals::setVerbose))
                .build());
    }

    private static void addStringOption(
            CommandSpec spec,
            String name,
            String paramLabel,
            String description,
            java.util.function.Consumer<String> consumer) {
        spec.addOption(OptionSpec.builder(name)
                .paramLabel(paramLabel)
                .type(String.class)
                .description(description)
                .setter(CommandTree.setter(consumer))
                .build());
    }

    private static void addStringOption(
            CommandSpec spec,
            String name,
            String alias,
            String paramLabel,
            String description,
            java.util.function.Consumer<String> consumer) {
        spec.addOption(OptionSpec.builder(name, alias)
                .paramLabel(paramLabel)
                .type(String.class)
                .description(description)
                .setter(CommandTree.setter(consumer))
                .build());
    }

    private static int positiveLimit(String raw, int maximum) {
        try {
            int value = Integer.parseInt(raw);
            if (value <= 0 || value > maximum) {
                throw new CommandLine.TypeConversionException("must be between 1 and " + maximum);
            }
            return value;
        } catch (NumberFormatException invalid) {
            throw new CommandLine.TypeConversionException("'" + raw + "' is not a number");
        }
    }

    private abstract static class ArchiveCommand implements Callable<Integer> {

        final CliContext context;
        final ArchiveOptions options;

        ArchiveCommand(CliContext context, ArchiveOptions options) {
            this.context = context;
            this.options = options;
        }

        abstract JsonValue read() throws Exception;

        @Override
        public final Integer call() {
            try {
                context.emit(read());
                return ExitCodes.SUCCESS;
            } catch (ActivityStoreException
                    | DurableJournalStoreException
                    | IllegalArgumentException
                    | SecurityException failure) {
                context.err().println(safeMessage(failure));
                context.err().flush();
                return ExitCodes.ERROR;
            } catch (ReflectiveOperationException serialization) {
                context.err().println("The durable journal result could not be serialized.");
                context.err().flush();
                return ExitCodes.ERROR;
            } catch (Exception unexpected) {
                context.err()
                        .println("The durable journal archive could not be read ("
                                + unexpected.getClass().getSimpleName()
                                + ").");
                context.err().flush();
                return ExitCodes.ERROR;
            }
        }

        private static String safeMessage(RuntimeException failure) {
            if (failure instanceof DurableJournalUnavailableException) {
                return failure.getMessage();
            }
            if (failure instanceof DurableJournalStoreException) {
                String message = failure.getMessage();
                return message == null || message.isBlank() ? "Durable journal read failed." : message;
            }
            if (failure instanceof ActivityStoreException) {
                return failure.getMessage();
            }
            return failure.getMessage() == null || failure.getMessage().isBlank()
                    ? "Invalid durable journal archive options."
                    : failure.getMessage();
        }
    }

    private static final class Runs extends ArchiveCommand {

        int limit = DurableJournalArchiveService.MAX_MULTI_RUNS;

        Runs(CliContext context, ArchiveOptions options) {
            super(context, options);
        }

        @Override
        JsonValue read() {
            String namespace = options.requiredNamespace();
            String application = options.requiredApplication();
            List<DurableJournalRun> runs = options.service().listRuns(namespace, application, limit);
            String selectedRun = options.run();
            if (selectedRun != null) {
                runs = runs.stream()
                        .filter(run -> selectedRun.equals(run.scope().run()))
                        .toList();
            }
            Map<String, JsonValue> payload = new LinkedHashMap<>();
            payload.put(
                    "runs",
                    JsonValue.array(runs.stream().map(ArchiveCommands::runJson).toList()));
            return JsonValue.object(payload);
        }
    }

    private static final class Activity extends ArchiveCommand {

        int limit = DurableJournalQuery.DEFAULT_PAGE_SIZE;
        String instanceId;
        String source;
        String type;
        String severity;
        String query;
        String route;
        String requestId;
        Long since;
        Long until;
        boolean noRequest;
        String cursor;

        Activity(CliContext context, ArchiveOptions options) {
            super(context, options);
        }

        @Override
        JsonValue read() throws ReflectiveOperationException {
            DurableJournalGlobalCursor decodedCursor = DurableJournalGlobalCursor.decodeStrict(cursor);
            DurableJournalFeedQuery query = new DurableJournalFeedQuery(
                    options.activityNamespace(),
                    options.application(),
                    options.run(),
                    instanceId,
                    source,
                    type,
                    severity,
                    this.query,
                    route,
                    requestId,
                    since,
                    until,
                    noRequest,
                    decodedCursor,
                    limit);
            DurableJournalFeedPage page = options.service().queryFeed(query);
            Map<String, JsonValue> payload = new LinkedHashMap<>();
            payload.put(
                    "entries",
                    JsonValue.array(page.entries().stream()
                            .map(ArchiveCommands::feedJson)
                            .toList()));
            payload.put("nextCursor", JsonValue.of(page.nextCursorToken()));
            payload.put("hasMore", JsonValue.of(page.hasMore()));
            return JsonValue.object(payload);
        }
    }

    private static final class Profile extends ArchiveCommand {

        String requestId;

        Profile(CliContext context, ArchiveOptions options) {
            super(context, options);
        }

        @Override
        JsonValue read() throws ReflectiveOperationException {
            DurableJournalScope scope = options.requiredScope();
            return ArchiveJson.value(options.service().profile(scope, requestId));
        }
    }

    private static final class Insights extends ArchiveCommand {

        int limit = DurableJournalArchiveService.MAX_MULTI_RUNS;
        final List<String> scopes = new ArrayList<>();

        Insights(CliContext context, ArchiveOptions options) {
            super(context, options);
        }

        @Override
        JsonValue read() throws ReflectiveOperationException {
            if (!scopes.isEmpty()) {
                List<DurableJournalScope> selected = parseScopes(scopes);
                DurableJournalArchiveReport report = options.service(selected).multiRunInsights(selected);
                return ArchiveJson.value(report);
            }
            String selectedRun = options.run();
            if (selectedRun != null) {
                return ArchiveJson.value(options.service().insights(options.requiredScope()));
            }
            String namespace = options.requiredNamespace();
            String application = options.requiredApplication();
            DurableJournalArchiveService service = options.service();
            List<DurableJournalScope> scopes = service.listRuns(namespace, application, limit).stream()
                    .map(DurableJournalRun::scope)
                    .toList();
            DurableJournalArchiveReport report = service.multiRunInsights(scopes);
            return ArchiveJson.value(report);
        }

        private static List<DurableJournalScope> parseScopes(List<String> values) {
            if (values.size() > DurableJournalArchiveService.MAX_MULTI_RUNS) {
                throw new IllegalArgumentException(
                        "At most " + DurableJournalArchiveService.MAX_MULTI_RUNS + " --scope values are allowed.");
            }
            List<DurableJournalScope> result = new ArrayList<>(values.size());
            for (String value : values) {
                String[] segments = value == null ? new String[0] : value.split("/", -1);
                if (segments.length != 3) {
                    throw new IllegalArgumentException(
                            "Invalid --scope '" + value + "': expected exactly namespace/application/run.");
                }
                for (int index = 0; index < segments.length; index++) {
                    segments[index] = segments[index].strip();
                    if (segments[index].isEmpty()) {
                        throw new IllegalArgumentException("Invalid --scope '" + value
                                + "': namespace, application, and run must all be nonblank.");
                    }
                }
                result.add(new DurableJournalScope(segments[0], segments[1], segments[2]));
            }
            return List.copyOf(result);
        }
    }

    private static JsonValue runJson(DurableJournalRun run) {
        Map<String, JsonValue> values = new LinkedHashMap<>();
        values.put("namespace", JsonValue.of(run.scope().namespace()));
        values.put("application", JsonValue.of(run.scope().application()));
        values.put("run", JsonValue.of(run.scope().run()));
        values.put("startedAt", JsonValue.of(run.startedAtEpochMillis()));
        values.put("updatedAt", JsonValue.of(run.updatedAtEpochMillis()));
        values.put("status", JsonValue.of(run.status()));
        values.put("checkpoint", JsonValue.of(run.checkpoint()));
        values.put("instanceId", JsonValue.of(run.instanceId()));
        return JsonValue.object(values);
    }

    private static JsonValue feedJson(DurableJournalFeedItem item) {
        Map<String, JsonValue> values = new LinkedHashMap<>();
        values.put("namespace", JsonValue.of(item.origin().scope().namespace()));
        values.put("application", JsonValue.of(item.origin().scope().application()));
        values.put("run", JsonValue.of(item.origin().scope().run()));
        values.put("instanceId", JsonValue.of(item.origin().instanceId()));
        values.put("globalSequence", JsonValue.of(item.globalSequence()));
        values.put("journalSequence", JsonValue.of(item.journalSequence()));
        values.put("timestamp", JsonValue.of(item.timestamp()));
        values.put("source", JsonValue.of(item.source()));
        values.put("projectionAvailable", JsonValue.of(item.projectionAvailable()));
        values.put("activity", item.activity() == null ? JsonValue.NULL : ArchiveJson.valueUnchecked(item.activity()));
        return JsonValue.object(values);
    }

    private static final class ArchiveOptions {

        private final CliContext context;
        private String jdbcUrl;
        private String username;
        private String password;
        private String driver;
        private String tablePrefix;
        private String namespace;
        private String application;
        private String run;
        private boolean allowCrossNamespace;

        ArchiveOptions(CliContext context) {
            this.context = context;
        }

        DurableJournalArchiveService service() {
            return service(null);
        }

        DurableJournalArchiveService service(List<DurableJournalScope> scopes) {
            String url = required("JDBC URL", explicitOrEnvironment(jdbcUrl, JDBC_URL_ENV));
            String configuredDriver = explicitOrEnvironment(driver, DRIVER_ENV);
            SimpleDriverDataSource dataSource;
            try {
                dataSource = new SimpleDriverDataSource(
                        url, passwordAwareValue(username, USERNAME_ENV), explicitPassword(), configuredDriver);
            } catch (ActivityStoreException missingDriver) {
                throw new ActivityStoreException(
                        "JDBC driver class was not found. Put the driver jar on the Java classpath and pass"
                                + " --driver if auto-registration is not available.",
                        missingDriver);
            }
            try {
                DriverManager.getDriver(url);
            } catch (SQLException noDriver) {
                throw new IllegalArgumentException(
                        "No JDBC driver accepts the configured URL. Put a user-supplied driver jar on the Java"
                                + " classpath and pass --driver <DriverClass>.");
            }
            JdbcDurableJournalStore store = new JdbcDurableJournalStore(dataSource, tablePrefix(), true);
            store.verifySchema();
            String selectedNamespace = namespace();
            if (selectedNamespace == null && scopes != null && !scopes.isEmpty()) {
                selectedNamespace = scopes.get(0).namespace();
            }
            if (allowCrossNamespace) {
                return DurableJournalArchiveService.crossNamespaceReader(store, MASKED);
            }
            if (selectedNamespace == null) {
                throw new IllegalArgumentException(
                        "--namespace is required unless --allow-cross-namespace is used for a global activity read");
            }
            return DurableJournalArchiveService.forNamespace(store, MASKED, selectedNamespace);
        }

        String activityNamespace() {
            String selected = namespace();
            if (selected == null && !allowCrossNamespace) {
                throw new IllegalArgumentException(
                        "--namespace is required unless --allow-cross-namespace is used for a global activity read");
            }
            return selected;
        }

        String requiredNamespace() {
            return required("--namespace", namespace());
        }

        String requiredApplication() {
            return required("--application", application());
        }

        DurableJournalScope requiredScope() {
            return new DurableJournalScope(requiredNamespace(), requiredApplication(), required("--run", run()));
        }

        String namespace() {
            return optional(explicitOrEnvironment(namespace, NAMESPACE_ENV));
        }

        String application() {
            return optional(explicitOrEnvironment(application, APPLICATION_ENV));
        }

        String run() {
            return optional(explicitOrEnvironment(run, RUN_ENV));
        }

        private String explicitPassword() {
            if (password != null) {
                return password;
            }
            return context.environment().get(PASSWORD_ENV);
        }

        private String passwordAwareValue(String explicit, String environmentVariable) {
            String resolved = explicitOrEnvironment(explicit, environmentVariable);
            return resolved == null || resolved.isBlank() ? null : resolved;
        }

        private String tablePrefix() {
            String configured = explicitOrEnvironment(tablePrefix, TABLE_PREFIX_ENV);
            return configured == null || configured.isBlank()
                    ? DurableJournalSettings.DEFAULT_TABLE_PREFIX
                    : configured.strip();
        }

        private String explicitOrEnvironment(String explicit, String environmentVariable) {
            if (explicit != null && !explicit.isBlank()) {
                return explicit;
            }
            String fromEnvironment = context.environment().get(environmentVariable);
            return fromEnvironment == null || fromEnvironment.isBlank() ? null : fromEnvironment;
        }

        private static String optional(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }

        private static String required(String name, String value) {
            String selected = optional(value);
            if (selected == null) {
                throw new IllegalArgumentException(name + " is required for this archive command.");
            }
            return selected;
        }
    }

    /** Reflection is confined to immutable records returned by the engine, outside the dependency-free client. */
    private static final class ArchiveJson {

        private ArchiveJson() {}

        static JsonValue value(Object value) throws ReflectiveOperationException {
            if (value == null) {
                return JsonValue.NULL;
            }
            if (value instanceof String string) {
                return JsonValue.of(string);
            }
            if (value instanceof Boolean bool) {
                return JsonValue.of(bool);
            }
            if (value instanceof Character character) {
                return JsonValue.of(character.toString());
            }
            if (value instanceof Byte byteValue) {
                return JsonValue.of(byteValue.longValue());
            }
            if (value instanceof Short shortValue) {
                return JsonValue.of(shortValue.longValue());
            }
            if (value instanceof Integer integer) {
                return JsonValue.of(integer.longValue());
            }
            if (value instanceof Long longValue) {
                return JsonValue.of(longValue);
            }
            if (value instanceof Number number) {
                return JsonValue.parse(number.toString());
            }
            if (value instanceof Enum<?> enumeration) {
                return JsonValue.of(enumeration.name());
            }
            if (value instanceof Iterable<?> iterable) {
                List<JsonValue> values = new ArrayList<>();
                for (Object item : iterable) {
                    values.add(value(item));
                }
                return JsonValue.array(values);
            }
            if (value instanceof Map<?, ?> map) {
                Map<String, JsonValue> values = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new ReflectiveOperationException("archive JSON map key is not a string");
                    }
                    values.put(key, value(entry.getValue()));
                }
                return JsonValue.object(values);
            }
            if (value.getClass().isRecord()) {
                Map<String, JsonValue> values = new LinkedHashMap<>();
                for (RecordComponent component : value.getClass().getRecordComponents()) {
                    Object member = component.getAccessor().invoke(value);
                    values.put(component.getName(), value(member));
                }
                return JsonValue.object(values);
            }
            throw new ReflectiveOperationException(
                    "archive JSON does not support " + value.getClass().getName());
        }

        static JsonValue valueUnchecked(Object value) {
            try {
                return value(value);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Archive activity serialization failed", failure);
            }
        }
    }

    private static final class UsageCommand implements Callable<Integer> {

        private CommandSpec spec;

        @Override
        public Integer call() {
            spec.commandLine().usage(spec.commandLine().getErr());
            return ExitCodes.ERROR;
        }
    }
}
