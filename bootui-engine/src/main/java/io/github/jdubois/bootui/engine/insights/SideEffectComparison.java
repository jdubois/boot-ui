package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.core.dto.RuntimeRunComparisonDto;
import io.github.jdubois.bootui.core.dto.RuntimeSideEffectChangeDto;
import io.github.jdubois.bootui.core.dto.RuntimeSideEffectChangesDto;
import io.github.jdubois.bootui.core.dto.RuntimeSideEffectSensorDto;
import io.github.jdubois.bootui.engine.journal.RunSideEffects;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * Compares two runs' side effects ({@code docs/PLAN-v2.md} §5.8, §5.16, M5-7b): the side-effect keys one run has and
 * the other does not, per sensor, said only when the sensor recorded the whole of both runs. A key is new when this run
 * has it and the previous did not; gone when the previous had it, this run exercised its owner, and this run does not
 * have it; otherwise its owner was not exercised. A sensor that kept only part of its keys in a run withholds the rows
 * that part could make wrong: new keys when the previous run's were cut, gone keys when this run's were.
 */
final class SideEffectComparison {

    static final String ROUTE = "route";
    static final String EXECUTION = "execution";
    static final String STARTUP = "startup";

    /** The one owner of every route when HTTP Exchanges was hidden in either run, or is now. */
    static final String HIDDEN_ROUTE = "(route hidden: HTTP Exchanges is disabled)";

    static final String LIMITATION_VALUES = "Side effects are compared by name and normalized, masked pattern only:"
            + " hosts and ports, path patterns, process file names, and variable names, never a value, an argument, or"
            + " a file's contents.";

    static final String LIMITATION_WHOLE = "A sensor is compared only when it recorded the whole of both runs, from"
            + " the application's start for startup's keys, without losing a record; work on a thread no request or"
            + " execution owns is not compared, since the agent may still hold its records.";

    static final String LIMITATION_EXERCISED = "A key the previous run had is gone only when this run exercised its"
            + " owner: its route served a request, its job ran, or it did something else outside the JVM; otherwise"
            + " its owner was not exercised in this run.";

    static final String LIMITATION_HIDDEN_ROUTES = "HTTP Exchanges was hidden in one of the runs, or is now, so every"
            + " route is compared as one hidden route, and none of its keys is reported gone.";

    private static final List<String> SENSORS = List.of("network", "files", "processes", "environment");

    private SideEffectComparison() {}

