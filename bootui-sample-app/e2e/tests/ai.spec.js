// @ts-check
import {expect, test} from './fixtures.js'

const chatSpanId = '1111111111111111'

const overview = {
  enabled: true,
  springAiDetected: true,
  totalChats: 1,
  totalInputTokens: 42,
  totalOutputTokens: 7,
  tokensByModel: {'qwen2.5:0.5b': 49},
  callsByModel: {'qwen2.5:0.5b': 1},
  toolCallCount: 1,
  vectorOperationCount: 1,
  embeddingCount: 0,
  recent: [
    {
      traceId: '0123456789abcdef0123456789abcdef',
      spanId: chatSpanId,
      startEpochNanos: Date.now() * 1_000_000,
      durationNanos: 250_000_000,
      provider: 'ollama',
      requestModel: 'qwen2.5:0.5b',
      responseModel: 'qwen2.5:0.5b',
      inputTokens: 42,
      outputTokens: 7,
      totalTokens: 49,
      finishReason: 'stop',
      statusCode: 'OK',
      operation: 'chat',
      toolCallCount: 1,
      vectorOperationCount: 1
    }
  ],
  contentBanner: 'Prompt and completion text is not captured by default.'
}

const tokenSeries = {
  minutes: 60,
  buckets: [
    {epochMinute: 100, inputTokens: 0, outputTokens: 0, callCount: 0},
    {epochMinute: 101, inputTokens: 42, outputTokens: 7, callCount: 1}
  ]
}

const detail = {
  summary: overview.recent[0],
  toolCalls: [
    {
      spanId: '2222222222222222',
      name: 'getWeather',
      startEpochNanos: overview.recent[0].startEpochNanos + 5_000_000,
      durationNanos: 20_000_000,
      statusCode: 'OK'
    }
  ],
  vectorOperations: [
    {
      spanId: '3333333333333333',
      operation: 'query',
      collectionName: 'docs',
      startEpochNanos: overview.recent[0].startEpochNanos + 10_000_000,
      durationNanos: 15_000_000,
      statusCode: 'OK'
    }
  ],
  attributes: [{key: 'gen_ai.system', type: 'string', value: 'ollama'}],
  events: [],
  contentCaptured: false,
  contentBanner: 'Message content is not on this span.'
}

test.describe('AI Usage view', () => {
  test('renders AI token usage, model breakdowns, and chat detail', async ({page}) => {
    await stubAi(page, overview)

    await page.goto('/bootui/#/ai')
    await expect(
      page
        .locator('main h2')
        .filter({hasText: /AI Usage/})
        .first()
    ).toBeVisible()
    await expect(page.getByText('Spring AI detected')).toBeVisible()
    await expect(page.locator('.kpi-card-body', {hasText: 'Total tokens'}).getByText('49', {exact: true})).toBeVisible()
    await expect(page.locator('.card', {hasText: 'Usage by model'}).getByText('qwen2.5:0.5b')).toBeVisible()
    await expect(page.getByText('Token usage (last 60 min)')).toBeVisible()

    await page.getByRole('button', {name: 'Toggle chat details'}).click()
    await expect(page.locator('.card', {hasText: `Chat ${chatSpanId}`})).toBeVisible()
    await expect(page.locator('.chat-detail-row').getByText('getWeather', {exact: true})).toBeVisible()
    await expect(page.locator('.chat-detail-row').getByText('docs', {exact: true})).toBeVisible()
    await page.locator('.chat-detail-row summary', {hasText: 'gen_ai'}).click()
    await expect(page.locator('.chat-detail-row').getByText('gen_ai.system')).toBeVisible()
  })

  test('shows disabled mode when telemetry is unavailable', async ({page}) => {
    await stubAi(page, {
      ...overview,
      enabled: false,
      springAiDetected: false,
      totalChats: 0,
      totalInputTokens: 0,
      totalOutputTokens: 0,
      tokensByModel: {},
      callsByModel: {},
      recent: [],
      contentBanner: null
    })

    await page.goto('/bootui/#/ai')
    await expect(page.getByText('Enable the BootUI telemetry receiver')).toBeVisible()
    await expect(page.getByText('No AI chat completions recorded yet')).toHaveCount(0)
  })

  test('shows ready empty state before the first chat is recorded', async ({page}) => {
    await stubAi(page, {
      ...overview,
      totalChats: 0,
      totalInputTokens: 0,
      totalOutputTokens: 0,
      tokensByModel: {},
      callsByModel: {},
      toolCallCount: 0,
      vectorOperationCount: 0,
      embeddingCount: 0,
      recent: []
    })

    await page.goto('/bootui/#/ai')
    await expect(page.getByText('No AI chat completions recorded yet')).toBeVisible()
    await expect(page.getByText('Telemetry ready')).toBeVisible()
    await expect(page.getByText('Enable the BootUI telemetry receiver')).toHaveCount(0)
    await expect(page.getByText('OTLP exporter configured')).toHaveCount(0)
  })

  test('shows unavailable mode when Spring AI is missing', async ({page}) => {
    await stubAi(page, {
      ...overview,
      springAiDetected: false,
      totalChats: 0,
      totalInputTokens: 0,
      totalOutputTokens: 0,
      tokensByModel: {},
      callsByModel: {},
      recent: [],
      contentBanner: null
    })

    await page.goto('/bootui/#/ai')
    await expect(page.getByText('Spring AI on classpath')).toBeVisible()
    await expect(page.getByText('No AI chat completions recorded yet')).toHaveCount(0)
  })
})

async function stubAi(page, overviewResponse) {
  await stubShell(page, overviewResponse.enabled && overviewResponse.springAiDetected)
  await page.route(
    (url) => url.pathname === '/bootui/api/ai/overview',
    async (route) => {
      await route.fulfill({contentType: 'application/json', body: JSON.stringify(overviewResponse)})
    }
  )
  await page.route(
    (url) => url.pathname === '/bootui/api/ai/tokens',
    async (route) => {
      await route.fulfill({contentType: 'application/json', body: JSON.stringify(tokenSeries)})
    }
  )
  await page.route(
    (url) => url.pathname === `/bootui/api/ai/chats/${chatSpanId}`,
    async (route) => {
      await route.fulfill({contentType: 'application/json', body: JSON.stringify(detail)})
    }
  )
}

async function stubShell(page, aiAvailable) {
  await page.route(
    (url) => url.pathname === '/bootui/api/overview',
    async (route) => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          bootUiVersion: 'test',
          applicationName: 'bootui-sample',
          springBootVersion: '4.0.6',
          javaVersion: '25',
          javaVendor: 'test',
          activeProfiles: ['dev'],
          defaultProfiles: ['default'],
          webApplicationType: 'SERVLET',
          serverPort: 8080,
          managementPort: null,
          contextPath: '',
          startupTimeMillis: 1000,
          activation: {enabled: true, localhostOnly: true, reason: 'test', warnings: []},
          openApiUrl: null
        })
      })
    }
  )
  await page.route(
    (url) => url.pathname === '/bootui/api/panels',
    async (route) => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          panels: [
            {
              id: 'ai',
              title: 'AI Usage',
              available: aiAvailable,
              unavailableReason: aiAvailable ? null : 'AI usage unavailable in this test state'
            }
          ]
        })
      })
    }
  )
}
