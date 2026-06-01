// Copies shared documentation screenshots from the repository's docs/images
// folder into the VitePress public/ directory so the website reuses a single
// source of truth instead of duplicating binary assets in version control.
import {cp, mkdir, rm} from 'node:fs/promises'
import {dirname, resolve} from 'node:path'
import {fileURLToPath} from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = resolve(here, '..', '..')
const source = resolve(repoRoot, 'docs', 'images')
const destination = resolve(here, '..', 'public', 'images')

await rm(destination, {recursive: true, force: true})
await mkdir(destination, {recursive: true})
await cp(source, destination, {recursive: true})

console.log(`Copied screenshots from ${source} to ${destination}`)
