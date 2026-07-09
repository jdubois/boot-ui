// @ts-check
import {expect, test} from '@playwright/test'

// These checks only run when the sample app was booted with the full Docker stack (the `docker`
// Spring profile, set via BOOTUI_SAMPLE_PROFILES=docker by the weekly "Docker configuration"
// workflow). They assert the runtime genuinely uses PostgreSQL, Redis, Kafka, and Ollama instead of
// the Docker-free `dev` defaults (H2, an in-memory cache, no KafkaTemplate, disabled Spring AI), so a
// green run actually proves the Docker-based configuration works rather than passing in a broadly
// compatible mode.
const dockerProfileActive = (process.env.BOOTUI_SAMPLE_PROFILES || '')
  .split(',')
  .map((profile) => profile.trim())
  .includes('docker')

test.describe('Docker profile smoke checks', () => {
  test.skip(!dockerProfileActive, 'Only runs when the sample app is started with the docker profile')

  test('reports the docker profile as active', async ({request}) => {
    const response = await request.get('/bootui/api/overview')
    expect(response.ok()).toBeTruthy()
    const overview = await response.json()
    expect(overview.activeProfiles).toContain('docker')
  })

  test('uses a PostgreSQL datasource', async ({request}) => {
    const response = await request.get('/actuator/health')
    expect(response.ok()).toBeTruthy()
    const health = await response.json()
    expect(health.components?.db?.details?.database).toBe('PostgreSQL')
  })

  test('uses a Redis-backed Spring cache', async ({request}) => {
    const response = await request.get('/bootui/api/cache')
    expect(response.ok()).toBeTruthy()
    const cache = await response.json()
    const managerTypes = (cache.managers || []).map((manager) => manager.type)
    expect(managerTypes.some((type) => /Redis/i.test(type ?? ''))).toBeTruthy()
  })

  test('serves PostgreSQL-backed sample products', async ({request}) => {
    const response = await request.get('/api/sample/products')
    expect(response.ok()).toBeTruthy()
    const products = await response.json()
    expect(Array.isArray(products)).toBeTruthy()
    expect(products.length).toBeGreaterThan(0)
  })

  test('answers a chat prompt through Ollama', async ({request}) => {
    const response = await request.post('/api/chat', {
      data: {message: 'Reply with the single word: pong.'},
      timeout: 120_000
    })
    expect(response.ok(), `chat request failed: ${response.status()} ${await response.text()}`).toBeTruthy()
    const body = await response.json()
    expect(typeof body.reply).toBe('string')
    expect(body.reply.length).toBeGreaterThan(0)
  })

  test('captures a real Kafka produce/consume round trip', async ({request}) => {
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
  })
})
