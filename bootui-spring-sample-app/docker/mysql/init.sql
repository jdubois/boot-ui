-- Development-only diagnostics for the sample's non-root application account.
-- The image already grants this account access to bootui_sample.
GRANT SELECT ON performance_schema.global_status TO 'bootui'@'%';
GRANT SELECT ON performance_schema.setup_consumers TO 'bootui'@'%';
GRANT SELECT ON performance_schema.setup_objects TO 'bootui'@'%';
GRANT SELECT ON performance_schema.setup_instruments TO 'bootui'@'%';
GRANT SELECT ON performance_schema.threads TO 'bootui'@'%';
GRANT SELECT ON performance_schema.data_lock_waits TO 'bootui'@'%';
GRANT SELECT ON performance_schema.data_locks TO 'bootui'@'%';
GRANT SELECT ON performance_schema.metadata_locks TO 'bootui'@'%';
GRANT SELECT ON performance_schema.events_statements_summary_by_digest TO 'bootui'@'%';
GRANT SELECT ON performance_schema.table_io_waits_summary_by_table TO 'bootui'@'%';
GRANT SELECT ON performance_schema.table_io_waits_summary_by_index_usage TO 'bootui'@'%';
GRANT SELECT ON performance_schema.replication_connection_status TO 'bootui'@'%';
GRANT SELECT ON performance_schema.replication_applier_status TO 'bootui'@'%';
GRANT SELECT ON performance_schema.replication_applier_status_by_worker TO 'bootui'@'%';
GRANT SELECT ON performance_schema.replication_applier_status_by_coordinator TO 'bootui'@'%';
GRANT PROCESS ON *.* TO 'bootui'@'%';
