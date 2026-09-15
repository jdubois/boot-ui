// Explicit projection of the MySQL DTOs. Never enumerate arbitrary response properties:
// sampled SQL, lock payloads and server configuration are not displayable fields.
const text = (key, label, extra = {}) => ({key, label, ...extra})
const counter = (key, label) => ({key, label, type: 'counter'})
const duration = (key, label, type = 'ms') => ({key, label, type})

export const mysqlColumns = {
  sessions: [
    counter('threadId', 'Thread'),
    counter('connectionId', 'Connection'),
    text('command', 'Command'),
    text('state', 'State', {wrap: true}),
    duration('stateSeconds', 'State age', 's'),
    text('transactionState', 'Transaction'),
    duration('transactionAgeSeconds', 'Transaction age', 's'),
    counter('rowsLocked', 'Rows locked'),
    counter('rowsModified', 'Rows modified'),
    text('user', 'User'),
    text('host', 'Host'),
    text('database', 'Default schema')
  ],
  rowLocks: [
    counter('requestingThreadId', 'Waiting thread'),
    counter('blockingThreadId', 'Blocking thread'),
    text('schemaName', 'Schema'),
    text('objectName', 'Object'),
    text('indexName', 'Index'),
    text('requestedMode', 'Requested mode'),
    text('blockingMode', 'Blocking mode'),
    text('status', 'Status')
  ],
  metadataLocks: [
    counter('requestingThreadId', 'Waiting thread'),
    text('schemaName', 'Schema'),
    text('objectName', 'Object'),
    text('requestedMode', 'Requested mode'),
    text('status', 'Status')
  ],
  statements: [
    text('digestText', 'Normalized statement', {wrap: true}),
    counter('calls', 'Calls'),
    duration('totalTimeMs', 'Total time'),
    duration('averageTimeMs', 'Average'),
    duration('maxTimeMs', 'Maximum'),
    counter('rowsExamined', 'Rows examined'),
    counter('rowsSent', 'Rows sent'),
    counter('errors', 'Errors'),
    counter('temporaryTables', 'Temporary tables'),
    counter('temporaryDiskTables', 'Disk temporary tables'),
    text('schemaName', 'Default schema'),
    text('digest', 'Digest')
  ],
  indexes: [
    text('schemaName', 'Schema'),
    text('tableName', 'Table'),
    text('indexName', 'Index', {emptyText: 'No-index / insert bucket'}),
    counter('readOperations', 'Read operations'),
    counter('writeOperations', 'Write operations'),
    counter('fetchOperations', 'Fetch'),
    counter('insertOperations', 'Insert'),
    counter('updateOperations', 'Update'),
    counter('deleteOperations', 'Delete'),
    duration('totalTimeMs', 'Total time')
  ],
  tables: [
    text('schemaName', 'Schema'),
    text('tableName', 'Table'),
    text('engine', 'Engine'),
    counter('estimatedRows', 'Estimated rows'),
    counter('dataBytes', 'Data bytes (estimate)'),
    counter('indexBytes', 'Index bytes (estimate)'),
    counter('readOperations', 'Read operations'),
    counter('writeOperations', 'Write operations'),
    duration('totalTimeMs', 'Total time')
  ],
  innodb: [
    text('label', 'Metric'),
    counter('value', 'Value'),
    text('unit', 'Unit'),
    text('scope', 'Scope'),
    text('source', 'Source', {wrap: true})
  ],
  replication: [
    text('channel', 'Channel', {emptyText: 'Default channel'}),
    text('receiverState', 'Receiver'),
    text('applierState', 'Applier'),
    counter('workerCount', 'Workers'),
    counter('errorCount', 'Workers with errors'),
    counter('lastErrorNumber', 'Observed error number')
  ],
  settings: [
    text('name', 'Setting'),
    text('value', 'Value', {wrap: true}),
    text('scope', 'Scope'),
    text('source', 'Source', {wrap: true})
  ],
  capabilities: [
    text('source', 'Source', {wrap: true}),
    text('scope', 'Scope'),
    text('readability', 'Readability'),
    text('collection', 'Collection'),
    text('timing', 'Timing'),
    text('reason', 'Qualification', {wrap: true})
  ]
}
