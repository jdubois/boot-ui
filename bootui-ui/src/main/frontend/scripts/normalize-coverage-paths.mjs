// Rewrites the `SF:` paths in the generated LCOV report so they are relative to
// the `bootui-ui` Maven module base directory instead of the frontend project
// directory. Vitest emits paths like `SF:src/App.vue`, but SonarQube analyzes the
// `bootui-ui` module (base `bootui-ui/`) and resolves LCOV paths against that base.
// Without this prefix the coverage would silently fail to map onto the indexed
// `src/main/frontend/src/**` sources. Run automatically via the `posttest` hook.
import {existsSync, readFileSync, writeFileSync} from 'node:fs'

const LCOV_PATH = 'coverage/lcov.info'
const MODULE_PREFIX = 'src/main/frontend/'

if (!existsSync(LCOV_PATH)) {
  process.exit(0)
}

const original = readFileSync(LCOV_PATH, 'utf8')
const normalized = original
  .split('\n')
  .map((line) => {
    if (!line.startsWith('SF:')) {
      return line
    }
    const file = line.slice(3)
    // Idempotent: skip lines that are already module-relative.
    if (file.startsWith(MODULE_PREFIX)) {
      return line
    }
    return `SF:${MODULE_PREFIX}${file}`
  })
  .join('\n')

if (normalized !== original) {
  writeFileSync(LCOV_PATH, normalized)
}
