const markdownExtension = /\.md$/i
const readmeFile = 'README.md'

export function toDocLink(file) {
  const normalized = file.replaceAll('\\', '/')

  if (normalized === readmeFile) {
    return '/'
  }

  if (normalized.endsWith(`/${readmeFile}`)) {
    return `/${normalized.slice(0, -readmeFile.length - 1).toLowerCase()}`
  }

  return `/${normalized.replace(markdownExtension, '').toLowerCase()}`
}

/** Docs kept in the repository but not published on the site (the validation rerun's data); see pagePatterns. */
export const repositoryOnlyDir = 'validation/'
const repositoryDocsUrl = 'https://github.com/jdubois/boot-ui/blob/v2/docs/'

export function isRepositoryOnly(file) {
  return file.replaceAll('\\', '/').startsWith(repositoryOnlyDir)
}

/**
 * A link from a published page to a repository-only file, resolved against the page's own file, as a GitHub URL;
 * null for any other link.
 */
export function toRepositoryLink(href, pageFile) {
  const match = href.match(/^([^#?]*)([#?].*)?$/)
  if (!match || !match[1] || /^[a-z][a-z0-9+.-]*:/i.test(href) || href.startsWith('/')) {
    return null
  }
  const [, pathname, hashAndQuery = ''] = match
  const segments = pageFile.replaceAll('\\', '/').split('/').slice(0, -1)
  for (const segment of decodeURI(pathname).split('/')) {
    if (segment === '..') segments.pop()
    else if (segment !== '.' && segment !== '') segments.push(segment)
  }
  const file = segments.join('/')
  return isRepositoryOnly(file) ? `${repositoryDocsUrl}${file}${hashAndQuery}` : null
}
