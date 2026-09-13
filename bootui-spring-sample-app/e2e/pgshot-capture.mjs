import {chromium} from '@playwright/test'
import fs from 'node:fs/promises'
import path from 'node:path'
import sharp from 'sharp'

const baseUrl = process.env.BASE_URL ?? 'http://localhost:8080'
const outDir = process.env.OUT_DIR ?? '/tmp/pgshot/out'
const viewport = {width: 1600, height: 900}

async function shot(page, name) {
  await page.evaluate(() => window.scrollTo(0, 0))
  const png = await page.screenshot({animations: 'disabled'})
  await sharp(png).webp({quality: 80}).toFile(path.join(outDir, `${name}.webp`))
  console.log('captured', name)
}

async function scrollWorkspaceTo(page, selector) {
  await page.evaluate((sel) => {
    const target = document.querySelector(sel)
    if (target) target.scrollIntoView({block: 'start', behavior: 'instant'})
  }, selector)
  await page.waitForTimeout(400)
}

async function main() {
  await fs.mkdir(outDir, {recursive: true})
  const browser = await chromium.launch()
  const context = await browser.newContext({
    viewport,
    deviceScaleFactor: 1,
    recordVideo: {dir: path.join(outDir, 'video'), size: viewport},
  })
  const page = await context.newPage()

  await page.goto(`${baseUrl}/bootui/#/postgresql`, {waitUntil: 'networkidle'})
  await page.getByRole('heading', {name: 'PostgreSQL', exact: true}).waitFor()
  await page.waitForTimeout(1200)
  await shot(page, '01-before-read')

  await page.getByRole('button', {name: 'Run PostgreSQL read'}).click()
  await page.getByText('database read', {exact: false}).first().waitFor({timeout: 60000})
  await page.waitForTimeout(1500)
  await shot(page, '02-after-read')

  const sections = [
    ['03-vital-signs', 'Vital signs'],
    ['04-notable-settings', 'Notable settings'],
    ['05-sessions', 'Sessions'],
    ['06-statement-ranking', 'Statement ranking'],
    ['07-index-usage', 'Index usage'],
    ['08-largest-relations', 'Largest relations'],
    ['09-autovacuum-health', 'Autovacuum health'],
    ['10-replication-wal', 'Replication, checkpoints and WAL'],
  ]
  for (const [name, text] of sections) {
    const locator = page.getByText(text, {exact: true}).first()
    if ((await locator.count()) === 0) {
      console.log('missing section', text)
      continue
    }
    await locator.evaluate((element) => element.scrollIntoView({block: 'start', behavior: 'instant'}))
    await page.waitForTimeout(700)
    const png = await page.screenshot({animations: 'disabled'})
    await sharp(png).webp({quality: 80}).toFile(path.join(outDir, `${name}.webp`))
    console.log('captured', name)
  }

  // A second read, so the "What changed since the previous read" delta is populated.
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.getByRole('button', {name: 'Run PostgreSQL read'}).click()
  await page.waitForTimeout(5000)
  const delta = page.getByText('What changed since the previous read', {exact: true}).first()
  if ((await delta.count()) > 0) {
    await delta.evaluate((element) => element.scrollIntoView({block: 'start', behavior: 'instant'}))
    await page.waitForTimeout(700)
    const png = await page.screenshot({animations: 'disabled'})
    await sharp(png).webp({quality: 80}).toFile(path.join(outDir, '11-delta.webp'))
    console.log('captured 11-delta')
  }

  // Slow, readable scroll to the bottom and back for the screencast.
  await page.evaluate(async () => {
    const step = 120
    const pause = () => new Promise((resolve) => setTimeout(resolve, 60))
    window.scrollTo(0, 0)
    await pause()
    while (window.scrollY + window.innerHeight < document.body.scrollHeight - 2) {
      window.scrollBy(0, step)
      await pause()
    }
    await new Promise((resolve) => setTimeout(resolve, 1200))
  })
  await page.waitForTimeout(1000)

  await context.close()
  await browser.close()
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