    /**
     * {@code previous} and {@code current} compared.
     *
     * @param exercised whether this run exercised an owner, by scope and owner
     * @param routesHiddenNow whether HTTP Exchanges is hidden for this read
     */
    static RuntimeSideEffectChangesDto compare(
            RunSideEffects previous,
            RunSideEffects current,
            BiPredicate<String, String> exercised,
            boolean routesHiddenNow) {
        boolean hideRoutes = routesHiddenNow || previous.routesHidden() || current.routesHidden();
        Map<String, RunSideEffects.Key> before = keys(previous, hideRoutes);
        Map<String, RunSideEffects.Key> after = keys(current, hideRoutes);
        java.util.Set<String> currentOwners = new java.util.HashSet<>();
        after.values().forEach(key -> currentOwners.add(key.scope() + '\u0000' + key.owner()));
        List<RuntimeSideEffectSensorDto> sensors = new ArrayList<>();
        List<RuntimeSideEffectChangeDto> changes = new ArrayList<>();
        boolean partial = false;
        for (String id : SENSORS) {
            RunSideEffects.Sensor was = previous.sensor(id);
            RunSideEffects.Sensor is = current.sensor(id);
            String notCompared = notCompared(id, was, is);
            if (notCompared != null) {
                sensors.add(new RuntimeSideEffectSensorDto(
                        id, RuntimeSideEffectSensorDto.NOT_COMPARED, notCompared, 0, 0, 0));
                continue;
            }
            String startupNotCompared = startupNotCompared(id, was, is);
            boolean previousCut = was.omittedKeys() > 0;
            boolean currentCut = is.omittedKeys() > 0;
            int added = 0;
            int removed = 0;
            int notExercised = 0;
            int withheldAdded = 0;
            int withheldRemoved = 0;
            for (Map.Entry<String, RunSideEffects.Key> entry : after.entrySet()) {
                RunSideEffects.Key key = entry.getValue();
                if (!key.sensor().equals(id)
                        || before.containsKey(entry.getKey())
                        || (STARTUP.equals(key.scope()) && startupNotCompared != null)) {
                    continue;
                }
                if (previousCut) {
                    withheldAdded++;
                    continue;
                }
                added++;
                changes.add(change(key, RuntimeSideEffectChangeDto.ADDED));
            }
            for (Map.Entry<String, RunSideEffects.Key> entry : before.entrySet()) {
                RunSideEffects.Key key = entry.getValue();
                if (!key.sensor().equals(id)
                        || after.containsKey(entry.getKey())
                        || (STARTUP.equals(key.scope()) && startupNotCompared != null)) {
                    continue;
                }
                boolean ownerExercised = STARTUP.equals(key.scope())
                        || (!HIDDEN_ROUTE.equals(key.owner())
                                && (currentOwners.contains(key.scope() + '\u0000' + key.owner())
                                        || exercised.test(key.scope(), key.owner())));
                if (!ownerExercised) {
                    notExercised++;
                    changes.add(change(key, RuntimeSideEffectChangeDto.NOT_EXERCISED));
                } else if (currentCut) {
                    withheldRemoved++;
                } else {
                    removed++;
                    changes.add(change(key, RuntimeSideEffectChangeDto.REMOVED));
                }
            }
            List<String> notes = new ArrayList<>();
            if (previousCut || currentCut) {
                partial = true;
                notes.add(cutNote(previousCut, currentCut, withheldAdded, withheldRemoved));
            }
            if (startupNotCompared != null) {
                notes.add("its startup is not compared: " + startupNotCompared);
            }
            sensors.add(new RuntimeSideEffectSensorDto(
                    id,
                    previousCut || currentCut
                            ? RuntimeSideEffectSensorDto.PARTIAL
                            : RuntimeSideEffectSensorDto.COMPARED,
                    notes.isEmpty() ? null : capitalize(String.join("; ", notes)) + ".",
                    added,
                    removed,
                    notExercised));
        }
        changes.sort(Comparator.comparingInt((RuntimeSideEffectChangeDto change) -> order(change.change()))
                .thenComparingInt(change -> SENSORS.indexOf(change.sensor()))
                .thenComparing(Comparator.comparingLong(RuntimeSideEffectChangeDto::count)
                        .reversed())
                .thenComparing(RuntimeSideEffectChangeDto::owner)
                .thenComparing(RuntimeSideEffectChangeDto::target));
        int total = changes.size();
        List<String> limitations = new ArrayList<>(List.of(LIMITATION_VALUES, LIMITATION_WHOLE, LIMITATION_EXERCISED));
        if (hideRoutes) {
            limitations.add(LIMITATION_HIDDEN_ROUTES);
        }
        return new RuntimeSideEffectChangesDto(
                true,
                null,
                partial,
                sensors,
                total <= RuntimeRunComparisonDto.MAX_ROWS
                        ? changes
                        : changes.subList(0, RuntimeRunComparisonDto.MAX_ROWS),
                total,
                limitations);
    }

    /** Why sensor {@code id} is not compared at all, or {@code null}. */
    private static String notCompared(String id, RunSideEffects.Sensor was, RunSideEffects.Sensor is) {
        if (was == null) {
            return "Not compared: the previous run kept no " + id + " keys.";
        }
        if (is == null) {
            return "Not compared: this run keeps no " + id + " keys.";
        }
        if (was.reason() != null) {
            return "Not compared: " + id + " was not recording the whole previous run: " + was.reason() + ".";
        }
        if (is.reason() != null) {
            return "Not compared: " + id + " was not recording the whole of this run: " + is.reason() + ".";
        }
        return null;
    }

    /** Why sensor {@code id}'s startup keys are not compared, or {@code null}. */
    private static String startupNotCompared(String id, RunSideEffects.Sensor was, RunSideEffects.Sensor is) {
        if (was.startupReason() != null) {
            return "in the previous run, " + was.startupReason();
        }
        if (is.startupReason() != null) {
            return "in this run, " + is.startupReason();
        }
        return null;
    }

    private static String cutNote(boolean previousCut, boolean currentCut, int withheldAdded, int withheldRemoved) {
        List<String> parts = new ArrayList<>();
        if (previousCut) {
            parts.add("the previous run kept only part of its keys, so " + withheldAdded
                    + (withheldAdded == 1 ? " key" : " keys") + " not in it " + (withheldAdded == 1 ? "is" : "are")
                    + " not reported new");
        }
        if (currentCut) {
            parts.add("this run keeps only part of its keys, so " + withheldRemoved
                    + (withheldRemoved == 1 ? " key" : " keys") + " not in it " + (withheldRemoved == 1 ? "is" : "are")
                    + " not reported gone");
        }
        return String.join("; ", parts);
    }

