#!/usr/bin/env node
// The registered harness: every file under validation/ except the work area (.work/) and the first run's fixture
// (scoring/fixtures/). Its hash is recorded in each run's run.json and in the worksheet; the registration check
// compares the tree with the registration tag.
//
//   node validation/scoring/harness.mjs            prints the harness SHA-256

import {execFileSync} from 'node:child_process'
import {createHash} from 'node:crypto'
import {readFileSync, readdirSync} from 'node:fs'
import {dirname, join, relative, sep} from 'node:path'
import {fileURLToPath} from 'node:url'

export const validationHome = join(dirname(fileURLToPath(import.meta.url)), '..')
export const EXCLUDED = ['.work/', 'scoring/fixtures/']
const IGNORED_NAMES = ['.DS_Store']

export function harnessFiles(home = validationHome) {
  const files = []
  const walk = (dir) => {
    for (const entry of readdirSync(dir, {withFileTypes: true})) {
      const path = join(dir, entry.name)
      const rel = relative(home, path).split(sep).join('/') + (entry.isDirectory() ? '/' : '')
      if (IGNORED_NAMES.includes(entry.name) || EXCLUDED.some((prefix) => rel.startsWith(prefix))) continue
      if (entry.isDirectory()) walk(path)
      else if (entry.isFile()) files.push(rel)
    }
  }
  walk(home)
  return files.sort()
}

/** SHA-256 over the sorted paths and contents of the registered harness. */
export function harnessHash(home = validationHome) {
  const hash = createHash('sha256')
  for (const file of harnessFiles(home)) {
    hash.update(file).update('\0')
    hash
      .update(
        createHash('sha256')
          .update(readFileSync(join(home, file)))
          .digest('hex')
      )
      .update('\n')
  }
  return hash.digest('hex')
}

const git = (args) =>
  execFileSync('git', args, {cwd: validationHome, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore']}).trim()

/** The tag object SHA origin publishes for `ref`, or null when origin has no such tag; throws when it is unreachable. */
export function remoteTag(ref) {
  const line = execFileSync('git', ['ls-remote', '--tags', 'origin', `refs/tags/${ref}`], {
    cwd: validationHome,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'ignore'],
    timeout: 60_000
  })
    .split('\n')
    .find((l) => l.endsWith(`refs/tags/${ref}`))
  return line ? line.split(/\s+/)[0] : null
}

/**
 * Checks the harness against the registration tag: the local tag is annotated and is the one origin publishes (a tag
 * deleted and recreated locally on a later commit does not match), no tracked difference, no untracked file, and every
 * run's BootUI commit descends from the tag. `ref` 'none' skips the check (tests and smoke runs). `offline` accepts an
 * unreachable origin, and the result is marked `final: false`.
 */
export function checkRegistration(ref, bootuiCommits = [], {offline = false, lookupRemote = remoteTag} = {}) {
  if (ref === 'none') return {ref, sha: null, tag: null, final: false, problems: []}
  const problems = []
  let sha = null
  let tag = null
  try {
    sha = git(['rev-parse', '--verify', `${ref}^{commit}`])
    tag = git(['rev-parse', '--verify', `refs/tags/${ref}`])
  } catch {
    return {
      ref,
      sha,
      tag,
      final: false,
      problems: [`the registration tag ${ref} does not exist: create it on the commit that registered the protocol`]
    }
  }
  if (git(['cat-file', '-t', tag]) !== 'tag')
    problems.push(`${ref} is a lightweight tag; the registration is an annotated tag`)
  let final = true
  try {
    const published = lookupRemote(ref)
    if (published === null) problems.push(`origin has no tag ${ref}: push the registration tag`)
    else if (published !== tag) {
      problems.push(
        `the local tag ${ref} (${tag.slice(0, 9)}) is not the one origin publishes (${published.slice(0, 9)})`
      )
    }
  } catch {
    if (offline) final = false
    else
      problems.push(
        `origin is unreachable, so ${ref} cannot be checked against it; pass --offline for a score marked not final`
      )
  }
  const pathspec = ['--', '.', ...EXCLUDED.map((p) => `:(exclude)${p}`)]
  try {
    git(['diff', '--quiet', sha, ...pathspec])
  } catch {
    problems.push(`the harness differs from ${ref} (${sha.slice(0, 9)}): the protocol is not the registered one`)
  }
  if (git(['ls-files', '--others', '--exclude-standard', ...pathspec])) {
    problems.push(`the harness has files ${ref} does not register`)
  }
  for (const commit of new Set(bootuiCommits.filter(Boolean))) {
    try {
      git(['merge-base', '--is-ancestor', sha, commit])
    } catch {
      problems.push(`BootUI commit ${commit.slice(0, 9)} does not descend from ${ref}`)
    }
  }
  return {ref, sha, tag, final, problems}
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) console.log(harnessHash())
