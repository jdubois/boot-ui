/**
 * Relaxed-binding-aware matching for configuration property names, mirroring the engine's
 * `io.github.jdubois.bootui.engine.support.RelaxedNames` so the browser narrows a name the same way the server does.
 *
 * Both sides are mapped onto one canonical form — lower case, with `_` and `-` treated as `.` — so the dotted,
 * kebab-case and UPPER_SNAKE_CASE spellings of one property all compare equal. The mapping is per character and
 * length preserving, so canonical matching is a superset of literal matching: nothing that matched before stops
 * matching, and a prefix stays a prefix.
 */
export function canonicalizeName(value) {
  if (typeof value !== 'string') return ''
  return value.toLowerCase().replace(/[_-]/g, '.')
}
