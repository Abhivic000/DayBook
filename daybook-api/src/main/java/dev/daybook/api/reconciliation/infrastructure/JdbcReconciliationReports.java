package dev.daybook.api.reconciliation.infrastructure;

import dev.daybook.api.reconciliation.application.ReconciliationReports;
import dev.daybook.api.reconciliation.domain.Check;
import dev.daybook.api.reconciliation.domain.ReconciliationReport;
import dev.daybook.api.reconciliation.domain.Violation;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcReconciliationReports implements ReconciliationReports {

  private final JdbcClient jdbc;

  JdbcReconciliationReports(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public void save(ReconciliationReport report) {
    jdbc.sql(
            """
            INSERT INTO reconciliation_runs
                (id, trigger, status, started_at, finished_at, checks_run, violation_count, error)
            VALUES (:id, :trigger, :status, :startedAt, :finishedAt, :checksRun, :violations, :error)
            """)
        .param("id", report.id())
        .param("trigger", report.trigger().name())
        .param("status", report.status().name())
        .param("startedAt", OffsetDateTime.ofInstant(report.startedAt(), ZoneOffset.UTC))
        .param("finishedAt", OffsetDateTime.ofInstant(report.finishedAt(), ZoneOffset.UTC))
        .param("checksRun", report.checksRun())
        .param("violations", report.violations().size())
        .param("error", report.error())
        .update();
    for (Violation violation : report.violations()) {
      jdbc.sql(
              """
              INSERT INTO reconciliation_violations
                  (run_id, check_name, severity, tenant_id, subject_id, detail)
              VALUES (:runId, :check, :severity, :tenantId, :subjectId, :detail)
              """)
          .param("runId", report.id())
          .param("check", violation.check().name())
          .param("severity", violation.severity().name())
          .param("tenantId", violation.tenantId())
          .param("subjectId", violation.subjectId())
          .param("detail", violation.detail())
          .update();
    }
  }

  @Override
  public List<RunSummary> recent(int limit) {
    return jdbc.sql(
            """
            SELECT id, trigger, status, started_at, finished_at, checks_run, violation_count, error
              FROM reconciliation_runs ORDER BY started_at DESC LIMIT :limit
            """)
        .param("limit", limit)
        .query(
            (rs, n) ->
                new RunSummary(
                    rs.getObject("id", UUID.class),
                    ReconciliationReport.Trigger.valueOf(rs.getString("trigger")),
                    ReconciliationReport.Status.valueOf(rs.getString("status")),
                    rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("finished_at", OffsetDateTime.class).toInstant(),
                    rs.getInt("checks_run"),
                    rs.getInt("violation_count"),
                    rs.getString("error")))
        .list();
  }

  @Override
  public Optional<ReconciliationReport> find(UUID runId) {
    List<Violation> violations =
        jdbc.sql(
                """
                SELECT check_name, tenant_id, subject_id, detail FROM reconciliation_violations
                 WHERE run_id = :runId ORDER BY id
                """)
            .param("runId", runId)
            .query(
                (rs, n) ->
                    new Violation(
                        Check.valueOf(rs.getString("check_name")),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("subject_id", UUID.class),
                        rs.getString("detail")))
            .list();
    return jdbc.sql(
            """
            SELECT id, trigger, status, started_at, finished_at, checks_run, error
              FROM reconciliation_runs WHERE id = :runId
            """)
        .param("runId", runId)
        .query(
            (rs, n) ->
                new ReconciliationReport(
                    rs.getObject("id", UUID.class),
                    ReconciliationReport.Trigger.valueOf(rs.getString("trigger")),
                    ReconciliationReport.Status.valueOf(rs.getString("status")),
                    rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("finished_at", OffsetDateTime.class).toInstant(),
                    rs.getInt("checks_run"),
                    violations,
                    rs.getString("error")))
        .optional();
  }

  @Override
  public int deleteOlderThan(Duration age) {
    return jdbc.sql(
            "DELETE FROM reconciliation_runs WHERE started_at < now() - make_interval(secs => :s)")
        .param("s", age.toSeconds())
        .update();
  }
}
