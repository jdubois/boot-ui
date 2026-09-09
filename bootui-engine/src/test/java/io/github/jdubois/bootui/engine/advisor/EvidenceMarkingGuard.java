package io.github.jdubois.bootui.engine.advisor;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Static call-graph guard shared by the advisor rule-catalogue tests.
 *
 * <p>Every advisor scores its panel from the applicability and coverage evidence that its rules record while
 * they evaluate. That evidence is opt-in: a rule which never tells its context what it looked at silently
 * contributes nothing, and the panel can end up unscored without any test failing. Reviewing that by hand
 * does not scale across the several hundred registered rules, so this guard walks the bytecode call graph
 * from each registered rule class and asserts that it can reach one of the evidence-marking methods.
 *
 * <p>The walk is transitive and confined to BootUI engine classes, because rules routinely delegate their
 * marking to shared helpers rather than touching the evidence holder directly.
 */
public final class EvidenceMarkingGuard {

    private static final String CONSTRUCTOR_NAME = "<init>";

    private static final String ENGINE_PACKAGE = "io.github.jdubois.bootui.engine";

    private static final JavaClasses ENGINE_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(ENGINE_PACKAGE);

    private EvidenceMarkingGuard() {}

    /**
     * Returns the simple names of the registered rules that can never reach an evidence-marking method,
     * ignoring the rules that are documented as deliberately application-wide.
     *
     * @param rules the registered rule instances, in catalogue order
     * @param evidenceType the advisor evidence holder that owns the marking methods
     * @param markingMethods the names of the marking methods declared on {@code evidenceType}
     * @param applicationWide rules that legitimately never mark, with a justification in the calling test
     */
    public static List<String> rulesThatNeverMarkEvidence(
            Collection<?> rules, String evidenceType, Set<String> markingMethods, Set<String> applicationWide) {
        List<String> unmarked = new ArrayList<>();
        for (Object rule : rules) {
            String name = rule.getClass().getSimpleName();
            if (applicationWide.contains(name)) continue;
            if (!reachesMarking(rule.getClass().getName(), evidenceType, markingMethods)) unmarked.add(name);
        }
        return unmarked;
    }

    /** Returns the registered rules named in {@code applicationWide} that do mark evidence after all. */
    public static List<String> allowedRulesThatDoMarkEvidence(
            Collection<?> rules, String evidenceType, Set<String> markingMethods, Set<String> applicationWide) {
        List<String> stale = new ArrayList<>();
        for (Object rule : rules) {
            String name = rule.getClass().getSimpleName();
            if (!applicationWide.contains(name)) continue;
            if (reachesMarking(rule.getClass().getName(), evidenceType, markingMethods)) stale.add(name);
        }
        return stale;
    }

    /** Returns the catalogue entries whose rule class is registered more than once. */
    public static List<String> duplicatedRules(Collection<?> rules) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (Object rule : rules) {
            String name = rule.getClass().getName();
            if (!seen.add(name)) duplicates.add(rule.getClass().getSimpleName());
        }
        return duplicates;
    }

    private static boolean reachesMarking(String ruleClassName, String evidenceType, Set<String> markingMethods) {
        Set<String> visited = new HashSet<>();
        Deque<JavaCodeUnit> pending = new ArrayDeque<>();
        enqueueClass(ruleClassName, visited, pending);
        while (!pending.isEmpty()) {
            JavaCodeUnit unit = pending.poll();
            for (var call : unit.getCallsFromSelf()) {
                JavaClass owner = call.getTargetOwner();
                String target = call.getName();
                if (owner.getName().equals(evidenceType) && markingMethods.contains(target)) return true;
                if (!owner.getPackageName().startsWith(ENGINE_PACKAGE)) continue;
                if (CONSTRUCTOR_NAME.equals(target) && (owner.isAnonymousClass() || owner.isLocalClass())) {
                    // Rules routinely mark from inside an anonymous predicate, so the whole instantiated
                    // type belongs to the rule's reachable behaviour. Named types are not pulled in
                    // wholesale, otherwise a super() call would reach every inherited helper.
                    enqueueClass(owner.getName(), visited, pending);
                    continue;
                }
                call.getTarget().resolveMember().ifPresent(resolved -> {
                    if (visited.add(resolved.getFullName())) pending.add((JavaCodeUnit) resolved);
                });
                enqueueLambdasOf(owner, target, visited, pending);
            }
        }
        return false;
    }

    /** Enqueues the synthetic bodies of the lambdas declared inside {@code methodName}. */
    private static void enqueueLambdasOf(
            JavaClass owner, String methodName, Set<String> visited, Deque<JavaCodeUnit> pending) {
        if (!ENGINE_CLASSES.contain(owner.getName())) return;
        String prefix = "lambda$" + methodName + "$";
        for (JavaCodeUnit unit : ENGINE_CLASSES.get(owner.getName()).getCodeUnits()) {
            if (unit.getName().startsWith(prefix) && visited.add(unit.getFullName())) pending.add(unit);
        }
    }

    private static void enqueueClass(String className, Set<String> visited, Deque<JavaCodeUnit> pending) {
        if (!visited.add("class:" + className)) return;
        if (!ENGINE_CLASSES.contain(className)) return;
        JavaClass type = ENGINE_CLASSES.get(className);
        for (JavaCodeUnit unit : type.getCodeUnits()) {
            if (visited.add(unit.getFullName())) pending.add(unit);
        }
    }
}
