package io.github.jdubois.bootui.quarkus.liquibase;

import io.github.jdubois.bootui.core.dto.LiquibaseActionResult;
import io.github.jdubois.bootui.core.dto.LiquibaseChangeSetDto;
import io.github.jdubois.bootui.spi.LiquibaseDatabaseSnapshot;
import io.github.jdubois.bootui.spi.LiquibaseProvider;
import io.github.jdubois.bootui.spi.LiquibaseTarget;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.liquibase.LiquibaseFactory;
import io.quarkus.liquibase.runtime.LiquibaseFactoryUtil;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.RanChangeSet;

/**
 * Quarkus {@link LiquibaseProvider}: the Liquibase-specific seam behind the shared engine
 * {@code LiquibaseService}, backed by the {@code quarkus-liquibase} extension's {@link LiquibaseFactory} beans.
 * It is the <strong>sole</strong> importer of the {@code io.quarkus.liquibase.*}/{@code liquibase.*} API on the
 * Quarkus side; the engine stays liquibase-free.
 *
 * <p>This class is constructed only by {@link io.github.jdubois.bootui.quarkus.BootUiLiquibaseProducer}, which
 * the deployment processor excludes from bean discovery unless the {@code LIQUIBASE} capability is present
 * (R2), so the Liquibase types it references are never linked in a Liquibase-absent application.</p>
 *
 * <p>Discovery uses {@link LiquibaseFactoryUtil#getActiveLiquibaseFactories()} (which resolves the per-named-
 * datasource CDI qualifiers, so {@code @LiquibaseDataSource}-named datasources are included and inactive ones
 * skipped) rather than a plain {@code Instance<LiquibaseFactory>} that would see only the {@code @Default}
 * factory. Each read and the update primitive open a {@link Liquibase} in a try-with-resources; as of Quarkus
 * 3.33 {@link LiquibaseFactory#createLiquibase()} applies and resets the per-datasource Liquibase system
 * properties internally (the former caller-managed {@code ResettableSystemProperties} helper is now private),
 * exactly as Quarkus' own {@code LiquibaseRecorder} does, and the underlying JDBC connection (closed by
 * {@code Liquibase.close()}) never leaks.</p>
 */
public class QuarkusLiquibaseProvider implements LiquibaseProvider {

    @Override
    public boolean available() {
        // The Quarkus adapter only constructs this provider when the LIQUIBASE capability is present (the
        // producer is excluded from bean discovery otherwise), so the Liquibase integration is present here.
        return true;
    }

    @Override
    public List<LiquibaseDatabaseSnapshot> databases() {
        List<LiquibaseDatabaseSnapshot> databases = new ArrayList<>();
        for (InstanceHandle<LiquibaseFactory> handle : LiquibaseFactoryUtil.getActiveLiquibaseFactories()) {
            LiquibaseFactory factory = handle.get();
            databases.add(new LiquibaseDatabaseSnapshot(
                    factory.getDataSourceName(),
                    readAppliedChangeSets(factory),
                    readPendingChangeSets(factory),
                    updateDisabledReason(factory)));
        }
        return databases;
    }

    @Override
    public List<LiquibaseTarget> targets() {
        List<LiquibaseTarget> targets = new ArrayList<>();
        for (InstanceHandle<LiquibaseFactory> handle : LiquibaseFactoryUtil.getActiveLiquibaseFactories()) {
            LiquibaseFactory factory = handle.get();
            targets.add(new LiquibaseTarget(factory.getDataSourceName(), updateDisabledReason(factory)));
        }
        return targets;
    }

    @Override
    public LiquibaseActionResult update(String name) throws Exception {
        LiquibaseFactory factory = LiquibaseFactoryUtil.getActiveLiquibaseFactories().stream()
                .map(InstanceHandle::get)
                .filter(candidate -> candidate.getDataSourceName().equals(name))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalStateException("No Liquibase factory named '" + name + "' is available."));
        try (Liquibase liquibase = factory.createLiquibase()) {
            int before = liquibase
                    .listUnrunChangeSets(factory.createContexts(), factory.createLabels())
                    .size();
            liquibase.update(factory.createContexts(), factory.createLabels());
            int after = liquibase
                    .listUnrunChangeSets(factory.createContexts(), factory.createLabels())
                    .size();
            int applied = Math.max(0, before - after);
            String message = applied == 0
                    ? "Liquibase database is already up to date."
                    : "Liquibase applied " + applied + " change set(s).";
            return new LiquibaseActionResult("success", message, name, before, after, applied, List.of());
        }
    }

    private List<LiquibaseChangeSetDto> readAppliedChangeSets(LiquibaseFactory factory) {
        try (Liquibase liquibase = factory.createLiquibase()) {
            List<LiquibaseChangeSetDto> changeSets = new ArrayList<>();
            for (RanChangeSet ranChangeSet : liquibase.getDatabase().getRanChangeSetList()) {
                changeSets.add(toChangeSetDto(ranChangeSet));
            }
            return changeSets;
        } catch (Exception ex) {
            // Fail closed for this datasource: an inaccessible history table yields an empty list.
            return List.of();
        }
    }

    private List<LiquibaseChangeSetDto> readPendingChangeSets(LiquibaseFactory factory) {
        try (Liquibase liquibase = factory.createLiquibase()) {
            List<LiquibaseChangeSetDto> changeSets = new ArrayList<>();
            for (ChangeSet changeSet :
                    liquibase.listUnrunChangeSets(factory.createContexts(), factory.createLabels())) {
                changeSets.add(toPendingChangeSetDto(changeSet));
            }
            return changeSets;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String updateDisabledReason(LiquibaseFactory factory) {
        // A Quarkus LiquibaseFactory is only produced for a configured datasource with a change log, so the
        // update action is always enabled here (mirroring the Spring reasons, which only fire when a
        // SpringLiquibase bean has no DataSource / no change log — states Quarkus cannot reach).
        return null;
    }

    private LiquibaseChangeSetDto toChangeSetDto(RanChangeSet changeSet) {
        return new LiquibaseChangeSetDto(
                changeSet.getId(),
                changeSet.getAuthor(),
                changeSet.getChangeLog(),
                changeSet.getDescription(),
                changeSet.getComments(),
                changeSet.getExecType() == null ? null : changeSet.getExecType().name(),
                nullSafeToInstant(changeSet.getDateExecuted()),
                changeSet.getOrderExecuted(),
                changeSet.getLastCheckSum() == null
                        ? null
                        : changeSet.getLastCheckSum().toString(),
                changeSet.getTag(),
                changeSet.getDeploymentId(),
                changeSet.getContextExpression() == null
                        ? List.of()
                        : List.copyOf(changeSet.getContextExpression().getContexts()),
                changeSet.getLabels() == null
                        ? List.of()
                        : List.copyOf(changeSet.getLabels().getLabels()));
    }

    private LiquibaseChangeSetDto toPendingChangeSetDto(ChangeSet changeSet) {
        return new LiquibaseChangeSetDto(
                changeSet.getId(),
                changeSet.getAuthor(),
                changeSet.getFilePath(),
                changeSet.getDescription(),
                changeSet.getComments(),
                "PENDING",
                null,
                null,
                null,
                null,
                null,
                changeSet.getContextFilter() == null
                        ? List.of()
                        : List.copyOf(changeSet.getContextFilter().getContexts()),
                changeSet.getLabels() == null
                        ? List.of()
                        : List.copyOf(changeSet.getLabels().getLabels()));
    }

    private String nullSafeToInstant(java.util.Date date) {
        return date == null ? null : Instant.ofEpochMilli(date.getTime()).toString();
    }
}
