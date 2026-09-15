// Deterministic synthetic observations. No live credentials, SQL samples, application rows,
// lock payloads, server addresses or replication configuration belong in this fixture.
export const mysqlSections = [
  ['vital-signs', 'Vital signs', 'SERVER'],
  ['sessions', 'Sessions', 'DEFAULT_SCHEMA_ASSOCIATED'],
  ['statements', 'Statements', 'DEFAULT_SCHEMA_ASSOCIATED'],
  ['indexes', 'Indexes', 'SELECTED_SCHEMA'],
  ['tables', 'Tables', 'SELECTED_SCHEMA'],
  ['innodb', 'InnoDB', 'SERVER'],
  ['replication', 'Replication', 'SERVER'],
  ['settings', 'Settings', 'SERVER']
].map(([id, title, scope]) => ({
  id,
  title,
  scope,
  status: 'AVAILABLE',
  reason: null,
  hint: null,
  rowCount: 0,
  truncated: false
}))

export function mysqlDataSource(overrides = {}) {
  const source = {
    name: 'orders',
    schemaName: 'bootui_shop',
    serverVersion: 'MySQL 8.4.6',
    serverFlavor: 'ORACLE_MYSQL',
    account: 'shop_app',
    status: 'READ',
    message: null,
    readStartedAt: 1700000000000,
    readAt: 1700000000800,
    sections: structuredClone(mysqlSections),
    vitalSigns: [
      ['Threads_connected', 'Connected threads', '18', 'count'],
      ['Threads_running', 'Running threads', '3', 'count'],
      ['Max_used_connections', 'Peak connected threads', '28', 'count'],
      ['Uptime', 'Server uptime', '259200', 'seconds'],
      ['Innodb_buffer_pool_read_requests', 'Buffer-pool read requests', '42890531', 'count'],
      ['Innodb_buffer_pool_reads', 'Buffer-pool disk reads', '12640', 'count'],
      ['Innodb_row_lock_waits', 'Row-lock waits', '24', 'count'],
      ['Innodb_log_waits', 'Redo log waits', '0', 'count']
    ].map(([id, label, value, unit]) => ({
      id,
      label,
      value,
      unit,
      scope: 'SERVER',
      source: 'performance_schema.global_status'
    })),
    capabilities: [
      ['sessions', 'performance_schema.threads', 'DEFAULT_SCHEMA_ASSOCIATED'],
      ['statements', 'performance_schema.events_statements_summary_by_digest', 'DEFAULT_SCHEMA_ASSOCIATED'],
      ['tables', 'information_schema.tables', 'SELECTED_SCHEMA'],
      ['replication', 'performance_schema.replication_connection_status', 'SERVER']
    ].map(([id, source, scope]) => ({
      id,
      source,
      scope,
      readability: 'READABLE',
      collection: 'ENABLED',
      timing: 'ENABLED',
      reason: null
    })),
    sessions: [
      {
        threadId: '81',
        connectionId: '42',
        user: 'shop_app',
        host: '[masked]',
        database: 'bootui_shop',
        command: 'Query',
        state: 'executing',
        stateSeconds: 0.4,
        transactionState: 'RUNNING',
        transactionAgeSeconds: 1.8,
        rowsLocked: '1',
        rowsModified: '1'
      },
      {
        threadId: '82',
        connectionId: '43',
        user: 'shop_app',
        host: '[masked]',
        database: 'bootui_shop',
        command: 'Sleep',
        state: '',
        stateSeconds: 8.2,
        transactionState: 'RUNNING',
        transactionAgeSeconds: 12.4,
        rowsLocked: '2',
        rowsModified: '0'
      },
      {
        threadId: '83',
        connectionId: '44',
        user: 'shop_app',
        host: '[masked]',
        database: 'bootui_shop',
        command: 'Query',
        state: 'Waiting for table metadata lock',
        stateSeconds: 1.2,
        transactionState: null,
        transactionAgeSeconds: null,
        rowsLocked: null,
        rowsModified: null
      }
    ],
    lockWaits: [
      {
        kind: 'ROW',
        requestingThreadId: '81',
        blockingThreadId: '82',
        schemaName: 'bootui_shop',
        objectName: 'orders',
        indexName: 'PRIMARY',
        requestedMode: 'X,REC_NOT_GAP',
        blockingMode: 'X,REC_NOT_GAP',
        status: 'WAITING'
      },
      {
        kind: 'METADATA',
        requestingThreadId: '83',
        blockingThreadId: null,
        schemaName: 'bootui_shop',
        objectName: 'inventory',
        indexName: null,
        requestedMode: 'EXCLUSIVE',
        blockingMode: null,
        status: 'PENDING'
      }
    ],
    statements: [
      [
        'orders-status',
        'SELECT `status` , COUNT ( * ) FROM `orders` GROUP BY `status`',
        '8421',
        28460,
        3.38,
        84.6,
        '25899432',
        '42105'
      ],
      [
        'order-items',
        'SELECT * FROM `order_items` WHERE `order_id` = ?',
        '63105',
        12504,
        0.2,
        15.2,
        '315525',
        '315525'
      ],
      [
        'inventory-read',
        'SELECT `available` FROM `inventory` WHERE `sku` = ?',
        '42108',
        8142,
        0.19,
        9.3,
        '42108',
        '42108'
      ],
      ['order-update', 'UPDATE `orders` SET `status` = ? WHERE `id` = ?', '2184', 6945, 3.18, 121.4, '2184', '0'],
      [
        'customer-orders',
        'SELECT * FROM `orders` WHERE `customer_id` = ? ORDER BY `created_at` DESC LIMIT ?',
        '9520',
        5038,
        0.53,
        18.9,
        '142800',
        '95200'
      ],
      [
        'inventory-update',
        'UPDATE `inventory` SET `available` = ? WHERE `sku` = ?',
        '2095',
        1948,
        0.93,
        23.1,
        '2095',
        '0'
      ]
    ].map(([digest, digestText, calls, totalTimeMs, averageTimeMs, maxTimeMs, rowsExamined, rowsSent]) => ({
      digest,
      digestText,
      calls,
      totalTimeMs,
      averageTimeMs,
      maxTimeMs,
      rowsExamined,
      rowsSent,
      schemaName: 'bootui_shop',
      errors: '0',
      temporaryTables: digest === 'orders-status' ? '8421' : '0',
      temporaryDiskTables: '0'
    })),
    indexes: [
      {
        schemaName: 'bootui_shop',
        tableName: 'orders',
        indexName: 'idx_orders_customer_created',
        readOperations: '142800',
        writeOperations: '0',
        fetchOperations: '142800',
        insertOperations: '0',
        updateOperations: '0',
        deleteOperations: '0',
        totalTimeMs: 5038
      },
      {
        schemaName: 'bootui_shop',
        tableName: 'orders',
        indexName: 'PRIMARY',
        readOperations: '42108',
        writeOperations: '2184',
        fetchOperations: '42108',
        insertOperations: '0',
        updateOperations: '2184',
        deleteOperations: '0',
        totalTimeMs: 8142
      },
      {
        schemaName: 'bootui_shop',
        tableName: 'orders',
        indexName: null,
        readOperations: '0',
        writeOperations: '2095',
        fetchOperations: '0',
        insertOperations: '2095',
        updateOperations: '0',
        deleteOperations: '0',
        totalTimeMs: 1948
      }
    ],
    tables: [
      {
        schemaName: 'bootui_shop',
        tableName: 'orders',
        engine: 'InnoDB',
        estimatedRows: '48210',
        dataBytes: '12582912',
        indexBytes: '8388608',
        readOperations: '184908',
        writeOperations: '4279',
        totalTimeMs: 32140
      },
      {
        schemaName: 'bootui_shop',
        tableName: 'inventory',
        engine: 'InnoDB',
        estimatedRows: '2400',
        dataBytes: '524288',
        indexBytes: '262144',
        readOperations: '42108',
        writeOperations: '2095',
        totalTimeMs: 10090
      }
    ],
    innodb: [
      ['buffer_pool_pages_dirty', 'Dirty buffer-pool pages', '182', 'pages'],
      ['buffer_pool_pages_free', 'Free buffer-pool pages', '4096', 'pages'],
      ['log_waits', 'Redo log waits', '0', 'waits'],
      ['trx_rseg_history_len', 'History-list length', '42', 'entries']
    ].map(([id, label, value, unit]) => ({
      id,
      label,
      value,
      unit,
      scope: 'SERVER',
      source: id === 'trx_rseg_history_len' ? 'information_schema.innodb_metrics' : 'performance_schema.global_status'
    })),
    replication: [
      {
        channel: 'warehouse',
        receiverState: 'ON',
        applierState: 'ON',
        workerCount: '4',
        errorCount: '0',
        lastErrorNumber: 0
      }
    ],
    settings: [
      {name: 'max_connections', value: '200', scope: 'GLOBAL', source: 'global-system-variables'},
      {
        name: 'transaction_isolation',
        value: 'REPEATABLE-READ',
        scope: 'GLOBAL',
        source: 'global-system-variables'
      },
      {
        name: 'innodb_buffer_pool_size',
        value: '134217728',
        scope: 'GLOBAL',
        source: 'global-system-variables'
      },
      {name: 'performance_schema', value: 'ON', scope: 'GLOBAL', source: 'global-system-variables'}
    ],
    changes: [
      {
        metric: 'row_lock_waits',
        scope: 'SERVER',
        unit: 'waits',
        delta: '2',
        previousReadAt: 1699999970000,
        readAt: 1700000000800,
        qualification: 'Same server and compatible counter observations; not an application-only rate.'
      }
    ],
    truncated: false,
    ...overrides
  }
  if (!overrides.sections) {
    source.sections = source.sections.map((part) => ({
      ...part,
      rowCount:
        part.id === 'sessions'
          ? source.sessions.length + source.lockWaits.length
          : (part.id === 'vital-signs' ? source.vitalSigns : source[part.id])?.length || 0
    }))
  }
  return source
}

export function mysqlReport(overrides = {}) {
  return {
    localOnly: true,
    disclaimer:
      'Synthetic example. Server-wide counters include every client, not only this application. Collection is explicit and bounded; no application rows or raw SQL are read.',
    status: 'READ',
    message: 'MySQL read completed.',
    readStartedAt: 1700000000000,
    readAt: 1700000000800,
    dataSourcesRead: 1,
    dataSources: [mysqlDataSource()],
    diagnostics: [],
    limitations: [],
    truncated: false,
    ...overrides
  }
}
