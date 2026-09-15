// @ts-check
import {expect, test} from './fixtures.js'

// These checks run with a Docker profile: the full PostgreSQL stack or lightweight MySQL + Redis.
// They assert the runtime uses the selected database and Redis instead of
// the Docker-free `dev` defaults (H2, an in-memory cache, no KafkaTemplate, disabled Spring AI), so a
// green run actually proves the Docker-based configuration works rather than passing in a broadly
// compatible mode.
const profiles = (process.env.BOOTUI_SAMPLE_PROFILES || '').split(',').map((profile) => profile.trim())
const mysql = profiles.includes('docker-mysql')
const dockerProfile = mysql ? 'docker-mysql' : 'docker'
const database = mysql ? 'MySQL' : 'PostgreSQL'
const panelId = mysql ? 'mysql' : 'postgresql'

test.describe('Docker profile smoke checks', () => {
  test.skip(!profiles.includes(dockerProfile), 'Only runs with a Docker sample profile')

  test(`reports the ${dockerProfile} profile as active`, async ({request}) => {
    const response = await request.get('/bootui/api/overview')
    expect(response.ok()).toBeTruthy()
    const overview = await response.json()
    expect(overview.activeProfiles).toContain(dockerProfile)
  })

  test(`uses a ${database} datasource`, async ({request}) => {
    const response = await request.get('/actuator/health')
    expect(response.ok()).toBeTruthy()
    const health = await response.json()
    expect(health.components?.db?.details?.database).toBe(database)
  })

  test(`reads ${database} statement statistics`, async ({request, page, openView}) => {
    const products = await request.get('/api/sample/products')
    expect(products.ok()).toBeTruthy()

    await openView(panelId, database)
    const readResponse = page.waitForResponse(
      (response) => response.url().endsWith(`/bootui/api/${panelId}/read`) && response.request().method() === 'POST'
    )
    await page.getByRole('button', {name: `Run ${database} read`, exact: true}).click()
    const response = await readResponse
    expect(response.ok()).toBeTruthy()
    const report = await response.json()
    const sources = mysql ? report.dataSources : report.databases
    expect(sources.length).toBeGreaterThan(0)
    for (const source of sources) {
      const statements = source.sections.find((section) => section.id === 'statements')
      expect(statements?.status).toBe('AVAILABLE')
      expect(statements?.reason).toBeNull()
      expect(source.statements.length).toBeGreaterThan(0)
    }
  })

  test('uses a Redis-backed Spring cache', async ({request}) => {
    const response = await request.get('/bootui/api/cache')
    expect(response.ok()).toBeTruthy()
    const cache = await response.json()
    const managerTypes = (cache.managers || []).map((manager) => manager.type)
    expect(managerTypes.some((type) => /Redis/i.test(type ?? ''))).toBeTruthy()
  })

  test(`serves ${database}-backed sample products`, async ({request}) => {
    const response = await request.get('/api/sample/products')
    expect(response.ok()).toBeTruthy()
    const products = await response.json()
    expect(Array.isArray(products)).toBeTruthy()
    expect(products.length).toBeGreaterThan(0)
  })

  test(
    mysql ? 'keeps Ollama disabled in the MySQL profile' : 'answers a chat prompt through Ollama',
    async ({request}) => {
      const response = await request.post('/api/chat', {
        data: {message: 'Reply with the single word: pong.'},
        timeout: 120_000
      })
      if (mysql) {
        expect(response.status()).toBe(503)
        expect((await response.json()).error).toContain('ChatClient')
        return
      }
      expect(response.ok(), `chat request failed: ${response.status()} ${await response.text()}`).toBeTruthy()
      const body = await response.json()
      expect(typeof body.reply).toBe('string')
      expect(body.reply.length).toBeGreaterThan(0)
    }
  )

  test(
    mysql ? 'keeps Kafka disabled in the MySQL profile' : 'captures a real Kafka produce/consume round trip',
    async ({request}) => {
      if (mysql) {
        const response = await request.get('/bootui/api/kafka')
        expect(response.ok()).toBeTruthy()
        expect((await response.json()).available).toBe(false)
        return
      }
      const sendResponse = await request.get('/api/sample/send-kafka-message')
      expect(sendResponse.ok()).toBeTruthy()

      const response = await request.get('/bootui/api/kafka')
      expect(response.ok()).toBeTruthy()
      const kafka = await response.json()
      expect(kafka.available).toBeTruthy()
      const topics = (kafka.messages || []).map((message) => message.topic)
      expect(topics).toContain('orders.created')
      const directions = (kafka.messages || []).map((message) => message.direction)
      expect(directions).toContain('PRODUCE')
      expect(directions).toContain('CONSUME')
    }
  )
})