    /** {@code sideEffects}' keys by identity, every route owner as one hidden route when {@code hideRoutes}. */
    private static Map<String, RunSideEffects.Key> keys(RunSideEffects sideEffects, boolean hideRoutes) {
        Map<String, RunSideEffects.Key> keys = new LinkedHashMap<>();
        for (RunSideEffects.Key key : sideEffects.keys()) {
            RunSideEffects.Key shown = hideRoutes && ROUTE.equals(key.scope())
                    ? new RunSideEffects.Key(
                            key.sensor(),
                            key.kind(),
                            key.target(),
                            key.scope(),
                            HIDDEN_ROUTE,
                            key.client(),
                            key.count())
                    : key;
            keys.merge(
                    shown.identity(),
                    shown,
                    (a, b) -> new RunSideEffects.Key(
                            a.sensor(),
                            a.kind(),
                            a.target(),
                            a.scope(),
                            a.owner(),
                            a.client() != null ? a.client() : b.client(),
                            a.count() + b.count()));
        }
        return keys;
    }

    private static RuntimeSideEffectChangeDto change(RunSideEffects.Key key, String change) {
        return new RuntimeSideEffectChangeDto(
                key.sensor(),
                key.kind(),
                key.target(),
                key.scope(),
                key.owner(),
                change,
                key.client(),
                key.count(),
                sentence(key, change));
    }

    /** The change as one sentence: {@code `GET /orders` now connects to `api.example.com:443` (JDK HttpClient).} */
    static String sentence(RunSideEffects.Key key, String change) {
        String owner = "`" + key.owner() + "`";
        String target = "`" + key.target() + "`";
        String what = what(key);
        String client = key.client() == null ? "" : " (" + key.client() + ")";
        return switch (change) {
            case RuntimeSideEffectChangeDto.ADDED -> owner + " now " + what + " " + target + client + suffix(key) + ".";
            case RuntimeSideEffectChangeDto.REMOVED ->
                owner + " no longer " + what + " " + target + client + suffix(key) + ".";
            default ->
                owner + " " + past(what) + " " + target + client + " in the previous run, and was not exercised"
                        + " in this run: not compared.";
        };
    }

    /** What a key does, in the third person: {@code connects to}, {@code reads}, {@code starts the process}. */
    private static String what(RunSideEffects.Key key) {
        return switch (key.kind()) {
            case "connect" -> "connects to";
            case "datagram" -> "sends datagrams to";
            case "lookup" -> "resolves";
            case "read" -> "reads";
            case "write" -> "writes";
            case "delete" -> "deletes";
            case "move from" -> "moves files from";
            case "move to" -> "moves files to";
            case "copy from" -> "copies files from";
            case "copy to" -> "copies files to";
            case "process" -> "starts the process";
            case "environment variable" -> "reads the environment variable";
            case "system property" -> "reads the system property";
            default -> key.kind();
        };
    }

    /** {@code connects to} as {@code connect to}. */
    private static String base(String what) {
        int space = what.indexOf(' ');
        String verb = space < 0 ? what : what.substring(0, space);
        String rest = space < 0 ? "" : what.substring(space);
        if (verb.endsWith("ies")) {
            verb = verb.substring(0, verb.length() - 3) + "y";
        } else if (verb.endsWith("es") && (verb.endsWith("sses") || verb.endsWith("ches") || verb.endsWith("xes"))) {
            verb = verb.substring(0, verb.length() - 2);
        } else if (verb.endsWith("s")) {
            verb = verb.substring(0, verb.length() - 1);
        }
        return verb + rest;
    }

    /** {@code connects to} as {@code connected to}. */
    private static String past(String what) {
        String base = base(what);
        int space = base.indexOf(' ');
        String verb = space < 0 ? base : base.substring(0, space);
        String rest = space < 0 ? "" : base.substring(space);
        String done =
                switch (verb) {
                    case "send" -> "sent";
                    case "read" -> "read";
                    case "write" -> "wrote";
                    case "copy" -> "copied";
                    default -> verb.endsWith("e") ? verb + "d" : verb + "ed";
                };
        return done + rest;
    }

    private static String suffix(RunSideEffects.Key key) {
        return "environment".equals(key.sensor()) ? " (its name only, never its value)" : "";
    }

    private static int order(String change) {
        return switch (change) {
            case RuntimeSideEffectChangeDto.ADDED -> 0;
            case RuntimeSideEffectChangeDto.REMOVED -> 1;
            default -> 2;
        };
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
