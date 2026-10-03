import assert from 'node:assert/strict'
import test from 'node:test'

import {parseRuleCatalog} from './rule-catalog.js'

test('extracts severities from leading bold severity summaries', () => {
  const rules = parseRuleCatalog(`## Runtime

### QA-PROD-002 - Production schema creation, alteration or dropping

**CRITICAL** for \`drop\` or \`drop-and-create\`; **HIGH** for \`create\` or \`update\`.
`)

  assert.equal(rules[0].severity, 'CRITICAL')
})

test('keeps retired rules severityless', () => {
  const rules = parseRuleCatalog(`## Mapping

### HIB-MAP-012 - SINGLE_TABLE inheritance should declare @DiscriminatorColumn

**Retired.** Jakarta Persistence defines a valid implicit discriminator column.
`)

  assert.equal(rules[0].severity, null)
})
