package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.SqlTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the SQL Trace panel ({@code GET /bootui/api/sql-trace} plus {@code /clear} and
 * {@code /recording} actions). The Quarkus analogue of Spring's {@code SqlTraceController}: a thin transport
 * adapter over the shared engine {@link SqlTraceRecorder}, which owns the capped buffer, grouping/stats/N+1
 * assembly and report shaping, so the wire is byte-identical. Capture is the Quarkus-only Agroal datasource
 * wrap from {@code BootUiSqlTraceProducer}; bind values surface only when capture is on and value exposure is
 * not metadata-only. The recorder is resolved through an {@link Instance} so the panel renders unavailable
 * when no JDBC datasource is present (AGROAL gate). State-changing endpoints are gated by the panel-access
 * filter when the panel is read-only.
 */
@Path("/bootui/api/sql-trace")
public class SqlTraceResource {

    private static final String NOT_CONFIGURED = "SQL tracing is not configured";

    private final Instance<SqlTraceRecorder> recorder;
    private final QuarkusExposurePolicy exposure;

    @Inject
    public SqlTraceResource(Instance<SqlTraceRecorder> recorder, QuarkusExposurePolicy exposure) {
        this.recorder = recorder;
        this.exposure = exposure;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public SqlTraceReport trace() {
        SqlTraceRecorder rec = recorder.isResolvable() ? recorder.get() : null;
        return rec == null ? SqlTraceReport.unavailable(NOT_CONFIGURED) : report(rec);
    }

    @POST
    @Path("/clear")
    @Produces(MediaType.APPLICATION_JSON)
    public SqlTraceReport clear() {
        SqlTraceRecorder rec = recorder.isResolvable() ? recorder.get() : null;
        if (rec == null) {
            return SqlTraceReport.unavailable(NOT_CONFIGURED);
        }
        rec.clear();
        return report(rec);
    }

    @POST
    @Path("/recording")
    @Produces(MediaType.APPLICATION_JSON)
    public SqlTraceReport recording(SqlTraceRecordingRequest request) {
        SqlTraceRecorder rec = recorder.isResolvable() ? recorder.get() : null;
        if (rec == null) {
            return SqlTraceReport.unavailable(NOT_CONFIGURED);
        }
        boolean enabled = (request == null || request.enabled() == null) ? !rec.isRecording() : request.enabled();
        rec.setRecording(enabled);
        return report(rec);
    }

    private SqlTraceReport report(SqlTraceRecorder rec) {
        if (!rec.hasWrappedDataSource()) {
            return SqlTraceReport.unavailable(unavailableReason(rec));
        }
        boolean exposeParameters = rec.isCaptureParameters() && exposure.valueExposure() != ValueExposure.METADATA_ONLY;
        return rec.report(exposeParameters);
    }

    private String unavailableReason(SqlTraceRecorder rec) {
        if (!rec.isEnabled()) {
            return "SQL tracing is disabled (set bootui.sql-trace.enabled=true in a trusted local profile).";
        }
        return "No DataSource has been wrapped for tracing yet.";
    }
}
